package app.dandle.data.backend

import app.dandle.data.crypto.RecordCipher
import app.dandle.data.crypto.unb64
import app.dandle.data.cloud.PocketBaseClient
import app.dandle.data.db.ChildEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.SecureRandom

/**
 * Cloud child push wire contract (P1 fix, QA 2026-10-31): the OLD pushChild sealed the child
 * payload with the LOCAL child UUID as AAD but did NOT send an id, so PocketBase minted its
 * own 15-char record id — every pull decrypted with the server id as AAD, the GCM tag check
 * failed, and the child was silently dropped ("No child" dashboard forever, nothing logged).
 *
 * These drive the REAL PocketBaseClient + real AES-GCM over MockWebServer (same seam as
 * CloudBackendSignUpMappingTest) and pin:
 *  - the create body carries a client-minted id matching PocketBase's ^[a-z0-9]{15}$ pattern,
 *  - the returned identity IS that sent id (adoption joins on it),
 *  - the ciphertext decrypts under the SENT id's AAD and carries the child name,
 *  - the ciphertext does NOT decrypt under the local UUID's AAD (the old bug, inverted).
 */
class CloudBackendChildPushRoundTripTest {

    private lateinit var server: MockWebServer

    /** A uuid-shaped LOCAL child id (what Room used before adoption). */
    private val localChildId = "3fa85f64-5717-4562-b3fc-2c963f66afa6"

    private val child = ChildEntity(
        id = localChildId,
        firstName = "Nova",
        lastName = null,
        birthDate = "2026-03-01",
        birthTime = null,
        slug = "nova",
    )

    /**
     * PocketBase stand-in for sync_records creates: echoes the request's own "id" back,
     * exactly how PocketBase honors client-provided ids.
     */
    private fun backendEchoingCreate(): CloudBackend {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                return if (request.path == "/api/collections/${CloudCollections.RECORDS}/records" &&
                    request.method == "POST"
                ) {
                    val sentId = Json.parseToJsonElement(body).jsonObject["id"]?.let { it as JsonPrimitive }?.content
                        ?: error("create body carried no id — PocketBase would mint its own (the P1 bug)")
                    MockResponse().setResponseCode(200).setBody("""{"id":"$sentId"}""")
                } else {
                    MockResponse().setResponseCode(404).setBody("""{"code":404}""")
                }
            }
        }
        server.start()
        return CloudBackend(
            pb = PocketBaseClient(
                baseUrl = server.url("/").toString(),
                client = OkHttpClient(),
                json = Json { ignoreUnknownKeys = true },
            ),
            json = Json { ignoreUnknownKeys = true },
        )
    }

    @Before
    fun installDek() {
        // Direct DEK install (no Argon2 cost): the round trip only needs RecordCipher keyed.
        RecordCipher.install(ByteArray(32).also(SecureRandom()::nextBytes))
    }

    @After
    fun tearDown() {
        RecordCipher.reset() // don't leak a key into other tests (singleton cipher)
        if (::server.isInitialized) server.shutdown()
    }

    @Test
    fun `pushChild sends a minted id, returns it, and the payload decrypts under that sent id`() = runBlocking {
        val backend = backendEchoingCreate()

        val returnedId = backend.pushChild("token", "user-1", child)

        val recorded = server.takeRequest()
        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        val sentId = (body["id"] as JsonPrimitive).content

        assertTrue(
            "PocketBase id pattern must be satisfied by the client-minted id",
            sentId.matches(Regex("^[a-z0-9]{15}$")),
        )
        assertEquals("the adoption identity must be the id that rode the wire", sentId, returnedId)
        assertEquals("child", (body["type"] as JsonPrimitive).content)

        // (b) AAD bound to the id SENT ON THE WIRE — the exact check CloudPuller does on pull.
        val plaintext = RecordCipher.decrypt(
            unb64((body["payload"] as JsonPrimitive).content),
            CloudCollections.RECORDS,
            sentId,
        ).decodeToString()
        assertTrue("decrypted child payload must carry the child name", plaintext.contains("Nova"))
    }

    @Test
    fun `the payload does NOT decrypt under the local child uuid AAD - the old silent-drop bug`() = runBlocking {
        val backend = backendEchoingCreate()
        backend.pushChild("token", "user-1", child)

        val recorded = server.takeRequest()
        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        val payload = (body["payload"] as JsonPrimitive).content

        val decryptUnderLocalUuid = runCatching {
            RecordCipher.decrypt(unb64(payload), CloudCollections.RECORDS, localChildId)
        }
        assertTrue(
            "sealing must have moved off the local UUID (old bug: pull AAD mismatch, silent skip)",
            decryptUnderLocalUuid.isFailure,
        )
    }
}
