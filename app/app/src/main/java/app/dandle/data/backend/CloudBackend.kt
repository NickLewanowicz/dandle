package app.dandle.data.backend

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import app.dandle.data.cloud.CloudAuth
import app.dandle.data.cloud.CloudError
import app.dandle.data.cloud.CloudLoginResult
import app.dandle.data.cloud.PocketBaseClient
import app.dandle.data.cloud.PocketBaseException
import app.dandle.data.crypto.DandleCrypto
import app.dandle.data.crypto.DekEnvelope
import app.dandle.data.crypto.RecordCipher
import app.dandle.data.crypto.WrongPassphraseException
import app.dandle.data.crypto.b64
import app.dandle.data.crypto.unb64
import app.dandle.data.db.ChildEntity
import app.dandle.data.db.EventEntity
import app.dandle.data.mapper.serverLocalUuid
import app.dandle.domain.model.EventDraft
import app.dandle.domain.model.EventType
import app.dandle.domain.model.SyncState

/** PocketBase collections (brief 05 §5.1; schema applied by scripts/cloud/apply-schema.sh). */
object CloudCollections {
    const val RECORDS = "sync_records"
    const val USERS = "users"
}

/**
 * Dandle Cloud adapter (brief 05 §5): encrypt → push, pull → decrypt → LWW merge inputs.
 * The server sees only ciphertext + type/timing metadata; everything domain-shaped is
 * AES-256-GCM sealed with an HKDF key off the account DEK, AAD-bound to the record id.
 */
