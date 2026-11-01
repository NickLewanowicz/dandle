package app.dandle.ui.dashboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.runBlocking
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * P1 regression (QA 2026-10-19, found by the demo-widget capture): with the app COLD, a
 * widget "tap to stop" deep link delivered during DashboardViewModel construction crashed
 * with an NPE — viewModelScope runs on Main.immediate, so the pending stopRequest collected
 * SYNCHRONOUSLY during init and read `uiState` before the property (declared below the
 * deep-link init block) was constructed. The uncaught NPE killed the collector, so the tap
 * was dropped on the floor (and on device, the process died). The fix gates deep-link
 * handling on `uiStateConstructed`, completed by an init block after the uiState property.
 *
 * UnconfinedTestDispatcher replicates Main.immediate's synchronous-start semantics — the
 * StandardTestDispatcher used by the other dashboard tests queues init collectors and
 * cannot reproduce this. RED proof: with the gate removed, the collector dies on the NPE
 * and the request is never consumed, failing the consumption assertions below.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DashboardDeepLinkColdStartTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var server: MockWebServer
    private lateinit var container: AppContainer
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before
    fun setUp() {
        // Eager (Main.immediate-like) start: init collectors run synchronously mid-init,
        // exactly when the production crash happened.
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        server = MockWebServer()
        // Deliberately NO children: activeChild never resolves, so deep-link handling takes
        // the documented bounded-wait path (awaitDashboardReady null after 10 s → consume as
        // a no-op) — deterministic in a unit test, still proves the collector SURVIVED init.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(200).setBody(
                    """{"count":0,"next":null,"previous":null,"results":[]}"""
                )
        }
        server.start()
        container = AppContainer(
            appScope = appScope,
            sessionFile = context.filesDir.resolve("dl-${System.nanoTime()}.preferences_pb").absolutePath.toPath(),
            quickActionsFile = context.filesDir.resolve("dlq-${System.nanoTime()}.preferences_pb").absolutePath.toPath(),
        )
        runBlocking {
            container.sessionStore.saveLogin(server.url("/").toString().trimEnd('/'), "key", "admin")
            container.sessionStore.setActiveChild("1")
        }
        container.initDatabase(context)
        container.start()
        // The container's mirror collector runs on Dispatchers.IO — give it a real beat so
        // the BaseUrlInterceptor routes the children fetch to the MockWebServer (same as
        // DashboardTimelineLiveUpdateTest).
        Thread.sleep(300)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        appScope.cancel()
        server.shutdown()
    }

    /**
     * The request must be consumed once the dashboard is ready — plain polled deadline, no
     * coroutine timeouts (they interleave badly with the eager init collectors).
     */
    private fun awaitConsumed(expected: EventType) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            if (WidgetDeepLink.stopRequest.value != expected &&
                WidgetDeepLink.panelRequest.value != expected
            ) return
            // awaitDashboardReady's 10 s bound runs on the TEST scheduler's virtual time —
            // advance it so the bounded-wait no-op path can fire.
            testDispatcher.scheduler.advanceTimeBy(1_000)
            testDispatcher.scheduler.runCurrent()
            Thread.sleep(20)
        }
    }

    @Test
    fun `pending widget stop deep link during construction is handled, not dropped`() {
        WidgetDeepLink.requestStop(EventType.SLEEP)

        // Without the gate this constructor path NPEs inside init (the collector dies and
        // the request is never consumed, failing the wait below — the red proof).
        val vm = DashboardViewModel(container, ApplicationProvider.getApplicationContext())

        awaitConsumed(EventType.SLEEP)
        assertEquals(null, WidgetDeepLink.stopRequest.value)
    }

    @Test
    fun `pending widget panel deep link during construction is handled, not dropped`() {
        WidgetDeepLink.requestPanel(EventType.NOTE)

        val vm = DashboardViewModel(container, ApplicationProvider.getApplicationContext())

        awaitConsumed(EventType.NOTE)
        assertEquals(null, WidgetDeepLink.panelRequest.value)
    }
}
