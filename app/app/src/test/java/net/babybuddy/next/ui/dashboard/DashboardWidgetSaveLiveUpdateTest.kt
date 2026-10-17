package net.babybuddy.next.ui.dashboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import net.babybuddy.next.data.AppContainer
import net.babybuddy.next.domain.model.EventType
import net.babybuddy.next.domain.model.SyncState
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Path.Companion.toPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * T1 pin (the one that was missing): saves that do NOT go through the dashboard screen's own
 * Save button must still land in the live DashboardViewModel pipeline immediately:
 *
 *  1. the WIDGET timer path — [net.babybuddy.next.data.repo.QuickActionActions].startTimer (the
 *     home-screen Sleep trampoline) followed by TimerRepository.stopAndSave (what the app's
 *     stop-guard Save runs) — Sam's "nap from the home screen" scenario;
 *  2. the OFFLINE variant — the same save with the server unreachable must show the row as
 *     PENDING immediately (Jordan's airplane-mode scenario);
 *  3. the write-boundary reconciliation nudge — EventRepository.timelineWrites re-queries the
 *     timeline even when Room's flow signal does not fire (the device-only freeze class).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DashboardWidgetSaveLiveUpdateTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var server: MockWebServer
    private lateinit var container: AppContainer
    private lateinit var viewModel: DashboardViewModel
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val latest = kotlinx.coroutines.flow.MutableStateFlow(DashboardUiState())

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.startsWith("/api/children/") -> MockResponse().setResponseCode(200).setBody(
                    """{"count":1,"next":null,"previous":null,"results":[
                       {"id":1,"first_name":"Milo","last_name":"Rivera","birth_date":"2025-01-01","slug":"milo"}]}"""
                )
                request.path!!.startsWith("/api/timers") && request.method == "POST" ->
                    MockResponse().setResponseCode(201).setBody("""{"id":5,"child":1,"name":"Sleep-BBapp:1"}""")
                request.path!!.startsWith("/api/sleep") && request.method == "POST" ->
                    MockResponse().setResponseCode(201).setBody(
                        """{"id":31,"child":1,"start":"2026-10-17T15:00:00Z","end":"2026-10-17T15:20:00Z","duration":"00:20:00","nap":true}"""
                    )
                else -> MockResponse().setResponseCode(200).setBody("""{"count":0,"next":null,"previous":null,"results":[]}""")
            }
        }
        server.start()
        container = AppContainer(
            appScope = appScope,
            sessionFile = context.filesDir.resolve("w-${System.nanoTime()}.preferences_pb").absolutePath.toPath(),
            quickActionsFile = context.filesDir.resolve("wq-${System.nanoTime()}.preferences_pb").absolutePath.toPath(),
        )
        runBlocking {
            container.sessionStore.saveLogin(server.url("/").toString().trimEnd('/'), "key", "admin")
            container.sessionStore.setActiveChild(1)
        }
        container.initDatabase(context)
        container.start()
        Thread.sleep(300)
        viewModel = DashboardViewModel(container, context)
        appScope.launch { viewModel.uiState.collect { latest.value = it } }
        testDispatcher.scheduler.advanceUntilIdle()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        appScope.cancel()
        server.shutdown()
    }

    private fun advance() = testDispatcher.scheduler.advanceUntilIdle()

    private suspend fun awaitChildLoaded() {
        viewModel.refresh()
        withTimeout(10_000) {
            while (latest.value.activeChild == null) { advance(); delay(50) }
        }
    }

    private suspend fun awaitTimelineRow(type: EventType, what: String) {
        withTimeout(10_000) {
            while (latest.value.timeline.firstOrNull()?.type != type) { advance(); delay(50) }
        }
        assertEquals(what, type, latest.value.timeline.first().type)
    }

    @Test
    fun `widget-started timer saved while app is open appears in the timeline immediately`() = runBlocking {
        awaitChildLoaded()

        // The exact widget path: QuickActionsWidget's Sleep cell -> QuickActionActions.startTimer.
        val actions = container.quickActionActions
        val childId = actions.activeChildId()
        assertNotNull("active child resolved for the widget", childId)
        val timer = actions.startTimer(EventType.SLEEP, childId!!)

        // ... the user later taps Save (stop-guard dialog / strip card semantics).
        val draft = net.babybuddy.next.domain.model.EventDraft(
            type = EventType.SLEEP,
            childId = childId,
            startMillis = timer.startedAtMillis,
            endMillis = timer.startedAtMillis + 60_000,
        )
        container.timerRepository.stopAndSave(timer, draft)

        awaitTimelineRow(EventType.SLEEP, "widget-timer save must appear without refresh")
    }

    @Test
    fun `offline save shows the row as PENDING immediately`() = runBlocking {
        awaitChildLoaded()

        // Server unreachable (airplane mode): shutdown the mock server -> all writes queue.
        server.shutdown()

        val draft = net.babybuddy.next.domain.model.EventDraft(
            type = EventType.CHANGE,
            childId = 1,
            startMillis = System.currentTimeMillis(),
            endMillis = System.currentTimeMillis(),
            wet = true,
            solid = false,
        )
        container.eventRepository.create(draft)

        withTimeout(10_000) {
            while (latest.value.timeline.firstOrNull()?.type != EventType.CHANGE) { advance(); delay(50) }
        }
        val row = latest.value.timeline.first()
        assertEquals("offline row must be PENDING from the first render", SyncState.PENDING, row.syncState)
        assertTrue("offline row must have no server id yet", row.serverId == null)
    }

    @Test
    fun `timeline reconciliation nudge re-queries after a write`() = runBlocking {
        awaitChildLoaded()
        advance()

        val draft = net.babybuddy.next.domain.model.EventDraft(
            type = EventType.NOTE,
            childId = 1,
            startMillis = System.currentTimeMillis(),
            endMillis = System.currentTimeMillis(),
            noteText = "nudge pin",
        )
        container.eventRepository.create(draft)
        advance()
        // (The create itself nudges; pin the nudge contract directly:)
        container.eventRepository.nudgeTimeline()
        withTimeout(10_000) {
            while (latest.value.timeline.firstOrNull()?.type != EventType.NOTE) { advance(); delay(50) }
        }
        assertEquals(EventType.NOTE, latest.value.timeline.first().type)
    }
}
