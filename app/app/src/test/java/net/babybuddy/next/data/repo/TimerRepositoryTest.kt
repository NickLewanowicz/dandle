package net.babybuddy.next.data.repo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import net.babybuddy.next.data.api.BabyBuddyApi
import net.babybuddy.next.data.db.BabyBuddyDatabase
import net.babybuddy.next.domain.Clock
import net.babybuddy.next.domain.ElapsedTimeFormatter
import net.babybuddy.next.domain.Iso8601
import net.babybuddy.next.domain.ServerClock
import net.babybuddy.next.domain.model.EventDraft
import net.babybuddy.next.domain.model.EventType
import net.babybuddy.next.domain.model.SyncState
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Timer semantics (spec §3) on Robolectric + in-memory Room + MockWebServer: local rows are the
 * source of truth (offline-safe), saves/duplicates behave exactly once, and server "-BBapp:"
 * timers are adopted back after process death / relaunch.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class TimerRepositoryTest {

    private lateinit var db: BabyBuddyDatabase
    private lateinit var server: MockWebServer
    private lateinit var api: BabyBuddyApi
    private lateinit var eventRepo: EventRepository
    private lateinit var timerRepo: TimerRepository

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }

    // Wall clock and server clock are frozen apart by a fixed skew so we can prove which one
    // stamps what (entries use the SERVER clock, matching the original app's discipline).
    // wallNow is a var so restart tests can advance it between calls ("process death").
    private var wallNow = 1_760_000_000_000L
    private val serverNow = wallNow + 90_000L
    private val clock = Clock { wallNow }
    private val serverClock = ServerClock(clock).apply { restoreOffset(serverNow - wallNow) }

    private val responses = ArrayDeque<MockResponse>()
    private val seenRequests = mutableListOf<String>()

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

        val serverClockLocal = serverClock
        eventRepo = EventRepository(
            apiProvider = { api },
            db = db,
            json = json,
            clock = clock,
            drainer = net.babybuddy.next.data.sync.OutboxDrainer(
                apiProvider = { api },
                json = json,
                eventDao = db.eventDao(),
                outboxDao = db.outboxDao(),
                clock = clock,
                isAuthPaused = { false },
                onAuthExpired = {},
            ),
            requestDrain = { },
        )
        timerRepo = TimerRepository(
            apiProvider = { api },
            db = db,
            eventRepository = eventRepo,
            clock = clock,
            serverClock = serverClockLocal,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    private fun timerCreatedResponse(id: Long, startIso: String) = MockResponse()
        .setResponseCode(201)
        .setBody("""{"id":$id,"child":1,"name":"Sleep-BBapp:2","start":"$startIso"}""")

    @Test
    fun `start is idempotent per child and kind - re-tap focuses instead of duplicating`() = runTest {
        val first = timerRepo.start(EventType.SLEEP, childId = 1)
        val second = timerRepo.start(EventType.SLEEP, childId = 1)
        assertEquals(first.localId, second.localId)
        assertEquals(first.startedAtMillis, second.startedAtMillis)
        assertEquals(1, db.timerDao().snapshot().size)
    }

    @Test
    fun `same kind for a different child is a separate timer`() = runTest {
        timerRepo.start(EventType.SLEEP, childId = 1)
        timerRepo.start(EventType.SLEEP, childId = 2)
        assertEquals(2, db.timerDao().snapshot().size)
    }

    @Test
    fun `offline start still runs the timer locally`() = runTest {
        // No scripted 201 → the createTimer call 500s; the timer must survive.
        val timer = timerRepo.start(EventType.SLEEP, childId = 1)
        assertNull(timer.serverTimerId)
        assertNotNull(timerRepo.findRunning(EventType.SLEEP, 1))
    }

    @Test
    fun `stopAndSave stamps start from the timer and end from the server clock`() = runTest {
        responses += timerCreatedResponse(11, "2025-10-09T03:31:30Z")
        val timer = timerRepo.start(EventType.SLEEP, childId = 1)
        responses += MockResponse().setResponseCode(204) // timer delete on save

        timerRepo.stopAndSave(
            timer,
            EventDraft(
                type = EventType.SLEEP,
                childId = 1,
                startMillis = 0, // overridden by the timer's start
                endMillis = 0,   // overridden by the server's now
                noteText = "night sleep",
            ),
        )

        val events = db.eventDao().observeTimeline(1).first()
        assertEquals(1, events.size)
        val saved = events.single()
        assertEquals(timer.startedAtMillis, saved.startMillis)
        assertEquals(serverNow, saved.endMillis)
        assertEquals(SyncState.PENDING.name, saved.syncState)
        // Timer row is gone after save.
        assertNull(timerRepo.findRunning(EventType.SLEEP, 1))
        // The server timer delete was attempted as part of save.
        assertTrue(seenRequests.any { it == "DELETE /api/timers/11/" })
    }

    @Test
    fun `panel started-ago adjustment backdates only the entry start (T2)`() = runTest {
        responses += timerCreatedResponse(12, "2025-10-09T03:31:30Z")
        val timer = timerRepo.start(EventType.SLEEP, childId = 1)
        responses += MockResponse().setResponseCode(204) // timer delete on save

        val adjust = 20 * 60_000L
        timerRepo.stopAndSave(
            timer,
            EventDraft(
                type = EventType.SLEEP,
                childId = 1,
                startMillis = 0, // overridden: timer start minus the panel adjustment
                endMillis = 0,   // overridden by the server's now
                noteText = "nap that started early",
                startAdjustMillis = adjust,
            ),
        )

        val saved = db.eventDao().observeTimeline(1).first().single()
        assertEquals("start = timer start backdated by the adjust", timer.startedAtMillis - adjust, saved.startMillis)
        assertEquals("end stays the stop time (duration rules unchanged)", serverNow, saved.endMillis)
        assertEquals(serverNow - saved.startMillis, saved.endMillis - saved.startMillis)
        assertNull(timerRepo.findRunning(EventType.SLEEP, 1))
    }

    @Test
    fun `discard removes the timer without logging anything`() = runTest {
        val timer = timerRepo.start(EventType.SLEEP, childId = 1)
        timerRepo.discard(timer)
        assertNull(timerRepo.findRunning(EventType.SLEEP, 1))
        assertEquals(0, db.eventDao().observeTimeline(1).first().size)
    }

    @Test
    fun `restore - server timers we track are not duplicated on refresh`() = runTest {
        responses += timerCreatedResponse(21, "2025-10-09T04:00:00Z")
        val timer = timerRepo.start(EventType.SLEEP, childId = 1)
        timerRepo.refresh(childId = 1) // server still lists timer 21
        assertEquals(1, db.timerDao().snapshot().size)
        assertEquals(timer.localId, timerRepo.findRunning(EventType.SLEEP, 1)!!.localId)
    }

    @Test
    fun `restore - untracked server app timers are adopted after process death`() = runTest {
        // Simulates a timer created on another device / before a reinstall: the local DB is
        // empty, the server still runs "Sleep-BBapp:2".
        db.timerDao().clear()
        responses += MockResponse().setResponseCode(200).setBody(
            """{"count":1,"next":null,"previous":null,"results":[
                {"id":31,"child":1,"name":"Sleep-BBapp:2","start":"2025-10-09T04:10:00Z"}
            ]}""",
        )
        timerRepo.refresh(childId = 1)
        val adopted = timerRepo.findRunning(EventType.SLEEP, 1)
        assertNotNull(adopted)
        assertEquals(31L, adopted!!.serverTimerId)
        assertEquals(
            net.babybuddy.next.domain.Iso8601.parse("2025-10-09T04:10:00Z"),
            adopted.startedAtMillis,
        )
    }

    @Test
    fun `restore - foreign timers without app names are ignored`() = runTest {
        responses += MockResponse().setResponseCode(200).setBody(
            """{"count":1,"next":null,"previous":null,"results":[
                {"id":41,"child":1,"name":"Manual timer","start":"2025-10-09T04:20:00Z"}
            ]}""",
        )
        timerRepo.refresh(childId = 1)
        assertNull(timerRepo.findRunning(EventType.SLEEP, 1))
        assertEquals(0, db.timerDao().snapshot().size)
    }

    // -- Process death: the Room row is the timer -----------------------------------

    /** A brand-new repository stack over the same Room instance (fresh "process"). */
    private fun restartedTimerRepo() = TimerRepository(
        apiProvider = { api },
        db = db,
        eventRepository = eventRepo,
        clock = Clock { wallNow },
        serverClock = serverClock,
    )

    @Test
    fun `timer started offline survives a restart and elapsed comes from the persisted start`() = runTest {
        // Offline start: no scripted 201 → local row only, no server timer.
        val started = timerRepo.start(EventType.SLEEP, childId = 1)
        assertNull(started.serverTimerId)

        // 12m34s later the process is dead and restarted: a fresh repository reads Room.
        wallNow += 12 * 60_000L + 34_000L
        val restarted = restartedTimerRepo()
        val restored = restarted.findRunning(EventType.SLEEP, childId = 1)!!
        assertEquals("start survives verbatim", started.startedAtMillis, restored.startedAtMillis)

        // The dashboard's live-counter math (serverClock.nowMillis() - startedAtMillis):
        // the skew cancels because both stamps carry the same +90s server offset.
        val elapsed = serverClock.nowMillis() - restored.startedAtMillis
        assertEquals(12 * 60_000L + 34_000L, elapsed)
        assertEquals("00:12:34", ElapsedTimeFormatter.timerCounter(elapsed))

        // Stopping after the restart still saves the ORIGINAL start with the fresh end.
        restarted.stopAndSave(
            restored,
            EventDraft(type = EventType.SLEEP, childId = 1, startMillis = 0, endMillis = 0),
        )
        val saved = db.eventDao().observeTimeline(1).first().single()
        assertEquals(started.startedAtMillis, saved.startMillis)
        assertEquals(wallNow + 90_000L, saved.endMillis)
        assertEquals(12 * 60_000L + 34_000L, saved.endMillis - saved.startMillis)
        assertNull(restarted.findRunning(EventType.SLEEP, childId = 1))
    }

    @Test
    fun `stop just after midnight keeps duration from the persisted start - pure epoch math`() = runTest {
        // Start at 23:55:00Z server time, stop at 00:07:00Z the next UTC day: no local-field
        // day arithmetic may leak into the duration.
        wallNow = Iso8601.parse("2025-10-09T23:55:00Z") - 90_000L
        val started = timerRepo.start(EventType.SLEEP, childId = 1)
        wallNow = Iso8601.parse("2025-10-10T00:07:00Z") - 90_000L

        restartedTimerRepo().stopAndSave(
            started,
            EventDraft(type = EventType.SLEEP, childId = 1, startMillis = 0, endMillis = 0),
        )

        val saved = db.eventDao().observeTimeline(1).first().single()
        assertEquals(Iso8601.parse("2025-10-09T23:55:00Z"), saved.startMillis)
        assertEquals(Iso8601.parse("2025-10-10T00:07:00Z"), saved.endMillis)
        assertEquals(12 * 60_000L, saved.endMillis - saved.startMillis)
    }
}