class CloudBackend(
    private val pb: PocketBaseClient,
    private val json: Json,
) {

    // -- Auth (CloudAuth) ---------------------------------------------------------

    /**
     * Sign-in flow (brief 05 §5.2): password auth → read the account's envelope → unwrap the
     * DEK with the passphrase (wrong passphrase = WRONG_PASSPHRASE, distinct from bad
     * credentials) → install the DEK for RecordCipher → return token+envelope.
     */
    private fun signInInternal(username: String, password: String, passphrase: CharArray): AuthSuccess {
        val auth = pb.authWithPassword(username, password)
        val user = pb.getUserRecord(auth.token, auth.userId)
        val envelopeText = userEnvelope(user) ?: throw CloudFailure(CloudError.HTTP(0))
        val envelope = DekEnvelope.fromJson(envelopeText)
        val dek = try {
            DekEnvelope.unwrap(envelope, passphrase)
        } catch (_: WrongPassphraseException) {
            throw CloudFailure(CloudError.WRONG_PASSPHRASE)
        }
        RecordCipher.install(dek.encoded)
        return AuthSuccess(auth.token, envelopeText, username, auth.userId, dek.encoded)
    }

    private fun userEnvelope(user: JsonObject): String? =
        (user["wrapped_dek"] as? kotlinx.serialization.json.JsonPrimitive)?.content

    /** Creates the account + first envelope. Username collisions map to USERNAME_TAKEN. */
    private fun signUpInternal(username: String, password: String, passphrase: CharArray): AuthSuccess {
        val (envelope, dek) = DekEnvelope.create(passphrase)
        try {
            pb.createUser(
                username = username,
                password = password,
                fields = buildJsonObject {
                    put("enc_salt", b64(envelope.salt))
                    put("enc_params", envelopeKdfJson(envelope))
                    put("wrapped_dek", envelope.toJson())
                    put("crypto_version", DandleCrypto.CRYPTO_VERSION)
                },
            )
            RecordCipher.install(dek.encoded)
            val auth = pb.authWithPassword(username, password)
            return AuthSuccess(auth.token, envelope.toJson(), username, auth.userId, dek.encoded)
        } catch (e: PocketBaseException) {
            // Only a genuine COLLISION is parent-language "name already taken" (QA 2026-10-31):
            // a username-400 without a taken marker (pattern rejection, validation) must stay
            // HTTP(400) — mapping every username-400 to USERNAME_TAKEN dead-ended dotted names
            // silently, with the form claiming the name was taken when the server said "invalid".
            val body = e.body.orEmpty()
            if (e.status == 400 &&
                body.contains("username", ignoreCase = true) &&
                listOf("taken", "unique", "already").any { body.contains(it, ignoreCase = true) }
            ) {
                throw CloudFailure(CloudError.USERNAME_TAKEN)
            }
            throw e
        }
    }

    val auth: CloudAuth = object : CloudAuth {
        override suspend fun signUp(username: String, password: String, passphrase: String): CloudLoginResult =
            authCall { signUpInternal(username, password, passphrase.toCharArray()) }

        override suspend fun signIn(username: String, password: String, passphrase: String): CloudLoginResult =
            authCall { signInInternal(username, password, passphrase.toCharArray()) }
    }

    private class CloudFailure(val error: CloudError) : Exception(error.toString())

    private class AuthSuccess(val token: String, val envelope: String, val username: String, val userId: String, val dek: ByteArray)

    /**
     * P0 (QA 2026-10-31): PocketBaseClient is a BLOCKING OkHttp client — unlike the Retrofit
     * suspend stack it is NOT main-safe. Every entry point here hops to IO so callers on
     * viewModelScope (Main.immediate) can never hit NetworkOnMainThreadException; the
     * error-mapping try/catch stays on the caller's dispatcher, AFTER the hop returns
     * (withContext rethrows the original exceptions unchanged, so the order is intact).
     */
    private suspend fun authCall(block: () -> AuthSuccess): CloudLoginResult = try {
        val ok = withContext(Dispatchers.IO) { block() }
        CloudLoginResult.Success(ok.token, ok.envelope, ok.username, ok.userId, ok.dek)
    } catch (e: CloudFailure) {
        CloudLoginResult.Failure(e.error)
    } catch (e: PocketBaseException) {
        CloudLoginResult.Failure(
            when (e.status) {
                401, 403 -> CloudError.BAD_CREDENTIALS
                else -> CloudError.HTTP(e.status)
            }
        )
    } catch (e: java.io.IOException) {
        CloudLoginResult.Failure(CloudError.UNREACHABLE)
    }

    // -- Push (SyncPusher) ----------------------------------------------------------

    fun pusher(token: String, userId: String): SyncPusher = CloudSyncPusher(pb, json, token, userId)

    class CloudSyncPusher(
        private val pb: PocketBaseClient,
        private val json: Json,
        private val token: String,
        private val userId: String,
    ) : SyncPusher {

        // mintRecordId moved to the CloudBackend companion (P1 QA 2026-10-31): pushChild
        // mints + sends ids too, so both share one implementation.

        private fun payloadFor(draft: EventDraft, recordId: String): JsonObject = buildJsonObject {
            put("type", draft.type.name)
            put("childId", draft.childId)
            put("start", draft.startMillis)
            put("end", draft.endMillis)
            draft.feedingType?.let { put("feedingType", it) }
            draft.feedingMethod?.let { put("feedingMethod", it) }
            draft.amount?.let { put("amount", it) }
            draft.wet?.let { put("wet", it) }
            draft.solid?.let { put("solid", it) }
            draft.color?.let { put("color", it) }
            draft.noteText?.let { put("noteText", it) }
            draft.milestone?.let { put("milestone", it) }
        }

        private fun seal(draft: EventDraft, recordId: String): String =
            b64(RecordCipher.encrypt(payloadFor(draft, recordId).toString().toByteArray(), CloudCollections.RECORDS, recordId))

        override suspend fun pushCreate(type: EventType, draft: EventDraft): PushResult = cloudWrap {
            val recordId = mintRecordId()
            pb.createRecord(
                token = token,
                collection = CloudCollections.RECORDS,
                fields = buildJsonObject {
                    // Client-minted id MUST ride the create: PocketBase accepts provided ids,
                    // and every later edit/pull is keyed to it (server-generated ids would
                    // orphan the AAD-bound ciphertext and 404 the first edit).
                    put("id", recordId)
                    put("user", userId)
                    put("type", type.name)
                    put("payload", seal(draft, recordId))
                    put("client_updated", System.currentTimeMillis())
                    put("deleted", false)
                    put("schema_v", 1)
                },
            )
            // The record already carries our minted id server-side; report it as the identity.
            PushResult.Ok(recordId)
        }

        override suspend fun pushEdit(type: EventType, serverId: String, draft: EventDraft): PushResult = cloudWrap {
            pb.updateRecord(
                token = token,
                collection = CloudCollections.RECORDS,
                recordId = serverId,
                fields = buildJsonObject {
                    put("payload", seal(draft, serverId))
                    put("client_updated", System.currentTimeMillis())
                },
            )
            PushResult.Ok(serverId)
        }

        override suspend fun pushDelete(type: EventType, serverId: String): PushResult = cloudWrap {
            // Tombstone patch, never a hard delete (brief 05 §5.1 deleteRule: null).
            pb.updateRecord(
                token = token,
                collection = CloudCollections.RECORDS,
                recordId = serverId,
                fields = buildJsonObject {
                    put("deleted", true)
                    put("client_updated", System.currentTimeMillis())
                },
            )
            PushResult.Deleted
        }

        /**
         * Shared tail of pushCreate/pushEdit/pushDelete (P0 main-safety): the blocking pb call
         * runs on IO; mapping happens after the hop returns (originals rethrown unchanged).
         */
        private suspend fun cloudWrap(block: () -> PushResult): PushResult = try {
            withContext(Dispatchers.IO) { block() }
        } catch (e: PocketBaseException) {
            PushResult.Err(CloudBackend.toBackendError(e))
        } catch (e: java.io.IOException) {
            PushResult.Err(BackendError.Unreachable)
        }
    }

    // -- Pull (PullGateway) -----------------------------------------------------------

    fun puller(token: String, userId: String): PullGateway = CloudPuller(pb, json, token, userId)

    class CloudPuller(
        private val pb: PocketBaseClient,
        private val json: Json,
        private val token: String,
        private val userId: String,
    ) : PullGateway {

        override suspend fun listChildren(): List<ChildEntity> = withContext(Dispatchers.IO) {
            val page = pb.listRecords(token, CloudCollections.RECORDS, userId, sinceUpdated = null)
            page.items.mapNotNull { record -> record.toChildOrNull() }
        }

        override suspend fun pullRecentEvents(limitPerType: Int): List<EventEntity> = withContext(Dispatchers.IO) {
            val page = pb.listRecords(token, CloudCollections.RECORDS, userId, sinceUpdated = null)
            page.items.mapNotNull { record -> record.toEventOrNull() }
        }

        // Records carry plaintext type + client_updated; payload is sealed JSON.

        private fun PocketBaseClient.Record.toEventOrNull(): EventEntity? {
            val typeName = fields.plain("type") ?: return null
            val type = runCatching { EventType.valueOf(typeName) }.getOrNull() ?: return null
            if (typeName == "child") return null
            val payload = fields.plain("payload") ?: return null
            val clientUpdated = fields.plain("client_updated")?.toLongOrNull() ?: 0L
            val deleted = fields.plain("deleted") == "true"
            val plaintext = try {
                RecordCipher.decrypt(unb64(payload), CloudCollections.RECORDS, id).decodeToString()
            } catch (e: Exception) {
                // P1 (QA 2026-10-31): never silent — an undecryptable record used to vanish
                // with no trace and QA had to root-cause from Room dumps. Log type + id.
                app.dandle.log.BbLog.d(
                    app.dandle.log.BbLog.TAG_SYNC,
                    "cloud pull decrypt failed; skipped record type=$typeName id=$id (${e.javaClass.simpleName})",
                )
                return null // undecryptable (different account key era) — skip, never crash the timeline
            }
            val obj = json.parseToJsonElement(plaintext).jsonObject
            fun str(key: String) = (obj[key] as? kotlinx.serialization.json.JsonPrimitive)?.content
            fun long(key: String) = str(key)?.toLongOrNull()
            return EventEntity(
                localUuid = serverLocalUuid(type, id),
                serverId = id,
                type = type.name,
                childId = str("childId") ?: return null,
                startMillis = long("start") ?: 0L,
                endMillis = long("end") ?: 0L,
                feedingType = str("feedingType"),
                feedingMethod = str("feedingMethod"),
                amount = str("amount")?.toDoubleOrNull(),
                wet = str("wet")?.toBooleanStrictOrNull(),
                solid = str("solid")?.toBooleanStrictOrNull(),
                color = str("color"),
                noteText = str("noteText"),
                milestone = str("milestone"),
                syncState = SyncState.SYNCED.name,
                failureReason = null,
                conflictUiPath = null,
                conflictServerId = null,
                createdAtMillis = 0,
                updatedAtMillis = clientUpdated,
                deleted = deleted,
            )
        }

        private fun PocketBaseClient.Record.toChildOrNull(): ChildEntity? {
            if (fields.plain("type") != "child") return null
            val payload = fields.plain("payload") ?: return null
            val plaintext = try {
                RecordCipher.decrypt(unb64(payload), CloudCollections.RECORDS, id).decodeToString()
            } catch (e: Exception) {
                // P1 (QA 2026-10-31): an undecryptable child read as "No child" forever with
                // zero log output. Always name the type + record id (see BB/SYNC tail).
                app.dandle.log.BbLog.d(
                    app.dandle.log.BbLog.TAG_SYNC,
                    "cloud pull decrypt failed; skipped record type=child id=$id (${e.javaClass.simpleName})",
                )
                return null
            }
            val obj = json.parseToJsonElement(plaintext).jsonObject
            fun str(key: String) = (obj[key] as? kotlinx.serialization.json.JsonPrimitive)?.content
            return ChildEntity(
                id = id,
                firstName = str("firstName") ?: return null,
                lastName = null,
                birthDate = str("birthDate") ?: "",
                birthTime = null,
                slug = "cloud-$id",
            )
        }

        private fun JsonObject.plain(key: String): String? =
            (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content
    }

    // -- Child push (cloud child setup) -----------------------------------------------

    /**
     * Pushes a locally-created child as an encrypted record (brief 05 §5.4). P1 fix (QA
     * 2026-10-31): the record id is client-MINTED and rides the create (same contract as
     * [CloudSyncPusher.pushCreate]). The old code sealed the payload with the LOCAL child
     * UUID as AAD but let PocketBase assign its own 15-char id — every pull then decrypted
     * with the server id as AAD, the GCM tag check failed, and the child was silently
     * dropped ("No child" forever, no error).
     *
     * Returns the minted record id on success; the caller (AppContainer.pushCloudChild)
     * adopts it as the child's identity on this device. On failure the exception propagates
     * (LoginViewModel catches + logs): NO adoption happens — the child stays local-only and
     * the next child-setup pass pushes it again with a fresh minted id.
     */
    suspend fun pushChild(token: String, userId: String, child: ChildEntity): String {
        val recordId = mintRecordId()
        val payload = buildJsonObject {
            put("firstName", child.firstName)
            put("birthDate", child.birthDate)
        }
        withContext(Dispatchers.IO) {
            pb.createRecord(
                token = token,
                collection = CloudCollections.RECORDS,
                fields = buildJsonObject {
                    // The minted id MUST ride the create: the payload's GCM AAD is bound to
                    // it and the pull side decrypts with the server record id — a
                    // server-assigned id would orphan the ciphertext (the P1 bug above).
                    put("id", recordId)
                    put("user", userId)
                    put("type", "child")
                    put("payload", b64(RecordCipher.encrypt(payload.toString().toByteArray(), CloudCollections.RECORDS, recordId)))
                    put("client_updated", System.currentTimeMillis())
                    put("deleted", false)
                    put("schema_v", 1)
                },
            )
        }
        return recordId
    }

    suspend fun hasChildren(token: String, userId: String): Boolean = withContext(Dispatchers.IO) {
        pb.listRecords(token, CloudCollections.RECORDS, userId, sinceUpdated = null)
            .items.any { it.plainSafe("type") == "child" }
    }

    private fun PocketBaseClient.Record.plainSafe(key: String): String? =
        (fields[key] as? kotlinx.serialization.json.JsonPrimitive)?.content

    private fun envelopeKdfJson(envelope: DekEnvelope): String =
        """{"m":${envelope.kdf.memoryKib},"t":${envelope.kdf.iterations},"p":${envelope.kdf.parallelism}}"""

    companion object {
        /**
         * Client-mints the record id so the AAD can bind the ciphertext to it BEFORE the
         * server ever sees the row (brief 05 §5.2). Shared by event pushes
         * ([CloudSyncPusher]) and the child push ([pushChild]) — PocketBase accepts provided
         * ids matching `^[a-z0-9]{15}$`, which this satisfies.
         */
        internal fun mintRecordId(): String =
            buildString {
                val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
                repeat(15) { append(alphabet[kotlin.random.Random.nextInt(alphabet.length)]) }
            }

        internal fun toBackendError(e: PocketBaseException): BackendError = when {
            e.status == 401 -> BackendError.AuthExpired
            e.status == 404 -> BackendError.NotFound
            e.status in 500..599 -> BackendError.ServerError(e.status)
            else -> BackendError.Http(e.status, e.body)
        }
    }
}
