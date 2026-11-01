package app.dandle.ui.dashboard

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
import app.dandle.data.AppContainer
import app.dandle.domain.model.EventType
import app.dandle.widget.WidgetDeepLink
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Path.Companion.toPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * T5 pin: a widget "tap to stop" (EXTRA_STOP_TIMER → [WidgetDeepLink.stopRequest]) runs the
 * EXACT in-app stop semantics on the dashboard VM — >10s running raises the stop-guard request
 * (rendered as the ON-CARD band, framework v2.3 rule 3) and brings the timer's card forward,
 * ≤10s discards silently. Single-VM class on purpose: the deep-link channels are
 * process-global singletons, so the scenario runs as one test on one VM for determinism.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class WidgetStopDeepLinkTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var server: MockWebServer
    private lateinit var container: AppContainer
    private lateinit var viewModel: DashboardViewModel
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val latest = kotlinx.coroutines.flow.MutableStateFlow(DashboardUiState())

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        org.robolectric.shadows.ShadowLog.stream = System.out
        app.dandle.log.BbLog.enabled = true
        WidgetDeepLink.panelRequest.value = null
        WidgetDeepLink.stopRequest.value = null
        val context = ApplicationProvider.getApplicationContext<Context>()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.startsWith("/api/children/") -> MockResponse().setResponseCode(200).setBody(
                    """{"count":1,"next":null,"previous":null,"results":[
                       {"id":1,"first_name":"Milo","last_name":"Rivera","birth_date":"2025-01-01","slug":"milo"}]}"""
                )
                // A server-side app timer started 60s ago — adopted by TimerRepository.refresh
                // (the real reconcile path), giving a long-running timer with NO clock games.
                request.path!!.startsWith("/api/timers") && request.method == "GET" -> {
                    val start = java.time.format.DateTimeFormatter.ISO_INSTANT.format(
                        java.time.Instant.now().minusSeconds(60),
                    )
                    MockResponse().setResponseCode(200).setBody(
                        """{"count":1,"next":null,"previous":null,"results":[
                           {"id":9,"child":1,"name":"Sleep-BBapp:2","start":"$start"}]}"""
                    )
                }
                else -> MockResponse().setResponseCode(200).setBody("""{"count":0,"next":null,"previous":null,"results":[]}""")
            }
        }
        server.start()
        container = AppContainer(
            appScope = appScope,
            sessionFile = context.filesDir.resolve("s5-${System.nanoTime()}.preferences_pb").absolutePath.toPath(),
            quickActionsFile = context.filesDir.resolve("s5q-${System.nanoTime()}.preferences_pb").absolutePath.toPath(),
        )
        runBlocking {
            container.sessionStore.saveLogin(server.url("/").toString().trimEnd('/'), "key", "admin")
            container.sessionStore.setActiveChild("1")
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

    @Test
    fun `widget stop tap raises the guard when long-running and discards silently when fresh`() = runBlocking {
        viewModel.refresh()
        withTimeout(10_000) {
            while (latest.value.activeChild == null) { advance(); delay(50) }
        }

        // -- Scenario 1: long-running timer -> the guard request (what the on-card band renders).
        // Adopted from the server (refresh below) with start = now-60s: genuinely >10s old.
        val childId = container.quickActionActions.activeChildId()!!
        viewModel.refresh() // adopts the server's Sleep-BBapp timer (start = now-60s)
        withTimeout(10_000) {
            while (latest.value.runningTimers.isEmpty()) { advance(); delay(50) }
        }
        // Framework v2.3 rule 3 regression pin: the guard renders as an ON-CARD band, so the
        // guard request must bring the timer's OWN card forward even when a DIFFERENT card
        // holds the panel (the auto-open collector only runs with no panel open — the band
        // used to be lost in that race, QA 2026-10-29).
        viewModel.onQuickActionTap(EventType.FEEDING)
        withTimeout(10_000) {
            while (latest.value.openPanel?.type != EventType.FEEDING) { advance(); delay(50) }
        }
        WidgetDeepLink.requestStop(EventType.SLEEP)
        try {
            withTimeout(10_000) {
                while (viewModel.stopGuardRequests.value == null) { advance(); delay(50) }
            }
        } catch (e: Exception) {
            throw AssertionError(
                "guard never raised; stopReq=${WidgetDeepLink.stopRequest.value} " +
                    "timers=${latest.value.runningTimers.map { it.kind }}",
                e,
            )
        }
        assertEquals(EventType.SLEEP, viewModel.stopGuardRequests.value?.kind)
        withTimeout(10_000) {
            while (latest.value.openPanel?.type != EventType.SLEEP) { advance(); delay(50) }
        }
        assertEquals(
            "the guard request must open the timer's card (the band renders on it)",
            EventType.SLEEP,
            latest.value.openPanel?.type,
        )
        viewModel.consumeStopGuardRequest()
        WidgetDeepLink.consumeStop(EventType.SLEEP)

        // Clean up scenario 1's timers via the repository (the band's Discard semantics).
        val timer = container.timerRepository.findRunning(EventType.SLEEP, childId)
        if (timer != null) container.timerRepository.discard(timer)
        val feeding = container.timerRepository.findRunning(EventType.FEEDING, childId)
        if (feeding != null) container.timerRepository.discard(feeding)
        withTimeout(10_000) {
            while (latest.value.runningTimers.isNotEmpty()) { advance(); delay(50) }
        }

        // -- Scenario 2: fresh timer (<=10s) -> silent discard, no guard dialog.
        container.quickActionActions.startTimer(EventType.SLEEP, childId)
        withTimeout(10_000) {
            while (latest.value.runningTimers.isEmpty()) { advance(); delay(50) }
        }
        WidgetDeepLink.requestStop(EventType.SLEEP)
        withTimeout(10_000) {
            while (latest.value.runningTimers.isNotEmpty()) { advance(); delay(50) }
        }
        advance()
        assertNull("<=10s stop must discard silently (no guard dialog)", viewModel.stopGuardRequests.value)
        assertNull(
            "timer must be gone after the silent discard",
            container.timerRepository.findRunning(EventType.SLEEP, childId),
        )
        WidgetDeepLink.consumeStop(EventType.SLEEP)
    }
}
