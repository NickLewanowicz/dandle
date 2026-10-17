package net.babybuddy.next.ui.dashboard

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import net.babybuddy.next.data.AppContainer
import net.babybuddy.next.data.mapper.serverLocalUuid
import net.babybuddy.next.data.prefs.SessionStore
import net.babybuddy.next.data.sync.OutboxDrainer
import net.babybuddy.next.domain.model.EventType
import net.babybuddy.next.domain.model.SyncState
import net.babybuddy.next.domain.model.toDraft
import net.babybuddy.next.ui.dashboard.FailureDialogState
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Path.Companion.toPath
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
 * P1-1 regression: a quick-action save must reach `uiState.timeline` immediately (spec §3, UX §3
 * "the animation is the confirmation") — the pipeline (Room flow -> uiState combine) must emit on
 * insert and on window growth, with no stuck side-state.
 * P1-3 regression: a server-rejected write surfaces proactively as the failure dialog state
 * (spec §3.8/§6.2) — Edit/Retry/Delete-locally reachable without hunting for a badge.
 *
 * Drives the REAL DashboardViewModel over a REAL AppContainer: in-memory Room, temp DataStores,
 * MockWebServer as the server (the session points at it so BaseUrlInterceptor routes there).
 * Dispatchers.setMain makes viewModelScope's init collectors + saves deterministic.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DashboardTimelineLiveUpdateTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var server: MockWebServer
    private lateinit var container: AppContainer
    private lateinit var viewModel: DashboardViewModel
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Persistent uiState subscriber — stateIn is WhileSubscribed, the pipeline needs a collector. */
    private val latest = kotlinx.coroutines.flow.MutableStateFlow(DashboardUiState())

    private fun trace(msg: String) {
        java.io.File("/tmp/bb_t.log").appendText(msg + "\n")
    }

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }

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
                request.path!!.startsWith("/api/sleep") && request.method == "GET" -> MockResponse().setResponseCode(200).setBody(
                    """{"count":1,"next":null,"previous":null,"results":[
                       {"id":6,"child":1,"start":"2026-10-09T05:00:00Z","end":"2026-10-09T05:10:00Z","notes":"QA night nap"}]}"""
                )
                request.path!!.startsWith("/api/feedings") && request.method == "GET" -> {
                    val rows = (10..49).joinToString(",") { id ->
                        """{"id":$id,"child":1,"start":"2026-10-09T05:00:00Z","end":"2026-10-09T05:10:00Z",
                           "type":"breast milk","method":"bottle","amount":110.0}"""
                    }
                    MockResponse().setResponseCode(200).setBody(
                        """{"count":40,"next":null,"previous":null,"results":[$rows]}"""
                    )
                }
                request.method == "PATCH" && request.path!!.startsWith("/api/sleep") ->
                    MockResponse().setResponseCode(400).setBody(
                        """{"non_field_errors":["Another entry intersects the specified time period. """ +
                            """Conflicting entry: <a href=\"/sleep/6/\">Sleep (10/09/26 05:00 - 05:10)</a>"]}"""
                    )
                request.path!!.startsWith("/api/changes/") && request.method == "POST" ->
                    MockResponse().setResponseCode(201).setBody(
                        """{"id":40,"child":1,"time":"2026-10-09T06:00:00Z","wet":true,"solid":false}"""
                    )
                else -> MockResponse().setResponseCode(200).setBody("""{"count":0,"next":null,"previous":null,"results":[]}""")
            }
        }
        server.start()

        val context2 = context
        container = AppContainer(
            appScope = appScope,
            sessionFile = context2.filesDir.resolve("s-${System.nanoTime()}.preferences_pb").absolutePath.toPath(),
            quickActionsFile = context2.filesDir.resolve("q-${System.nanoTime()}.preferences_pb").absolutePath.toPath(),
        )
        // Login persists the MockWebServer URL; the container's mirror collector picks it up
        // in start() below, routing every API call to the MockWebServer.
        runBlocking {
            container.sessionStore.saveLogin(server.url("/").toString().trimEnd('/'), "key", "admin")
            container.sessionStore.setActiveChild(1)
        }
        container.initDatabase(context)
        container.start()
        // The container's mirror collector runs on Dispatchers.IO — give it a real beat so the
        // BaseUrlInterceptor routes the first refresh to the MockWebServer.
        Thread.sleep(300)
        viewModel = DashboardViewModel(container, context)
        appScope.launch { viewModel.uiState.collect { latest.value = it } }

        // Drain the VM's init collectors + first refresh (Main is the test dispatcher).
        testDispatcher.scheduler.advanceUntilIdle()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        appScope.cancel()
        server.shutdown()
    }

    private fun advance() = testDispatcher.scheduler.advanceUntilIdle()

    @Deprecated("replaced by latest flow")
    private suspend fun awaitTimeline(pred: (Int) -> Boolean, what: String): Int =
        withTimeout(10_000) {
            var size = -1
            while (pred(size).not()) {
                advance()
                size = latest.value.timeline.size
                if (pred(size)) break
                delay(50)
            }
            size
        }.also { assertTrue("timeline $what (last size=$it)", pred(it)) }

    @Test
    fun `server row is fetched and a quick-action save appears immediately`() = runBlocking {
        try {
            doTestSaveAppears()
        } catch (e: Exception) {
            println("DEBUG state: children=" + container.childRepository.children.first().size +
                " timeline=" + latest.value.timeline.size +
                " active=" + latest.value.activeChild +
                " requests=" + server.requestCount +
                " err=" + e)
            throw e
        }
    }

    private fun doTestSaveAppears() = runBlocking {
        // Initial refresh pulls the seeded server row (children + feedings).
        viewModel.refresh()
        advance()
        withTimeout(10_000) {
            while (latest.value.timeline.isEmpty()) {
                advance()
                delay(50)
            }
        }
        val before = latest.value.timeline.size
        assertTrue(before >= 1)

        // The exact quick-action save path (diaper: wet on, save) — waiting at each async step.
        viewModel.onQuickActionTap(EventType.CHANGE)
        withTimeout(10_000) {
            while (latest.value.openPanel?.type != EventType.CHANGE) { advance(); delay(50) }
        }
        viewModel.onWetToggle()
        withTimeout(10_000) {
            while (latest.value.openPanel?.draft?.wet != true) { advance(); delay(50) }
        }
        viewModel.savePanel()
        advance()

        withTimeout(10_000) {
            while (latest.value.timeline.firstOrNull()?.type != EventType.CHANGE) {
                advance()
                delay(50)
            }
        }
        val first = latest.value.timeline.first()
        assertEquals(EventType.CHANGE, first.type)
        assertEquals(SyncState.PENDING, first.syncState) // drained after this assert point
    }

    @Test
    fun `window growth re-emits the timeline (paged older-load reveals rows)`() = runBlocking {
        viewModel.refresh()
        advance()
        withTimeout(10_000) {
            while (latest.value.timeline.isEmpty()) { advance(); delay(50) }
        }
        viewModel.loadOlder() // window branch: 30 < 40 local rows
        withTimeout(10_000) {
            while (latest.value.timeline.size < 40) { advance(); delay(50) }
        }
        assertTrue(latest.value.timeline.size >= 40)
    }

    @Test
    fun `a rejected edit surfaces the failure dialog state proactively`() = runBlocking {
        viewModel.refresh()
        advance()
        withTimeout(10_000) { while (latest.value.timeline.none { it.type == EventType.SLEEP }) { advance(); delay(50) } }
        val target = latest.value.timeline.first { it.type == EventType.SLEEP }

        // Quick Edit sheet save (offline here): the EDIT op queues.
        container.eventRepository.edit(
            target.localUuid,
            target.toDraft().copy(startMillis = target.startMillis - 900_000),
        )
        advance()
        assertNull(latest.value.failureDialog) // parked, not yet failed

        // Drain: PATCH 400 -> the row is marked FAILED -> onEntryFailed -> AppContainer ->
        // the VM collector -> the failure dialog appears proactively (P1-3 chain).
        val outcome = container.drainer.drain()
        advance()
        val rowAfterDrain = container.eventRepository.event(target.localUuid)
        assertEquals(
            "drain must mark the rejected edit FAILED (outcome=$outcome)",
            SyncState.FAILED,
            rowAfterDrain?.syncState,
        )

        val dialog = withTimeout(10_000) {
            while (latest.value.failureDialog == null) { advance(); delay(50) }
            latest.value.failureDialog
        }
        assertNotNull(dialog)
        assertEquals(target.localUuid, dialog!!.event.localUuid)
        assertTrue(
            "parent-language overlap reason, got: " + dialog.reason,
            dialog.reason.contains("overlaps"),
        )
        // Spec §6.2: FAILED is never auto-deleted — the row stays in the local store (render
        // position beyond the paging window is a display concern; scrolling reveals it).
        val rowFinal = container.eventRepository.event(target.localUuid)
        assertEquals(SyncState.FAILED, rowFinal?.syncState)
        assertNotNull(rowFinal?.failureReason)
    }
}
