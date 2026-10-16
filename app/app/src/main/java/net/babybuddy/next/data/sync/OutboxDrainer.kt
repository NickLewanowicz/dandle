package net.babybuddy.next.data.sync

import kotlinx.serialization.json.Json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.babybuddy.next.data.api.ApiException
import net.babybuddy.next.data.api.BabyBuddyApi
import net.babybuddy.next.data.api.ChangeWriteDto
import net.babybuddy.next.data.api.FeedingWriteDto
import net.babybuddy.next.data.api.NoteWriteDto
import net.babybuddy.next.data.api.PumpingWriteDto
import net.babybuddy.next.data.api.SleepWriteDto
import net.babybuddy.next.data.api.TummyTimeWriteDto
import net.babybuddy.next.data.db.EventDao
import net.babybuddy.next.data.db.EventEntity
import net.babybuddy.next.data.db.OutboxDao
import net.babybuddy.next.data.db.OutboxOpEntity
import net.babybuddy.next.domain.Clock
import net.babybuddy.next.domain.ConflictPolicy
import net.babybuddy.next.domain.model.SyncState
import net.babybuddy.next.domain.model.EventType
import retrofit2.HttpException

/**
 * The outbox drain engine (spec §6, brief 00 pattern #1). Pure JVM (no android.*) so the whole
 * sync contract is unit-testable; SyncWorker is a thin WorkManager shell around [drain].
 *
 * Guarantees:
 * - Ops drain oldest-first (outbox id order) — replay order matches user intent.
 * - One failure never blocks others: 400s and permission-403s mark the single entry FAILED
 *   (or the op terminally errored) and draining continues with the next op.
 * - Network loss stops the drain cleanly (remaining ops keep their order for next time) —
 *   WorkManager retries with exponential backoff under a CONNECTED constraint.
 * - 401 (or token-invalid 403) pauses the whole queue via the session-expired flag; nothing
 *   is deleted; draining resumes after re-login.
 * - CREATE/EDIT/DELETE are idempotent-ish on replay: state is only advanced after the server
 *   acknowledges, and stale ops (target row already advanced) are dropped, not replayed.
 */
