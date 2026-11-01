package app.dandle.data.repo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import app.dandle.data.api.BabyBuddyApi
import app.dandle.data.sync.OutboxDrainer
import app.dandle.data.db.BabyBuddyDatabase
import app.dandle.data.prefs.QuickActionStore
import app.dandle.data.prefs.SessionStore
import app.dandle.domain.Clock
import app.dandle.domain.ServerClock
import app.dandle.domain.model.EventType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockWebServer
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
import okio.Path.Companion.toPath
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * The widget facade (Stage 4): trampolines + Glance actions reuse exactly the in-app semantics
 * through QuickActionActions — child resolution, timer start/discard, stop-guard decision, and
 * last-event lookups — all off Room/DataStore snapshots.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class QuickActionActionsTest {

    private lateinit var db: BabyBuddyDatabase
    private lateinit var server: MockWebServer
    private lateinit var api: BabyBuddyApi
    private lateinit var sessionStore: SessionStore
    private lateinit var quickActions: QuickActionStore
    private lateinit var childRepo: ChildRepository
    private lateinit var timerRepo: TimerRepository
    private lateinit var eventRepo: EventRepository
    private lateinit var facade: QuickActionActions

    private val now = 1_760_000_000_000L
    private val clock = Clock { now }
    private val responses = ArrayDeque<MockResponse>()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, BabyBuddyDatabase::class.java).build()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse =
                responses.removeFirstOrNull() ?: MockResponse().setResponseCode(500)
        }
        server.start()
        api = Retrofit.Builder()
            .baseUrl(server.url("/api/"))
            .addConverterFactory(
                Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }
                    .asConverterFactory("application/json".toMediaType())
            )
            .build()
            .create(BabyBuddyApi::class.java)
        sessionStore = SessionStore(
            context.filesDir.resolve("test-session-${System.nanoTime()}.preferences_pb").absolutePath.toPath()
        )
        quickActions = QuickActionStore(
            context.filesDir.resolve("test-qa-${System.nanoTime()}.preferences_pb").absolutePath.toPath()
        )
        childRepo = ChildRepository(app.dandle.data.backend.BabyBuddyPuller({ api }), db)
        val serverClock = ServerClock(clock)
        eventRepo = EventRepository(
            puller = app.dandle.data.backend.BabyBuddyPuller({ api }),
            db = db,
            json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false },
            clock = clock,
            drainer = OutboxDrainer(
                pusher = app.dandle.data.backend.BabyBuddySyncPusher(
                    { api },
                    Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false },
                ),
                json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false },
                eventDao = db.eventDao(),
                outboxDao = db.outboxDao(),
                clock = clock,
                isAuthPaused = { false },
                onAuthExpired = {},
            ),
            requestDrain = { },
        )
        timerRepo = TimerRepository(timerMirrorProvider = { app.dandle.data.backend.BabyBuddyTimerMirror({ api }) }, db = db, eventRepository = eventRepo, clock = clock, serverClock = serverClock)
        facade = QuickActionActions(timerRepo, eventRepo, quickActions, childRepo, sessionStore, serverClock)
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    private fun seedChild(id: String, name: String) {
        runTest {
            db.childDao().upsertAll(
                listOf(
                    app.dandle.data.db.ChildEntity(
                        id = id, firstName = name, lastName = null,
                        birthDate = "2025-01-01", birthTime = null, slug = name.lowercase(),
                    )
                )
            )
        }
    }

    @Test
    fun `active child comes from the session and falls back to the first child`() = runTest {
        seedChild("1", "Milo")
        seedChild("2", "Zoe")
        // No persisted selection → first child (spec §2 default).
        assertEquals("1", facade.activeChildId())
        // Persisted selection wins.
        sessionStore.setActiveChild("2")
        assertEquals("2", facade.activeChildId())
    }

    @Test
    fun `start is idempotent per kind and child - widget re-taps never duplicate`() = runTest {
        seedChild("1", "Milo")
        responses += MockResponse().setResponseCode(201).setBody("""{"id":3,"child":1,"name":"Sleep-BBapp:2"}""")
        val first = facade.startTimer(EventType.SLEEP, childId = "1")
        val second = facade.startTimer(EventType.SLEEP, childId = "1")
        assertEquals(first.localId, second.localId)
        assertEquals(first, facade.runningTimer(EventType.SLEEP, "1"))
        assertEquals(1, db.timerDao().snapshot().size)
    }

    @Test
    fun `stop guard decision matches the in-app threshold`() = runTest {
        seedChild("1", "Milo")
        val timer = facade.startTimer(EventType.SLEEP, childId = "1")
        // Timer just started (now == start): under the 10s guard.
        assertFalse(facade.stopGuardNeeded(timer))
        // A timer started >10s ago needs the guard in-app; the widget opens the app instead.
        val old = timer.copy(startedAtMillis = now - 11_000)
        assertTrue(facade.stopGuardNeeded(old))
    }

    @Test
    fun `discard removes the running timer`() = runTest {
        seedChild("1", "Milo")
        val timer = facade.startTimer(EventType.SLEEP, childId = "1")
        facade.discardTimer(timer)
        assertNull(facade.runningTimer(EventType.SLEEP, "1"))
    }

    @Test
    fun `latest event lookup is per type and child`() = runTest {
        seedChild("1", "Milo")
        responses += MockResponse().setResponseCode(200).setBody(
            """{"count":1,"next":null,"previous":null,"results":[
                {"id":5,"child":1,"time":"2025-10-09T05:00:00Z","wet":true,"solid":false,"color":"brown"}
            ]}""",
        )
        db.eventDao().upsertAll(listOf(ChangeDtoForTest()))
        val change = facade.latestEvent("1", EventType.CHANGE)
        assertNotNull(change)
        assertEquals(true, change!!.wet)
        assertNull(facade.latestEvent("1", EventType.FEEDING))
        assertNull(facade.latestEvent("2", EventType.CHANGE))
    }

    /** Change row shaped like the mapper's output (keeps the test free of mapper helpers). */
    private fun ChangeDtoForTest() = app.dandle.data.db.EventEntity(
        localUuid = "server:CHANGE:5",
        serverId = "5",
        type = EventType.CHANGE.name,
        childId = "1",
        startMillis = now,
        endMillis = now,
        feedingType = null, feedingMethod = null, amount = null,
        wet = true, solid = false, color = "brown",
        noteText = null, milestone = null,
        syncState = app.dandle.domain.model.SyncState.SYNCED.name,
        failureReason = null, conflictUiPath = null, conflictServerId = null,
        createdAtMillis = 0, updatedAtMillis = 0,
    )
}
