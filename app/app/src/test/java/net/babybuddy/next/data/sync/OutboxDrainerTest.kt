package net.babybuddy.next.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import net.babybuddy.next.data.api.ApiException
import net.babybuddy.next.data.api.BabyBuddyApi
import net.babybuddy.next.data.db.BabyBuddyDatabase
import net.babybuddy.next.data.mapper.toModel
import net.babybuddy.next.data.repo.EventRepository
import net.babybuddy.next.domain.Clock
import net.babybuddy.next.domain.model.SyncState
import net.babybuddy.next.domain.model.EventDraft
import net.babybuddy.next.domain.model.EventType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sync-engine contract (spec §6, brief 00 pattern #1), proven on Robolectric + in-memory Room +
 * MockWebServer: replay in order, 400 → FAILED + conflict parsing, 401 → pause, per-entry
 * isolation, edit/delete replay, and offline create → queue.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class OutboxDrainerTest {

    private lateinit var db: BabyBuddyDatabase
    private lateinit var server: MockWebServer
    private lateinit var api: BabyBuddyApi
    private lateinit var repository: EventRepository

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }
    private var authPaused = false
    private val now = 1_760_000_000_000L

    /** Requests the dispatcher saw, as "METHOD path" in arrival order. */
    private val seenRequests = mutableListOf<String>()

    /** Per-request behavior queue: each entry maps a request to a canned response. */
    private val responses = ArrayDeque<MockResponse>()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, BabyBuddyDatabase::class.java).build()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seenRequests += "${request.method} ${request.requestUrl!!.encodedPath}"
                return responses.removeFirstOrNull()
                    ?: MockResponse().setResponseCode(500).setBody("""{"detail":"unscripted"}""")
            }
        }
        server.start()
        api = Retrofit.Builder()
            .baseUrl(server.url("/api/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(BabyBuddyApi::class.java)
        repository = EventRepository(
            apiProvider = { api },
            db = db,
            json = json,
            clock = Clock.fixed(now),
            drainer = drainer(),
            requestDrain = { /* under manual control in tests */ },
        )
    }

    private fun drainer() = OutboxDrainer(
        apiProvider = { api },
        json = json,
        eventDao = db.eventDao(),
        outboxDao = db.outboxDao(),
        clock = Clock.fixed(now),
        isAuthPaused = { authPaused },
        onAuthExpired = { authPaused = true },
    )

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
    }

    // -- Helpers -----------------------------------------------------------------

    private fun changeDraft(
        wet: Boolean = true,
        solid: Boolean = false,
        offsetMinutes: Int = 0,
    ) = EventDraft(
        type = EventType.CHANGE,
        childId = 1,
        startMillis = now - offsetMinutes * 60_000L,
        endMillis = now - offsetMinutes * 60_000L,
        wet = wet,
        solid = solid,
    )

    private fun feedingDraft() = EventDraft(
        type = EventType.FEEDING,
        childId = 1,
        startMillis = now - 900_000,
        endMillis = now,
        feedingType = "formula",
        feedingMethod = "bottle",
        amount = 120.0,
    )

    private suspend fun timeline() = db.eventDao().observeTimeline(1)

    private suspend fun allEvents() = timeline().first().map { it.toModel() }

    private suspend fun outboxSnapshot() = db.outboxDao().snapshot()

    private fun createdChange201(id: Long) = MockResponse().setResponseCode(201)
        .setBody("""{"id":$id,"child":1,"time":"2026-10-16T08:30:00-04:00","wet":true,"solid":false,"color":null,"amount":null,"notes":null,"tags":[]}""")

    private fun createdFeeding201(id: Long) = MockResponse().setResponseCode(201)
        .setBody("""{"id":$id,"child":1,"start":"2026-10-16T09:00:00-04:00","end":"2026-10-16T09:15:00-04:00","duration":"00:15:00","type":"formula","method":"bottle","amount":120.0,"notes":null,"tags":[]}""")

    // -- Tests ---------------------------------------------------------------------

    @Test
    fun `offline create queues PENDING with no server id, drain replays and syncs`() = runTest {
        val event = repository.create(changeDraft())
        assertEquals(SyncState.PENDING, event.syncState)
        assertNull(event.serverId)
        assertEquals(1, outboxSnapshot().size)

        responses.add(createdChange201(948))
        val outcome = drainer().drain()

        assertEquals(OutboxDrainer.Outcome.Completed, outcome)
        val synced = allEvents().single()
        assertEquals(SyncState.SYNCED, synced.syncState)
        assertEquals(948L, synced.serverId)
        assertTrue(outboxSnapshot().isEmpty())
        assertEquals(listOf("POST /api/changes/"), seenRequests)

        // The replayed POST body carries the exact server field names.
        val sentBody = server.takeRequest().body.readUtf8()
        val body = json.parseToJsonElement(sentBody).jsonObject
        assertEquals(1L, body["child"]!!.toString().toLong())
        assertEquals("true", body["wet"]!!.toString())
        assertEquals("false", body["solid"]!!.toString())
        assertNotNull(body["time"])
    }

    @Test
    fun `ops replay oldest-first and preserve enqueue order`() = runTest {
        repository.create(changeDraft())      // op 1
        repository.create(feedingDraft())     // op 2

        responses.add(createdChange201(1))
        responses.add(createdFeeding201(2))
        drainer().drain()

        assertEquals(
            listOf("POST /api/changes/", "POST /api/feedings/"),
            seenRequests,
        )
    }

    @Test
    fun `400 overlap marks the entry FAILED with parsed conflict and does not block others`() = runTest {
        val first = repository.create(changeDraft())
        val second = repository.create(feedingDraft())

        // First op: server-side overlap rejection with the embedded conflict link (research doc §5.1).
        responses.add(
            MockResponse().setResponseCode(400).setBody(
                """{"non_field_errors": ["Another entry intersects the specified time period. Conflicting entry: <a href=\"/feedings/7/\">Feeding (11/19/17 03:00 - 11/19/17 04:30)</a>"]}"""
            )
        )
        responses.add(createdFeeding201(77)) // second op succeeds
        drainer().drain()

        val events = allEvents().sortedBy { it.localUuid }
        val failed = events.first { it.localUuid == first.localUuid }
        val synced = events.first { it.localUuid == second.localUuid }

        assertEquals(SyncState.FAILED, failed.syncState)
        assertTrue(failed.failureReason!!.contains("intersects"))
        assertEquals("feedings", failed.conflictUiPath)
        assertEquals(7L, failed.conflictServerId)
        assertEquals(SyncState.SYNCED, synced.syncState)
        assertEquals(77L, synced.serverId)
        assertTrue(outboxSnapshot().isEmpty()) // the doomed op is dropped, not retried forever
    }

    @Test
    fun `401 pauses the queue, flags the session, and leaves remaining ops untouched`() = runTest {
        val first = repository.create(changeDraft())
        val second = repository.create(feedingDraft())

        responses.add(
            MockResponse().setResponseCode(401).setBody("""{"detail":"Invalid token."}""")
        )
        val outcome = drainer().drain()

        assertEquals(OutboxDrainer.Outcome.PausedAuth, outcome)
        assertTrue(authPaused)
        // Nothing deleted: the paused op stays queued for the post-relogin drain.
        assertEquals(2, outboxSnapshot().size)
        val events = allEvents()
        assertEquals(SyncState.PENDING, events.first { it.localUuid == second.localUuid }.syncState)
        assertEquals(listOf("POST /api/changes/"), seenRequests) // second op never attempted
    }

    @Test
    fun `permission 403 does not pause the queue (caregiver delete case)`() = runTest {
        repository.create(changeDraft())
        responses.add(
            MockResponse().setResponseCode(403)
                .setBody("""{"detail": "You do not have permission to perform this action."}""")
        )
        val outcome = drainer().drain()
        assertEquals(OutboxDrainer.Outcome.Completed, outcome)
        assertFalse(authPaused)
    }

    @Test
    fun `network failure mid-drain stops cleanly and preserves remaining ops`() = runTest {
        repository.create(changeDraft())
        repository.create(feedingDraft())
        // Simulate a dead network by shutting the server down before draining.
        server.shutdown()
        val outcome = drainer().drain()
        assertEquals(OutboxDrainer.Outcome.NetworkDown, outcome)
        assertEquals(2, outboxSnapshot().size)
        assertTrue(allEvents().all { it.syncState == SyncState.PENDING })
    }

    @Test
    fun `one 500 never blocks other entries`() = runTest {
        repository.create(changeDraft())
        repository.create(feedingDraft())
        responses.add(MockResponse().setResponseCode(503).setBody("down"))
        responses.add(createdFeeding201(5))
        drainer().drain()
        val events = allEvents()
        assertEquals(SyncState.PENDING, events.first { it.type == EventType.CHANGE }.syncState)
        val feeding = events.first { it.type == EventType.FEEDING }
        assertEquals(SyncState.SYNCED, feeding.syncState)
        assertEquals(1, outboxSnapshot().size) // only the 503'd op remains
    }

    @Test
    fun `edit of a synced entry queues a PATCH and syncs`() = runTest {
        val created = repository.create(changeDraft())
        responses.add(createdChange201(948))
        drainer().drain()

        repository.edit(created.localUuid, changeDraft(solid = true, offsetMinutes = 5))
        assertEquals(SyncState.PENDING, allEvents().single().syncState)

        responses.add(
            MockResponse().setResponseCode(200)
                .setBody("""{"id":948,"child":1,"time":"2026-10-16T08:30:00-04:00","wet":true,"solid":true,"color":null,"amount":null,"notes":null,"tags":[]}""")
        )
        drainer().drain()

        assertEquals(listOf("POST /api/changes/", "PATCH /api/changes/948/"), seenRequests)
        val patched = allEvents().single()
        assertEquals(SyncState.SYNCED, patched.syncState)
        assertEquals(true, patched.solid)
        assertTrue(outboxSnapshot().isEmpty())
    }

    @Test
    fun `edit of a never-synced create re-encodes the CREATE op (FAILED-entry fix path)`() = runTest {
        val created = repository.create(changeDraft()) // stays offline (no drain)
        repository.edit(created.localUuid, changeDraft(solid = true))

        val ops = outboxSnapshot()
        assertEquals(1, ops.size) // CREATE replaced, not duplicated
        assertEquals("CREATE", ops.single().opType)
        val entity = db.eventDao().byLocalUuid(created.localUuid)!!
        assertEquals(true, entity.solid)
        assertEquals(SyncState.PENDING, SyncState.valueOf(entity.syncState))
    }

    @Test
    fun `delete of a synced entry removes locally then deletes on server`() = runTest {
        val created = repository.create(changeDraft())
        responses.add(createdChange201(948))
        drainer().drain()

        repository.delete(created.localUuid)
        assertTrue(allEvents().isEmpty()) // local-first removal
        assertEquals(1, outboxSnapshot().size)

        responses.add(MockResponse().setResponseCode(204))
        drainer().drain()
        assertEquals(listOf("POST /api/changes/", "DELETE /api/changes/948/"), seenRequests)
        assertTrue(outboxSnapshot().isEmpty())
    }

    @Test
    fun `delete of a never-synced entry is local-only`() = runTest {
        val created = repository.create(changeDraft())
        repository.delete(created.localUuid)
        assertTrue(allEvents().isEmpty())
        assertTrue(outboxSnapshot().isEmpty())
        assertTrue(seenRequests.isEmpty())
    }

    @Test
    fun `404 on delete treats the entry as already gone`() = runTest {
        val created = repository.create(changeDraft())
        responses.add(createdChange201(948))
        drainer().drain()
        repository.delete(created.localUuid)
        responses.add(MockResponse().setResponseCode(404).setBody("""{"detail": "Not found."}"""))
        drainer().drain()
        assertTrue(outboxSnapshot().isEmpty())
        assertTrue(seenRequests.last() == "DELETE /api/changes/948/")
    }
}
