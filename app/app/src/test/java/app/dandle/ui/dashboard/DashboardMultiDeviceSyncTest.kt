package app.dandle.ui.dashboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import app.dandle.data.AppContainer
import app.dandle.data.mapper.serverLocalUuid
import app.dandle.domain.model.EventType
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Path.Companion.toPath
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Multi-device reconcile (resume/pause): the dashboard must PULL from the server while it is
 * open — on resume and periodically — not only once per composition (root cause A), and the
 * initial pull window must be wide enough that an edit-in-place (which does not move an event
 * up the -end/-time ordering) still converges (root cause B). Test 5 pins the TimerRepository
 * contract the resume path relies on: a local running timer whose server mirror vanished is
 * dropped by a plain refresh.
 *
 * Same harness shape as DashboardTimelineLiveUpdateTest: REAL DashboardViewModel + REAL
 * AppContainer + in-memory Room + temp DataStores + MockWebServer (the session points at it so
 * BaseUrlInterceptor routes there). Dispatchers.setMain makes viewModelScope deterministic.
 * The ViewModel is constructed per test (not in setUp) so test 3 can inject a tiny
 * resumePullPeriodMs; `latest` mirrors uiState for assertions the way the live-update test does.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class DashboardMultiDeviceSyncTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var server: MockWebServer
    private lateinit var container: AppContainer
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Persistent uiState subscriber — stateIn is WhileSubscribed, the pipeline needs a collector. */
    private val latest = kotlinx.coroutines.flow.MutableStateFlow(DashboardUiState())

    /** Mutable server fixtures the tests flip mid-test ("spouse acted on another device"). */
    private val timersBody = AtomicReference("""{"count":0,"next":null,"previous":null,"results":[]}""")
    private val sleepBody = AtomicReference("""{"count":0,"next":null,"previous":null,"results":[]}""")
    private val childrenRequests = AtomicInteger(0)
    private val timersRequests = AtomicInteger(0)
    private val timerDeletes = AtomicInteger(0)

    /** Calm remote-end snackbar notice (DashboardEvent.TimerEndedRemotely), if one was emitted. */
    private val lastNotice = AtomicReference<String?>(null)

    /** Test-owned route override (test 4's 220-row window fixture); null falls through to the default. */
    @Volatile
    private var routeOverride: ((RecordedRequest) -> MockResponse?)? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                routeOverride?.invoke(request)?.let { return it }
                return defaultRoute(request)
            }
        }
        server.start()

        container = AppContainer(
            appScope = appScope,
            sessionFile = context.filesDir.resolve("s-${System.nanoTime()}.preferences_pb").absolutePath.toPath(),
            quickActionsFile = context.filesDir.resolve("q-${System.nanoTime()}.preferences_pb").absolutePath.toPath(),
        )
        // Login persists the MockWebServer URL; the container's mirror collector picks it up
        // in start() below, routing every API call to the MockWebServer. Default backend is
        // BABYBUDDY, so the timer mirror (GET /api/timers/) is live.
        runBlocking {
            container.sessionStore.saveLogin(server.url("/").toString().trimEnd('/'), "key", "admin")
            container.sessionStore.setActiveChild("1")
        }
        container.initDatabase(context)
        container.start()
        // The container's mirror collector runs on Dispatchers.IO — give it a real beat so the
        // BaseUrlInterceptor routes the first refresh to the MockWebServer.
        Thread.sleep(300)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        appScope.cancel()
        server.shutdown()
        routeOverride = null
    }

    private fun defaultRoute(request: RecordedRequest): MockResponse = when {
        // In-app timer start (mirror create) — returns the created timer so the local row
        // carries a serverTimerId (the precondition for the reconcile drop branch).
        request.path!!.startsWith("/api/timers") && request.method == "POST" ->
            MockResponse().setResponseCode(201).setBody(
                """{"id":42,"child":1,"name":"Sleep-BBapp:2","start":"2026-11-04T18:00:00Z"}""",
            )
        request.path!!.startsWith("/api/timers") && request.method == "DELETE" -> {
            timerDeletes.incrementAndGet()
            MockResponse().setResponseCode(204)
        }
        request.path!!.startsWith("/api/timers") -> {
            timersRequests.incrementAndGet()
            MockResponse().setResponseCode(200).setBody(timersBody.get())
        }
        request.path!!.startsWith("/api/children") -> MockResponse().setResponseCode(200)
            .setBody(
                """{"count":1,"next":null,"previous":null,"results":[
                   {"id":1,"first_name":"Milo","last_name":"Rivera","birth_date":"2025-01-01","slug":"milo"}]}""",
            ).also { childrenRequests.incrementAndGet() }
        request.path!!.startsWith("/api/sleep") && request.method == "GET" ->
            MockResponse().setResponseCode(200).setBody(sleepBody.get())
        else -> MockResponse().setResponseCode(200).setBody("""{"count":0,"next":null,"previous":null,"results":[]}""")
    }

    /** Real VM + real collector per test (test 3 injects a 200ms periodic-pull period). */
    private fun newViewModel(resumePullPeriodMs: Long = 60_000L): DashboardViewModel {
        val vm = DashboardViewModel(container, context, resumePullPeriodMs)
        appScope.launch { vm.uiState.collect { latest.value = it } }
        testDispatcher.scheduler.advanceUntilIdle()
        return vm
    }

    /**
     * Poll until [cond] holds, pumping the virtual scheduler in BOUNDED steps and giving the
     * real network (MockWebServer) beats to land — same pattern as the live-update harness's
     * advance loops. Bounded because a live periodic-pull ticker keeps the scheduler queue
     * non-empty forever: an unbounded advanceUntilIdle() would never terminate once resumed.
     */
    private inline fun awaitTrue(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError(
                    "Timed out: $what (children pulls=${childrenRequests.get()}, " +
                        "timers pulls=${timersRequests.get()}, runningTimers=${latest.value.runningTimers})",
                )
            }
            testDispatcher.scheduler.advanceTimeBy(250)
            testDispatcher.scheduler.runCurrent()
            Thread.sleep(25)
        }
    }

    /** Initial load with the active child resolved: children first, then a second refresh so the
     * timer reconcile sees a non-null child (the first-ever refresh can race the combine). */
    private suspend fun loadDashboardWithServerTimer(vm: DashboardViewModel) {
        timersBody.set(
            """{"count":1,"next":null,"previous":null,"results":[
               {"id":9,"child":1,"name":"Sleep-BBapp:2","start":"2026-11-04T18:00:00Z"}]}""",
        )
        vm.refresh()
        awaitTrue("initial pull resolves the active child") { latest.value.activeChild != null }
        vm.refresh()
        awaitTrue("running sleep timer adopted and visible") {
            latest.value.runningTimers.any { it.kind == EventType.SLEEP && it.childId == "1" }
        }
    }

    @Test
    fun `timer ended on another device disappears on resume reconcile`() = runBlocking {
        val vm = newViewModel()
        loadDashboardWithServerTimer(vm)
        assertTrue(
            "precondition: the running sleep timer is on the strip",
            latest.value.runningTimers.any { it.kind == EventType.SLEEP },
        )

        // Spouse ends it on their device: the server timer is gone, the completed entry exists.
        timersBody.set("""{"count":0,"next":null,"previous":null,"results":[]}""")
        sleepBody.set(
            """{"count":1,"next":null,"previous":null,"results":[
               {"id":777,"child":1,"start":"2026-11-04T18:00:00Z","end":"2026-11-04T20:00:00Z",
                "notes":"ended on another device"}]}""",
        )

        vm.onAppResumed()
        awaitTrue("resume reconcile must drop the vanished server timer") {
            latest.value.runningTimers.none { it.kind == EventType.SLEEP }
        }
        assertTrue(
            "no running timers may remain after the server dropped them: ${latest.value.runningTimers}",
            latest.value.runningTimers.isEmpty(),
        )
    }

    @Test
    fun `one resume cycle resolves a ghost whose completion entry arrives in the same pull`() = runBlocking {
        // QA 0.3.1 regression pin: fetch order is timers → type lists, so a completion entry
        // that arrives in the same pull as the still-listed ghost can only match on the NEXT
        // cycle. The contract: ONE resume must resolve it — timers reconcile against the
        // entry the same cycle's type lists just landed.
        val vm = newViewModel()
        loadDashboardWithServerTimer(vm)
        assertTrue(
            "precondition: the running sleep timer is on the strip",
            latest.value.runningTimers.any { it.kind == EventType.SLEEP },
        )

        // Spouse's stop lands server-side WHILE a pull is about to run: timer still listed +
        // the completed entry arrives in the same type-list response.
        sleepBody.set(
            """{"count":1,"next":null,"previous":null,"results":[
               {"id":777,"child":1,"start":"2026-11-04T18:00:00Z","end":"2026-11-04T20:00:00Z",
                "notes":"ended on another device"}]}""",
        )

        vm.onAppResumed()
        awaitTrue("one cycle must drop the locally running ghost timer") {
            latest.value.runningTimers.none { it.kind == EventType.SLEEP }
        }
        assertTrue(
            "the ghost server timer must be deleted in that cycle too",
            timerDeletes.get() > 0,
        )
    }

    @Test
    fun `resume triggers a server pull`() = runBlocking {
        val vm = newViewModel()
        val before = childrenRequests.get()
        vm.onAppResumed()
        awaitTrue("at least one /api/children/ pull after resume") {
            childrenRequests.get() > before
        }
    }

    @Test
    fun `while resumed the dashboard pulls periodically and pausing stops it`() = runBlocking {
        val vm = newViewModel(resumePullPeriodMs = 200)
        val before = childrenRequests.get()
        vm.onAppResumed()
        awaitTrue("at least 2 periodic children pulls while resumed") {
            childrenRequests.get() >= before + 2
        }

        vm.onAppPaused()
        // Let an in-flight tail pull (if any) land so the baseline is stable: pump until the
        // pull count holds steady across a few bounded pump beats.
        var stableFor = 0
        var lastCount = childrenRequests.get()
        val settleDeadline = System.currentTimeMillis() + 5_000
        while (stableFor < 4 && System.currentTimeMillis() < settleDeadline) {
            testDispatcher.scheduler.advanceTimeBy(250)
            testDispatcher.scheduler.runCurrent()
            Thread.sleep(50)
            val nowCount = childrenRequests.get()
            if (nowCount == lastCount) stableFor++ else {
                lastCount = nowCount
                stableFor = 0
            }
        }
        val afterPause = childrenRequests.get()

        // Watch window: a paused VM must not pull. Bounded steps — if the loop were still
        // ticking (200ms), each step would surface an extra pull and fail the assert below
        // (instead of hanging an unbounded advanceUntilIdle).
        var grew = false
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            testDispatcher.scheduler.advanceTimeBy(250)
            testDispatcher.scheduler.runCurrent()
            Thread.sleep(50)
            if (childrenRequests.get() != afterPause) {
                grew = true
                break
            }
        }
        assertFalse(
            "pausing must stop the periodic pull (grew $afterPause -> ${childrenRequests.get()})",
            grew,
        )
    }

    @Test
    fun `an edit to historical SYNCED events converges even outside the initial pull window`() =
        runBlocking {
            // 220 sleep rows sorted by -end. Newest 50 = ids 171..220 (recent minutes); the old
            // block id<=170 sits at 2026-10-30T08:00Z + (id-160)h, so id 160 lands at index 60 —
            // OUTSIDE the newest-50 initial window but INSIDE the newest-200. An edit-in-place
            // (notes only, same end) never moves it up the ordering.
            val note160 = AtomicReference("ORIGINAL")
            fun endFor(id: Int): Instant = when {
                id >= 171 -> Instant.parse("2026-11-04T18:00:00Z").plusSeconds((id - 171) * 60L)
                else -> Instant.parse("2026-10-30T08:00:00Z").plusSeconds((id - 160) * 3600L)
            }
            fun sleepJson(id: Int): String {
                val end = DateTimeFormatter.ISO_INSTANT.format(endFor(id))
                val start = DateTimeFormatter.ISO_INSTANT.format(endFor(id).minusSeconds(600))
                val notes = if (id == 160) """, "notes":"${note160.get()}"""" else ""
                return """{"id":$id,"child":1,"start":"$start","end":"$end"$notes}"""
            }
            routeOverride = { request ->
                if (request.path!!.startsWith("/api/sleep") && request.method == "GET") {
                    val query = request.path!!.substringAfter('?', "")
                    val limit = Regex("limit=(\\d+)").find(query)?.groupValues?.get(1)?.toInt() ?: 50
                    val offset = Regex("offset=(\\d+)").find(query)?.groupValues?.get(1)?.toInt() ?: 0
                    val from = offset.coerceIn(0, 220)
                    val to = (offset + limit).coerceIn(from, 220)
                    val rows = (220 downTo 1).toList().subList(from, to).joinToString(",") { sleepJson(it) }
                    MockResponse().setResponseCode(200)
                        .setBody("""{"count":220,"next":null,"previous":null,"results":[$rows]}""")
                } else {
                    null // default route: children + empty other types
                }
            }

            val vm = newViewModel()
            vm.refresh()
            awaitTrue("initial pull populates the timeline") { latest.value.timeline.isNotEmpty() }
            testDispatcher.scheduler.advanceUntilIdle()

            val uuid = serverLocalUuid(EventType.SLEEP, "160")
            val initialRow = container.eventRepository.event(uuid)
            assertTrue(
                "precondition: id 160 starts ORIGINAL (absent when the window is 50, " +
                    "ORIGINAL when it is 200) — got ${initialRow?.noteText}",
                initialRow == null || initialRow.noteText == "ORIGINAL",
            )

            // Edit-in-place on another device: notes change, end time (and thus the -end
            // ordering position, index 60) stays fixed.
            note160.set("EDITED REMOTELY")

            vm.onAppResumed()
            awaitTrue("resume pull must converge the out-of-window edit locally") {
                container.eventRepository.event(uuid)?.noteText == "EDITED REMOTELY"
            }
            org.junit.Assert.assertEquals(
                "EDITED REMOTELY",
                container.eventRepository.event(uuid)?.noteText,
            )
        }

    @Test
    fun `plain refresh drops a local running timer that the server no longer has`() = runBlocking {
        val vm = newViewModel()
        loadDashboardWithServerTimer(vm)
        assertTrue(
            "precondition: the running sleep timer is on the strip",
            latest.value.runningTimers.any { it.kind == EventType.SLEEP },
        )

        // The spouse ends it server-side; a plain refresh (pull-to-refresh path) must reconcile.
        timersBody.set("""{"count":0,"next":null,"previous":null,"results":[]}""")
        sleepBody.set(
            """{"count":1,"next":null,"previous":null,"results":[
               {"id":777,"child":1,"start":"2026-11-04T18:00:00Z","end":"2026-11-04T20:00:00Z",
                "notes":"ended on another device"}]}""",
        )

        vm.refresh()
        awaitTrue("refresh must drop the local timer whose server mirror vanished") {
            latest.value.runningTimers.none { it.kind == EventType.SLEEP }
        }
        assertTrue(
            "no running timers may remain: ${latest.value.runningTimers}",
            latest.value.runningTimers.isEmpty(),
        )
    }

    @Test
    fun `panel auto-closes when its running timer is reconciled away`() = runBlocking {
        lastNotice.set(null)
        val vm = newViewModel()
        appScope.launch {
            vm.events.collect {
                if (it is DashboardEvent.TimerEndedRemotely) lastNotice.set(it.message)
            }
        }
        vm.refresh()
        awaitTrue("active child resolved") { latest.value.activeChild != null }

        // Real path: the sleep quick action STARTS the timer (no server timers yet) and the
        // panel opens carrying the running timer (running card, live counter, savable).
        vm.onQuickActionTap(EventType.SLEEP)
        awaitTrue("sleep panel opens carrying the running timer") {
            latest.value.openPanel?.type == EventType.SLEEP && latest.value.openPanel?.timer != null
        }

        // Spouse ends it on another device: the server timer list empties; a refresh reconcile
        // drops the local row (same mechanics test 1/5 pin). The OPEN panel must not outlive it.
        timersBody.set("""{"count":0,"next":null,"previous":null,"results":[]}""")
        vm.refresh()
        awaitTrue("open panel must auto-close once its timer row is reconciled away") {
            latest.value.openPanel == null
        }
        awaitTrue("calm remote-end notice emitted") { lastNotice.get() != null }
        org.junit.Assert.assertEquals("Sleep ended on another phone.", lastNotice.get())
    }

    @Test
    fun `a panel for an unsynced local entry is not auto-closed by a reconcile cycle`() = runBlocking {
        val vm = newViewModel()
        vm.refresh()
        awaitTrue("active child resolved") { latest.value.activeChild != null }

        // A manual-entry panel (note): openPanel with timer == null — never a running timer.
        vm.onQuickActionTap(EventType.NOTE)
        awaitTrue("note panel opens") { latest.value.openPanel?.type == EventType.NOTE }
        assertTrue(
            "precondition: the note panel carries no running timer",
            latest.value.openPanel?.timer == null,
        )

        // A reconcile cycle that CHANGES the timers table (a spouse's sleep timer appears —
        // the same emission class that must close a dead timer's panel) must leave a
        // manual-entry panel untouched.
        timersBody.set(
            """{"count":1,"next":null,"previous":null,"results":[
               {"id":9,"child":1,"name":"Sleep-BBapp:2","start":"2026-11-04T18:00:00Z"}]}""",
        )
        vm.refresh()
        awaitTrue("reconcile adopted the server timer (collector re-emitted)") {
            latest.value.runningTimers.any { it.kind == EventType.SLEEP }
        }
        // Give any (overzealous) auto-close several scheduler beats to land before asserting.
        repeat(8) {
            testDispatcher.scheduler.advanceTimeBy(250)
            testDispatcher.scheduler.runCurrent()
            Thread.sleep(25)
        }
        assertTrue(
            "manual-entry panel must survive a reconcile cycle: ${latest.value.openPanel}",
            latest.value.openPanel?.type == EventType.NOTE,
        )
    }
}