class OutboxDrainer(
    private val apiProvider: () -> BabyBuddyApi,
    private val json: Json,
    private val eventDao: EventDao,
    private val outboxDao: OutboxDao,
    private val clock: Clock,
    /** Reads the session-expired pause flag (SessionStore mirror). */
    private val isAuthPaused: () -> Boolean,
    /** Marks the session expired (DataStore) — called once, on the first auth failure. */
    private val onAuthExpired: suspend () -> Unit,
) {

    sealed interface Outcome {
        /** Every op either landed or terminally failed; outbox empty or fully processed. */
        data object Completed : Outcome

        /** Network lost mid-drain; remaining ops untouched (order preserved). */
        data object NetworkDown : Outcome

        /** Auth expired; queue paused (spec §6.3.6). */
        data object PausedAuth : Outcome
    }

    private val mutex = kotlinx.coroutines.sync.Mutex()

    /** Serialized drain: write-path one-shots and SyncWorker never double-post. */
    suspend fun drain(): Outcome = mutex.withLock {
        if (isAuthPaused()) return Outcome.PausedAuth
        return drainOnce()
    }

    private suspend fun drainOnce(): Outcome {
        for (op in outboxDao.snapshot()) {
            if (isAuthPaused()) return Outcome.PausedAuth
            val event = eventDao.byLocalUuid(op.localUuid)
            if (event == null && op.opType != OpTypes.DELETE) {
                // Target row vanished (deleted locally while queued); nothing to sync.
                outboxDao.delete(op.id)
                continue
            }
            val result = try {
                when (op.opType) {
                    OpTypes.CREATE -> processCreate(op, event!!)
                    OpTypes.EDIT -> processEdit(op, event)
                    OpTypes.DELETE -> processDelete(op)
                    else -> {
                        outboxDao.delete(op.id)
                        OpResult.Done
                    }
                }
            } catch (e: ApiException) {
                handleApiException(op, event, e)
            } catch (e: HttpException) {
                // Retrofit suspend calls surface non-2xx as HttpException; normalize and map.
                handleApiException(op, event, ApiException.from(e))
            } catch (e: java.io.IOException) {
                outboxDao.recordAttempt(op.id, e.message)
                return Outcome.NetworkDown // stop; order preserved for the next drain
            }
            when (result) {
                OpResult.Done -> Unit
                OpResult.Continue -> Unit
                OpResult.NetworkDown -> return Outcome.NetworkDown
                OpResult.Paused -> return Outcome.PausedAuth
            }
        }
        return Outcome.Completed
    }

    private object OpTypes {
        const val CREATE = "CREATE"
        const val EDIT = "EDIT"
        const val DELETE = "DELETE"
    }

    private enum class OpResult { Done, Continue, NetworkDown, Paused }

    // -- Op processors ---------------------------------------------------------

    private suspend fun processCreate(op: OutboxOpEntity, event: EventEntity): OpResult {
        if (event.serverId != null) {
            // Already created (e.g. crash between POST and op-delete); drop the stale op.
            outboxDao.delete(op.id)
            return OpResult.Done
        }
        val type = EventType.valueOf(op.entityType)
        val body = OutboxPayloads.decodeBody(json, op.payload)
        val serverId = when (type) {
            EventType.FEEDING ->
                apiProvider().createFeeding(json.decodeFromJsonElement(FeedingWriteDto.serializer(), body)).id
            EventType.CHANGE ->
                apiProvider().createChange(json.decodeFromJsonElement(ChangeWriteDto.serializer(), body)).id
            EventType.SLEEP ->
                apiProvider().createSleep(json.decodeFromJsonElement(SleepWriteDto.serializer(), body)).id
            EventType.NOTE ->
                apiProvider().createNote(json.decodeFromJsonElement(NoteWriteDto.serializer(), body)).id
            EventType.PUMPING ->
                apiProvider().createPumping(json.decodeFromJsonElement(PumpingWriteDto.serializer(), body)).id
            EventType.TUMMY_TIME ->
                apiProvider().createTummyTime(json.decodeFromJsonElement(TummyTimeWriteDto.serializer(), body)).id
        }
        eventDao.upsert(
            event.copy(
                serverId = serverId,
                syncState = SyncState.SYNCED.name,
                failureReason = null,
                conflictUiPath = null,
                conflictServerId = null,
                updatedAtMillis = clock.nowMillis(),
            )
        )
        outboxDao.delete(op.id)
        return OpResult.Done
    }

    private suspend fun processEdit(op: OutboxOpEntity, event: EventEntity?): OpResult {
        val serverId = OutboxPayloads.decodeServerId(json, op.payload)
        if (event == null || event.serverId != serverId) {
            // Entry deleted locally or its identity moved on; the queued edit is meaningless.
            outboxDao.delete(op.id)
            return OpResult.Done
        }
        val type = EventType.valueOf(op.entityType)
        val body = OutboxPayloads.decodeBody(json, op.payload)
        when (type) {
            EventType.FEEDING ->
                apiProvider().patchFeeding(serverId, json.decodeFromJsonElement(FeedingWriteDto.serializer(), body))
            EventType.CHANGE ->
                apiProvider().patchChange(serverId, json.decodeFromJsonElement(ChangeWriteDto.serializer(), body))
            EventType.SLEEP ->
                apiProvider().patchSleep(serverId, json.decodeFromJsonElement(SleepWriteDto.serializer(), body))
            EventType.NOTE ->
                apiProvider().patchNote(serverId, json.decodeFromJsonElement(NoteWriteDto.serializer(), body))
            EventType.PUMPING ->
                apiProvider().patchPumping(serverId, json.decodeFromJsonElement(PumpingWriteDto.serializer(), body))
            EventType.TUMMY_TIME ->
                apiProvider().patchTummyTime(serverId, json.decodeFromJsonElement(TummyTimeWriteDto.serializer(), body))
        }
        eventDao.upsert(
            event.copy(
                syncState = SyncState.SYNCED.name,
                failureReason = null,
                conflictUiPath = null,
                conflictServerId = null,
                updatedAtMillis = clock.nowMillis(),
            )
        )
        outboxDao.delete(op.id)
        return OpResult.Done
    }

    private suspend fun processDelete(op: OutboxOpEntity): OpResult {
        val serverId = OutboxPayloads.decodeServerId(json, op.payload)
        val type = EventType.valueOf(op.entityType)
        val response = when (type) {
            EventType.FEEDING -> apiProvider().deleteFeeding(serverId)
            EventType.CHANGE -> apiProvider().deleteChange(serverId)
            EventType.SLEEP -> apiProvider().deleteSleep(serverId)
            EventType.NOTE -> apiProvider().deleteNote(serverId)
            EventType.PUMPING -> apiProvider().deletePumping(serverId)
            EventType.TUMMY_TIME -> apiProvider().deleteTummyTime(serverId)
        }
        when {
            response.isSuccessful || response.code() == 404 -> {
                // Deleted (or already gone — another device beat us to it).
                eventDao.delete(op.localUuid)
                outboxDao.delete(op.id)
            }
            else -> {
                outboxDao.recordAttempt(op.id, "HTTP ${response.code()}")
            }
        }
        return OpResult.Done
    }

    // -- Error mapping ----------------------------------------------------------

    private suspend fun handleApiException(
        op: OutboxOpEntity,
        event: EventEntity?,
        e: ApiException,
    ): OpResult {
        if (isAuthFailure(e)) {
            onAuthExpired()
            return OpResult.Paused
        }
        if (e.status == 400) {
            // Server rejected the content — permanent for this payload. Badge the entry,
            // drop the op (spec §6.2: FAILED is user-resolvable; never blocks other entries).
            val validation = e.validation
            eventDao.updateSyncState(
                localUuid = op.localUuid,
                syncState = SyncState.FAILED.name,
                failureReason = validation?.displayReason ?: e.rawBody?.take(500) ?: "The server rejected this entry.",
                conflictUiPath = validation?.conflictingEntry?.uiPath,
                conflictServerId = validation?.conflictingEntry?.serverId,
                updatedAt = clock.nowMillis(),
            )
            outboxDao.delete(op.id)
            return OpResult.Continue
        }
        if (e.status == 404 && op.opType != OpTypes.CREATE) {
            // Edit/delete target is gone server-side. For DELETE that's success; for EDIT the
            // entry no longer exists — surface it and drop the doomed op.
            if (op.opType == OpTypes.DELETE) {
                eventDao.delete(op.localUuid)
            } else {
                eventDao.updateSyncState(
                    localUuid = op.localUuid,
                    syncState = SyncState.FAILED.name,
                    failureReason = "This entry was deleted on the server.",
                    conflictUiPath = null,
                    conflictServerId = null,
                    updatedAt = clock.nowMillis(),
                )
            }
            outboxDao.delete(op.id)
            return OpResult.Continue
        }
        if (e.status in 500..599) {
            outboxDao.recordAttempt(op.id, "HTTP ${e.status}")
            return OpResult.Continue // server hiccup; keep draining the rest, retry later
        }
        // Other 4xx (unexpected): terminally fail the entry like a 400.
        eventDao.updateSyncState(
            localUuid = op.localUuid,
            syncState = SyncState.FAILED.name,
            failureReason = "The server rejected this entry (HTTP ${e.status}).",
            conflictUiPath = null,
            conflictServerId = null,
            updatedAt = clock.nowMillis(),
        )
        outboxDao.delete(op.id)
        return OpResult.Continue
    }

    /**
     * Auth failures pause the queue. DRF returns 403 (not 401) for missing/invalid tokens
     * (research doc §2) — but 403 also means "permission denied" (e.g. caregiver delete),
     * which must NOT pause anything. Distinguish by the server's own message.
     */
    private fun isAuthFailure(e: ApiException): Boolean {
        if (e.status == 401) return true
        if (e.status != 403) return false
        val body = e.rawBody.orEmpty()
        return listOf("Invalid token", "access has expired", "credentials were not provided")
            .any { body.contains(it, ignoreCase = true) }
    }
}
