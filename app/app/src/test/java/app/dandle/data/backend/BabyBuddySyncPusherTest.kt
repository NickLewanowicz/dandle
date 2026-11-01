package app.dandle.data.backend

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import app.dandle.data.api.BabyBuddyApi
import app.dandle.domain.model.EventDraft
import app.dandle.domain.model.EventType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException

/**
 * Adapter contract for the Baby Buddy transport (brief 05 phase 2): every wire failure maps
 * onto exactly one [BackendError] — the taxonomy the drainer and repos consume. The DRF
 * specifics (403 auth-sniff, 400 conflict parse, 404-on-delete-is-success) are pinned HERE so
 * they survive later refactors of the transport.
 */
class BabyBuddySyncPusherTest {

    private lateinit var server: MockWebServer
    private lateinit var api: BabyBuddyApi
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }

    private val draft = EventDraft(
        type = EventType.SLEEP,
        childId = 1L,
        startMillis = 1_760_000_000_000L,
        endMillis = 1_760_000_060_000L,
        noteText = "nap",
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = Retrofit.Builder()
            .baseUrl(server.url("/api/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(BabyBuddyApi::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun pusher() = BabyBuddySyncPusher({ api }, json)

    private fun enqueue(code: Int, body: String = "") {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
    }

    @Test
    fun `create returns the server id on 201`() = runTest {
        enqueue(201, """{"id": 42, "child": 1, "start": "2025-10-05T10:00:00Z", "end": "2025-10-05T10:10:00Z", "notes": "nap"}""")
        val result = pusher().pushCreate(EventType.SLEEP, draft)
        assertEquals(PushResult.Ok(42L), result)
        val recorded = server.takeRequest()
        assertEquals("/api/sleep/", recorded.path)
        assertTrue(recorded.body.readUtf8().contains(""""child":1"""))
    }

    @Test
    fun `400 maps to Validation with parsed conflict reference`() = runTest {
        enqueue(
            400,
            """{"non_field_errors": ["Another entry intersects the specified time period. """ +
                """Conflicting entry: <a href=\"/sleep/4/\">Sleep</a>"]}""",
        )
        val result = pusher().pushEdit(EventType.SLEEP, 7L, draft)
        val error = (result as PushResult.Err).error as BackendError.Validation
        assertEquals("sleep", error.conflictUiPath)
        assertEquals(4L, error.conflictServerId)
        assertTrue(error.displayReason!!.contains("intersects"))
    }

    @Test
    fun `401 maps to AuthExpired`() = runTest {
        enqueue(401, """{"detail":"Invalid token."}""")
        val result = pusher().pushEdit(EventType.SLEEP, 7L, draft)
        assertEquals(BackendError.AuthExpired, (result as PushResult.Err).error)
    }

    @Test
    fun `403 with token-invalid body maps to AuthExpired`() = runTest {
        enqueue(403, """{"detail":"Invalid token."}""")
        val result = pusher().pushEdit(EventType.SLEEP, 7L, draft)
        assertEquals(BackendError.AuthExpired, (result as PushResult.Err).error)
    }

    @Test
    fun `403 permission-denied does NOT map to AuthExpired`() = runTest {
        enqueue(403, """{"detail":"You do not have permission to perform this action."}""")
        val result = pusher().pushEdit(EventType.SLEEP, 7L, draft)
        val error = (result as PushResult.Err).error
        assertTrue(error is BackendError.Http && error.status == 403)
    }

    @Test
    fun `404 on edit maps to NotFound`() = runTest {
        enqueue(404, """{"detail":"Not found."}""")
        val result = pusher().pushEdit(EventType.SLEEP, 7L, draft)
        assertEquals(BackendError.NotFound, (result as PushResult.Err).error)
    }

    @Test
    fun `5xx maps to ServerError`() = runTest {
        enqueue(502, "bad gateway")
        val result = pusher().pushEdit(EventType.SLEEP, 7L, draft)
        assertEquals(BackendError.ServerError(502), (result as PushResult.Err).error)
    }

    @Test
    fun `other 4xx maps to Http with the status`() = runTest {
        enqueue(405, """{"detail":"Method not allowed."}""")
        val result = pusher().pushEdit(EventType.SLEEP, 7L, draft)
        val error = (result as PushResult.Err).error
        assertTrue(error is BackendError.Http && error.status == 405)
    }

    @Test
    fun `delete treats 404 as success`() = runTest {
        enqueue(404, "")
        val result = pusher().pushDelete(EventType.SLEEP, 7L)
        assertEquals(PushResult.Deleted, result)
    }

    @Test
    fun `delete reports other non-2xx as Http and does NOT special-case 401`() = runTest {
        enqueue(401, """{"detail":"Authentication credentials were not provided."}""")
        val result = pusher().pushDelete(EventType.SLEEP, 7L)
        val error = (result as PushResult.Err).error
        assertTrue(error is BackendError.Http && error.status == 401)
    }

    @Test
    fun `connection failure maps to Unreachable`() = runTest {
        val dead = MockWebServer()
        val url = dead.url("/api/")
        dead.shutdown()
        val disconnected = Retrofit.Builder()
            .baseUrl(url)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(BabyBuddyApi::class.java)
        val result = BabyBuddySyncPusher({ disconnected }, json).pushCreate(EventType.SLEEP, draft)
        assertEquals(BackendError.Unreachable, (result as PushResult.Err).error)
    }

    @Test
    fun `edit encodes only draft fields (partial PATCH body)`() = runTest {
        enqueue(200, """{"id": 7, "child": 1, "note": "nap", "time": "2025-10-05T10:00:00Z"}""")
        pusher().pushEdit(EventType.NOTE, 7L, draft.copy(type = EventType.NOTE, endMillis = draft.startMillis))
        val body = server.takeRequest().body.readUtf8()
        // NoteWriteDto: child/note/time — end is not a note field, notes carry the text.
        assertTrue(body.contains(""""note":"nap""""))
        assertTrue(body.contains(""""time":"""))
    }
}
