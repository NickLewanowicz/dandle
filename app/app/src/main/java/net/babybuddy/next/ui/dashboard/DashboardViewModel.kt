package net.babybuddy.next.ui.dashboard

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.babybuddy.next.data.AppContainer
import net.babybuddy.next.data.prefs.FeedingDefault
import net.babybuddy.next.data.prefs.PanelDraft
import net.babybuddy.next.data.repo.EventRepository
import net.babybuddy.next.data.mapper.serverLocalUuid
import net.babybuddy.next.data.sync.SyncWorker
import net.babybuddy.next.domain.DuplicateGuard
import net.babybuddy.next.domain.FailureMessage
import net.babybuddy.next.domain.FeedingOptions
import net.babybuddy.next.domain.TimeEditLogic
import net.babybuddy.next.domain.model.Child
import net.babybuddy.next.domain.model.Event
import net.babybuddy.next.domain.model.EventDraft
import net.babybuddy.next.domain.model.EventType
import net.babybuddy.next.domain.model.RunningTimer
import net.babybuddy.next.domain.model.SyncState
import net.babybuddy.next.domain.model.toDraft
import net.babybuddy.next.data.repo.TimerRepository

/** One open quick-action card/sheet: its type, persisted field draft, and running timer (if any). */
data class OpenPanel(
    val type: EventType,
    val draft: PanelDraft,
    val timer: RunningTimer?,
    /**
     * U4: the create sheet's (diaper/note) absolute entry time — null = "now". Ephemeral by
     * design (defaults to now anyway); the field DRAFTS persist independently.
     */
    val entryTimeMillis: Long? = null,
)

/** The calm duplicate line (spec §3.7) waiting on the user's Add / View-last decision. */
data class DuplicatePrompt(
    val message: String,
    /** Local row of the entry named in the message, for "View last entry". */
    val lastEventLocalUuid: String,
)

/** The Quick Edit sheet's working state (spec §5): a snapshot of the entry plus its draft. */
data class EditState(
    /** Snapshot as opened — the timeline row itself keeps live-syncing underneath. */
    val event: Event,
    val draft: EventDraft,
    /**
     * T6: parent-language reason when the entry is FAILED (same translation the failure dialog
     * uses — never the raw server line; times rendered device-local from the loaded clash row).
     */
    val failureReason: String? = null,
)

/** Failure dialog state (spec §6.2): the FAILED entry plus its parent-language reason. */
data class FailureDialogState(
    val event: Event,
    val reason: String,
    /** Local row of the clashing entry, when loaded — enables "Show clashing entry". */
    val clashLocalUuid: String? = null,
)

data class DashboardUiState(
    val loading: Boolean = true,
    val children: List<Child> = emptyList(),
    val activeChild: Child? = null,
    /** Windowed timeline (paged older-load — spec §2.4 pagination). */
    val timeline: List<Event> = emptyList(),
    val totalTimelineCount: Int = 0,
    val loadingOlder: Boolean = false,
    val runningTimers: List<RunningTimer> = emptyList(),
    val openPanel: OpenPanel? = null,
    val duplicatePrompt: DuplicatePrompt? = null,
    val editState: EditState? = null,
    val failureDialog: FailureDialogState? = null,
    // Sync health (UX spec §5 SyncStatusChip + badges)
    val isOnline: Boolean = true,
    /** A user-initiated (pull-to-refresh) re-sync is running. */
    val refreshing: Boolean = false,
    /** Any sync activity (refresh or outbox drain) — drives "Syncing…". */
    val syncing: Boolean = false,
    /** T3: transient "All synced" settle after a completed drain. */
    val showAllSynced: Boolean = false,
    val failedCount: Int = 0,
    val unsentCount: Int = 0,
    val sessionExpired: Boolean = false,
    val serverUrl: String? = null,
    /** One-shot: show the long-press hint snackbar on the first populated timeline. */
    val showLongPressHint: Boolean = false,
)

sealed interface DashboardEvent {
    data object SignedOut : DashboardEvent

    /** A quick-action save landed locally — the UI scrolls the timeline to the new row. */
    data class EntryCreated(val localUuid: String) : DashboardEvent
}

/**
 * Dashboard (spec §2–§4): children + persisted active child, active timers, the six quick
 * actions with their inline panels (local-first saves incl. stop guard and duplicate guard),
 * the merged timeline with sync badges, and the header's sync-health chip.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModel(
    private val container: AppContainer,
    private val appContext: Context,
) : ViewModel(), QuickActionCallbacks {

    private val childRepo = container.childRepository
    private val eventRepo = container.eventRepository
    private val timerRepo = container.timerRepository
    private val quickActions = container.quickActionStore

    private val _events = MutableSharedFlow<DashboardEvent>(extraBufferCapacity = 1)
    val events = _events

    /**
     * Set once `uiState` (declared below the deep-link init block) has been constructed.
     * viewModelScope runs on Main.immediate, so a pending widget deep link delivered during
     * init collects SYNCHRONOUSLY and would read `uiState` mid-construction — an NPE crash
     * on every cold-start widget stop tap (QA 2026-10-19). Deep-link handling awaits this.
     */
    private val uiStateConstructed = kotlinx.coroutines.CompletableDeferred<Unit>()
    private val activeChildId = MutableStateFlow<Long?>(null)
    private val refreshing = MutableStateFlow(false)
    private val openPanel = MutableStateFlow<OpenPanel?>(null)
    private val duplicatePrompt = MutableStateFlow<DuplicatePrompt?>(null)
    private val editState = MutableStateFlow<EditState?>(null)
    private val failureDialog = MutableStateFlow<FailureDialogState?>(null)
    private val showLongPressHint = MutableStateFlow(false)

    /** T3: the "All synced" chip settles for a few seconds after a drain completes. */
    private val allSyncedVisible = MutableStateFlow(false)
    private var allSyncedSettleJob: kotlinx.coroutines.Job? = null

    // Pagination (spec §2.4: load older on scroll)
    private val visibleCount = MutableStateFlow(TIMELINE_PAGE)
    private val loadingOlder = MutableStateFlow(false)
    private var fetchedLimitPerType = INITIAL_FETCH_LIMIT_PER_TYPE
    private var starvedOlderLoads = 0

    /** Full (unwindowed) local timeline size, refreshed by the combine below. */
    private val totalTimelineCount = MutableStateFlow(0)

    /** A save the duplicate guard is holding until the user picks "Add anyway". */
    private var heldSave: (suspend () -> Unit)? = null

    /** BbLog tag — MUST precede the init blocks (the deep-link collector logs at construction). */
    private val TAG_UI = net.babybuddy.next.log.BbLog.TAG_UI

    // -- Connectivity (offline is a normal path; reconnection re-kicks the drain) -------

    private val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val trackedNetworks: MutableSet<Network> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val isOnline = MutableStateFlow(true)

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            trackedNetworks += network
            isOnline.value = true
            SyncWorker.enqueue(appContext) // reconnect auto-drain (spec §6.1)
        }

        override fun onLost(network: Network) {
            trackedNetworks -= network
            if (trackedNetworks.isEmpty()) isOnline.value = false
        }
    }

    init {
        viewModelScope.launch {
            activeChildId.value = container.sessionStore.sessionFlow.first().activeChildId
            restoreOpenPanel()
        }
        observeRunningTimersAutoOpen()
        // P1-3: a server-rejected write surfaces the failure dialog proactively (spec §3.8/§6.2)
        // — the user must never have to hunt for a badge to learn a save was rejected.
        viewModelScope.launch {
            container.entryFailedEvents.collect { localUuid ->
                val event = eventRepo.event(localUuid) ?: return@collect
                onFailureBadgeTappedInternal(event)
            }
        }
        // Widget deep links (spec §7.1): StateFlow holds the request across cold starts and
        // login gates; consuming runs the exact in-app tap semantics. The request can arrive
        // before children load, so wait for the child list before acting (bounded — a request
        // for a childless server is consumed as a no-op instead of stranding).
        viewModelScope.launch {
            net.babybuddy.next.widget.WidgetDeepLink.panelRequest.collect { type ->
                if (type == null) return@collect
                net.babybuddy.next.log.BbLog.d(TAG_UI, "deep link panel ${type.name}")
                awaitDashboardReady()?.let {
                    onQuickActionTap(type)
                }
                net.babybuddy.next.widget.WidgetDeepLink.consume(type)
            }
        }
        // T5 (spec §7.2): a widget "tap to stop" opens the app DIRECTLY into the stop guard for
        // that timer — the same requestStopGuard the in-app ✕/re-tap uses (>10s dialog, ≤10s
        // silent discard). A vanished timer (state race: stopped elsewhere) falls back to the
        // plain quick-action semantics instead of stranding the request.
        viewModelScope.launch {
            net.babybuddy.next.widget.WidgetDeepLink.stopRequest.collect { type ->
                if (type == null) return@collect
                net.babybuddy.next.log.BbLog.d(TAG_UI, "deep link stop ${type.name}")
                val ready = awaitDashboardReady()
                // Fresh DB read, not the (possibly lagging) UI list: the tap must hit the timer.
                val timer = ready?.activeChild?.let { timerRepo.findRunning(type, it.id) }
                if (timer != null) {
                    requestStopGuard(timer)
                } else if (ready != null) {
                    net.babybuddy.next.log.BbLog.d(
                        TAG_UI,
                        "deep link stop: no running ${type.name} timer — fallback to quick action",
                    )
                    onQuickActionTap(type)
                }
                net.babybuddy.next.widget.WidgetDeepLink.consumeStop(type)
            }
        }
        // Fresh start with no persisted selection: adopt the first child (spec §2 "default =
        // first child, MUST persist"). Without this the header DISPLAYS the fallback child but
        // the timeline queries a null id → an empty dashboard despite server data.
        viewModelScope.launch {
            childRepo.children.collect { children ->
                if (activeChildId.value == null) {
                    children.firstOrNull()?.let { first ->
                        activeChildId.value = first.id
                        container.sessionStore.setActiveChild(first.id)
                    }
                }
            }
        }
        // T3 (UX spec §5): the "caught up" moment — when a drain completes (offline rows landed
        // or the queue drained), settle an "All synced" chip for a few seconds. Skipped while
        // offline (a NetworkDown drain synced nothing — the Offline chip keeps speaking).
        viewModelScope.launch {
            var previous = false // drainActive starts false; settle only on a true->false edge
            container.drainActive.collect { active ->
                if (previous && !active) {
                    if (isOnline.value) {
                        allSyncedVisible.value = true
                        allSyncedSettleJob?.cancel()
                        allSyncedSettleJob = viewModelScope.launch {
                            kotlinx.coroutines.delay(ALL_SYNCED_SETTLE_MS)
                            allSyncedVisible.value = false
                        }
                    }
                }
                previous = active
            }
        }
        try {
            connectivity?.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                networkCallback,
            )
        } catch (_: Exception) {
            // No connectivity service (odd test envs): assume online; sync failures still surface.
        }
    }

    /** Process-death restore (UX spec §2/§3): an open panel re-opens on the stored child. */
    private suspend fun restoreOpenPanel() {
        val childId = activeChildId.value ?: return
        for (type in EventType.entries) {
            val draft = quickActions.panel(childId, type.name) ?: continue
            if (!draft.open) continue
            val timer = if (type.isTimed) timerRepo.findRunning(type, childId) else null
            openPanel.value = OpenPanel(type, draft, timer)
            return
        }
    }

    /**
     * U2: a running timer's card is ALWAYS visible below the buttons — not opt-in via an open
     * panel. This collector opens the panel whenever a timer runs for the active child and no
     * editor is open (app restart with a timer, child switch, timer started from the widget).
     * Idempotent: the panel it opens satisfies the guard on the next emission.
     */
    private fun observeRunningTimersAutoOpen() {
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(activeChildId, timerRepo.observeRunning()) { childId, timers ->
                childId to timers
            }.collect { (childId, timers) ->
                if (childId == null || openPanel.value != null) return@collect
                val unopened = timers.firstOrNull { it.childId == childId && openPanel.value?.type != it.kind }
                if (unopened != null) {
                    net.babybuddy.next.log.BbLog.d(TAG_UI, "auto-open card ${unopened.kind.name}")
                    openPanelFor(unopened.kind, childId)
                }
            }
        }
    }

    /**
     * Deep-link taps read the resolved active child from uiState — wait (bounded) for the
     * combine's first populated state (activeChild != null already implies the child list
     * loaded — it is resolved from it); null = a childless server, consume as a no-op.
     */
    private suspend fun awaitDashboardReady(): DashboardUiState? =
        withTimeoutOrNull(10_000) {
            uiStateConstructed.await()
            uiState.first { it.activeChild != null }
        }

    override fun onCleared() {
        try {
            connectivity?.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }
    }

    private data class Combined(
        val children: List<Child>,
        val timers: List<RunningTimer>,
        val failedCount: Int,
        val unsentCount: Int,
        val drainActive: Boolean,
    )

    private data class Combined2(
        val session: net.babybuddy.next.data.prefs.Session,
        val isOnline: Boolean,
        val refreshing: Boolean,
        val loadingOlder: Boolean,
        val allSynced: Boolean,
    )

    /** Panel/dialog tail of the state (kept as one flow to stay within combine's arity). */
    private data class UiTail(
        val panel: OpenPanel?,
        val prompt: DuplicatePrompt?,
        val edit: EditState?,
        val failure: FailureDialogState?,
        /** Render window (rows shown); an INPUT of the combine so growth re-emits. */
        val window: Int,
    )

    val uiState: StateFlow<DashboardUiState> = combine(
        combine(
            childRepo.children,
            timerRepo.observeRunning(),
            eventRepo.observeFailedCount(),
            eventRepo.observeUnsentCount(),
            container.drainActive,
        ) { children, timers, failed, unsent, drainActive ->
            Combined(children, timers, failed, unsent, drainActive)
        },
        combine(
            container.sessionStore.sessionFlow,
            isOnline,
            refreshing,
            loadingOlder,
            allSyncedVisible,
        ) { session, online, refreshing, loadingOlder, allSynced ->
            Combined2(session, online, refreshing, loadingOlder, allSynced)
        },
        activeChildId.flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else eventRepo.observeTimeline(id)
        },
        combine(
            openPanel,
            duplicatePrompt,
            editState,
            failureDialog,
            visibleCount,
        ) { panel, prompt, edit, failure, window ->
            UiTail(panel, prompt, edit, failure, window)
        },
    ) { core, sessionCore, timeline, tail ->
        val storedId = activeChildId.value ?: sessionCore.session.activeChildId
        val active = core.children.firstOrNull { it.id == storedId } ?: core.children.firstOrNull()
        val childTimers = core.timers.filter { it.childId == (active?.id ?: it.childId) }
        // Open panels only make sense for the active child.
        val panelForChild = tail.panel?.takeIf { it.timer == null || it.timer.childId == active?.id }
        DashboardUiState(
            loading = false,
            children = core.children,
            activeChild = active,
            timeline = timeline.take(tail.window),
            totalTimelineCount = timeline.size,
            loadingOlder = sessionCore.loadingOlder,
            runningTimers = childTimers,
            openPanel = panelForChild,
            duplicatePrompt = tail.prompt,
            editState = tail.edit,
            failureDialog = tail.failure,
            isOnline = sessionCore.isOnline,
            refreshing = sessionCore.refreshing,
            syncing = sessionCore.refreshing || core.drainActive,
            showAllSynced = sessionCore.allSynced,
            failedCount = core.failedCount,
            unsentCount = core.unsentCount,
            sessionExpired = sessionCore.session.sessionExpired,
            serverUrl = sessionCore.session.serverUrl,
            showLongPressHint = showLongPressHint.value,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    /** `uiState` above is now constructed — pending deep links may proceed past the gate. */
    init {
        uiStateConstructed.complete(Unit)
    }

    /** Shows the long-press hint once, on the first populated timeline render (UX spec §2). */
    fun maybeShowLongPressHint() {
        if (showLongPressHint.value) return
        viewModelScope.launch {
            if (!quickActions.wasLongPressHintShown() &&
                totalTimelineCount.value > 0 &&
                uiState.value.timeline.isNotEmpty()
            ) {
                showLongPressHint.value = true
            }
        }
    }

    fun onLongPressHintShown() {
        showLongPressHint.value = false
        viewModelScope.launch { quickActions.setLongPressHintShown() }
    }

    // -- Child selection -----------------------------------------------------------

    fun selectChild(childId: Long) {
        net.babybuddy.next.log.BbLog.d(TAG_UI, "child switch -> $childId")
        val outgoing = openPanel.value
        val previousChildId = activeChildId.value
        openPanel.value = null // panels are per-child; it reopens from that child's draft
        if (outgoing != null && previousChildId != null) {
            viewModelScope.launch {
                quickActions.savePanel(previousChildId, outgoing.type.name, outgoing.draft.copy(open = false))
            }
        }
        activeChildId.value = childId
        viewModelScope.launch { container.sessionStore.setActiveChild(childId) }
    }

    // -- Refresh / sync --------------------------------------------------------------

    /** Pull-to-refresh / app-open sync (spec §2.4): children + timers + events + outbox drain. */
    fun refresh() {
        viewModelScope.launch {
            refreshing.value = true
            childRepo.refresh()
            val childId = uiState.value.activeChild?.id
            if (childId != null) {
                timerRepo.refresh(childId)
            }
            eventRepo.refresh(limitPerType = fetchedLimitPerType)
            refreshing.value = false
            SyncWorker.enqueue(appContext)
        }
    }

    /** SyncStatusChip tap (UX spec §5): force a drain + fresh pull. */
    fun syncNow() {
        net.babybuddy.next.log.BbLog.d(TAG_UI, "sync now (chip tap)")
        SyncWorker.enqueue(appContext)
        refresh()
    }

    // -- Quick actions (spec §3) -------------------------------------------------------

    /**
     * U3 (original-app tap semantics): tapping a timer type with a RUNNING timer is tap-to-stop —
     * the StopGuard decides dialog vs silent discard (>10s / ≤10s). A stopped/absent timer starts
     * one and opens its card. Edit-like types (diaper/note) toggle their sheet.
     */
    fun onQuickActionTap(type: EventType) {
        val child = uiState.value.activeChild ?: return
        net.babybuddy.next.log.BbLog.d(TAG_UI, "quick action ${type.name} child=${child.id}")
        if (type.isTimed) {
            val running = uiState.value.runningTimers.firstOrNull { it.kind == type }
            if (running != null) {
                requestStopGuard(running)
            } else {
                viewModelScope.launch {
                    timerRepo.start(type, child.id)
                    openPanelFor(type, child.id)
                }
            }
        } else {
            if (openPanel.value?.type == type) {
                onClosePanel(type)
            } else {
                viewModelScope.launch { openPanelFor(type, child.id) }
            }
        }
    }

    /** Called by the UI when the user asks to stop a running timer (re-tap, strip ✕, panel ✕).
     *  The ≤10s / >10s decision is StopGuard's; the silent branch discards immediately. */
    fun requestStopGuard(timer: RunningTimer) {
        viewModelScope.launch {
            val elapsed = container.serverClock.nowMillis() - timer.startedAtMillis
            if (net.babybuddy.next.domain.StopGuard.shouldConfirm(elapsed)) {
                _stopGuardRequests.value = timer
            } else {
                discardTimer(timer)
            }
        }
    }

    /** One-shot requests for the >10s "Stop timer?" dialog (UI observes and consumes). */
    private val _stopGuardRequests = MutableStateFlow<RunningTimer?>(null)
    val stopGuardRequests: StateFlow<RunningTimer?> = _stopGuardRequests.asStateFlow()

    fun consumeStopGuardRequest() {
        _stopGuardRequests.value = null
    }

    override fun onClosePanel(type: EventType) {
        val panel = openPanel.value?.takeIf { it.type == type } ?: return
        // P2-4: on a running timer the panel ✕ is a stop-guard trigger (UX spec §3) — never a
        // silent close. <=10s discards silently (and closes); >10s raises the guard dialog:
        // Save/Discard close the panel via their paths, Keep keeps both timer and panel open.
        panel.timer?.let { running ->
            requestStopGuard(running)
            return
        }
        openPanel.value = null
        val childId = uiState.value.activeChild?.id ?: return
        viewModelScope.launch {
            // Field values persist while the panel is closed (original app draft behavior).
            quickActions.savePanel(childId, type.name, panel.draft.copy(open = false))
        }
    }

    // Draft field updates — every change updates state AND persists (process-death safe).

    fun onPanelFeedingTypeChange(wire: String) = updateDraft(EventType.FEEDING) { draft ->
        draft.copy(feedingType = wire, feedingMethod = FeedingOptions.resetMethod(wire, draft.feedingMethod))
    }

    fun onPanelFeedingMethodChange(wire: String) = updateDraft(EventType.FEEDING) { draft ->
        draft.copy(feedingMethod = wire)
    }

    fun onPanelAmountChange(text: String) = updateDraft(null) { draft ->
        draft.copy(amountText = text.filter { it.isDigit() || it == '.' || it == ',' })
    }

    fun onPanelWetToggle() = updateDraft(EventType.CHANGE) { it.copy(wet = !it.wet) }

    fun onPanelSolidToggle() = updateDraft(EventType.CHANGE) { it.copy(solid = !it.solid) }

    fun onPanelColorChange(wire: String) = updateDraft(EventType.CHANGE) { draft ->
        draft.copy(color = if (draft.color == wire) null else wire)
    }

    fun onPanelExtraOptionsToggle() = updateDraft(EventType.CHANGE) { it.copy(extraOpen = !it.extraOpen) }

    fun onPanelNoteChange(text: String) = updateDraft(null) { it.copy(noteText = text) }

    fun onPanelMilestoneChange(text: String) = updateDraft(EventType.TUMMY_TIME) { it.copy(milestone = text) }

    fun onPanelTextChange(text: String) = updateDraft(EventType.NOTE) { it.copy(text = text) }

    /** U4: the create sheet's TIME row — absolute entry time for diaper/note (null = now). */
    override fun onCreateTimePicked(millis: Long) {
        val panel = openPanel.value ?: return
        if (panel.type.isTimed) return
        openPanel.value = panel.copy(entryTimeMillis = millis)
    }

    // -- T2: "started X min ago" adjust on timed panels (spec §5 panel time-adjust) ----------

    /** Stepper: [minutesAgoDelta] > 0 backdates ("−15m" = started 15 minutes earlier). */
    fun onPanelStartAdjustStepped(minutesAgoDelta: Long) {
        val panel = openPanel.value ?: return
        if (!panel.type.isTimed) return
        updateDraft(null) { draft ->
            draft.copy(
                startedMinutesAgo = (draft.startedMinutesAgo + minutesAgoDelta.toInt())
                    .coerceIn(0, MAX_STARTED_AGO_MINUTES),
            )
        }
    }

    /** Absolute set from the platform time picker: stored as whole minutes ago. */
    fun onPanelStartTimePicked(millis: Long) {
        val panel = openPanel.value ?: return
        if (!panel.type.isTimed) return
        val now = container.serverClock.nowMillis()
        val minutes = ((now - millis) / 60_000L).coerceIn(0L, MAX_STARTED_AGO_MINUTES.toLong())
        updateDraft(null) { it.copy(startedMinutesAgo = minutes.toInt()) }
    }

    private fun updateDraft(type: EventType?, transform: (PanelDraft) -> PanelDraft) {
        val panel = openPanel.value ?: return
        if (type != null && panel.type != type) return
        val updated = transform(panel.draft)
        openPanel.value = panel.copy(draft = updated)
        val childId = uiState.value.activeChild?.id ?: return
        viewModelScope.launch { quickActions.savePanel(childId, panel.type.name, updated) }
    }

    /** Save-disabled conditions are the only create-panel validation (UX spec §3). */
    fun isPanelSaveEnabled(panel: OpenPanel): Boolean = when (panel.type) {
        EventType.CHANGE -> panel.draft.wet || panel.draft.solid
        EventType.NOTE -> panel.draft.text.isNotBlank()
        else -> true
    }

    /** Save from the timer card or the create sheet — runs the duplicate guard first (spec §3.7). */
    fun savePanel() {
        val panel = openPanel.value ?: return
        val child = uiState.value.activeChild ?: return
        if (!isPanelSaveEnabled(panel)) return
        net.babybuddy.next.log.BbLog.d(TAG_UI, "panel save ${panel.type.name} child=${child.id}")
        // U4: the create sheet's TIME row backdates the entry (duplicate guard anchors there too).
        val now = if (!panel.type.isTimed) {
            panel.entryTimeMillis ?: container.serverClock.nowMillis()
        } else {
            container.serverClock.nowMillis()
        }
        val draft = panel.draft.toEventDraft(panel.type, child.id, now)
        viewModelScope.launch {
            val last = eventRepo.latestByType(child.id, panel.type)
            val hit = DuplicateGuard.check(now, last)
            if (hit != null) {
                heldSave = { commitSave(panel.type, child.id, draft, timer = null) }
                duplicatePrompt.value = DuplicatePrompt(hit.message, hit.lastEvent.localUuid)
            } else {
                commitSave(panel.type, child.id, draft, timer = null)
            }
        }
    }

    /** Stop + save from the timer strip card or the stop-guard dialog. */
    fun saveRunningTimer(timer: RunningTimer) {
        net.babybuddy.next.log.BbLog.d(TAG_UI, "strip save ${timer.kind.name} localId=${timer.localId}")
        val childId = timer.childId
        val now = container.serverClock.nowMillis()
        viewModelScope.launch {
            val draft = panelDraftFor(timer.kind, childId).toEventDraft(timer.kind, childId, now)
            val last = eventRepo.latestByType(childId, timer.kind)
            val hit = DuplicateGuard.check(now, last)
            if (hit != null) {
                heldSave = { commitSave(timer.kind, childId, draft, timer) }
                duplicatePrompt.value = DuplicatePrompt(hit.message, hit.lastEvent.localUuid)
            } else {
                commitSave(timer.kind, childId, draft, timer)
            }
        }
    }

    fun discardTimer(timer: RunningTimer) {
        net.babybuddy.next.log.BbLog.d(TAG_UI, "strip discard ${timer.kind.name} localId=${timer.localId}")
        viewModelScope.launch {
            timerRepo.discard(timer)
            net.babybuddy.next.widget.WidgetRefresher.refreshAll(appContext)
            val panel = openPanel.value?.takeIf { it.type == timer.kind }
            if (panel != null) {
                openPanel.value = null
                quickActions.savePanel(timer.childId, panel.type.name, panel.draft.copy(open = false))
            }
        }
    }

    fun onDuplicateAdd() {
        net.babybuddy.next.log.BbLog.d(TAG_UI, "duplicate prompt: add")
        duplicatePrompt.value = null
        val save = heldSave
        heldSave = null
        if (save != null) {
            viewModelScope.launch { save() }
        }
    }

    fun onDuplicateDismiss() {
        net.babybuddy.next.log.BbLog.d(TAG_UI, "duplicate prompt: dismiss")
        duplicatePrompt.value = null
        heldSave = null
    }

    private suspend fun commitSave(type: EventType, childId: Long, draft: EventDraft, timer: RunningTimer?) {
        val created = if (type.isTimed) {
            val running = timer ?: timerRepo.findRunning(type, childId) ?: return
            timerRepo.stopAndSave(running, draft)
        } else {
            eventRepo.create(draft)
        }
        // The insert animation is the save confirmation (UX §3): make sure the new row is
        // actually IN view — a scrolled-back timeline must not silently swallow it.
        _events.tryEmit(DashboardEvent.EntryCreated(created.localUuid))
        if (type == EventType.FEEDING && draft.feedingType != null && draft.feedingMethod != null) {
            quickActions.rememberFeeding(childId, draft.feedingType!!, draft.feedingMethod!!)
        }
        quickActions.clearPanel(childId, type.name)
        if (openPanel.value?.type == type) openPanel.value = null
        net.babybuddy.next.widget.WidgetRefresher.refreshAll(appContext)
    }

    private suspend fun openPanelFor(type: EventType, childId: Long) {
        net.babybuddy.next.log.BbLog.d(TAG_UI, "openPanelFor ${type.name} child=$childId")
        // One editor at a time (original app): persist the outgoing panel closed, keep values.
        openPanel.value?.takeIf { it.type != type }?.let { current ->
            quickActions.savePanel(childId, current.type.name, current.draft.copy(open = false))
        }
        val draft = panelDraftFor(type, childId).copy(open = true)
        quickActions.savePanel(childId, type.name, draft)
        val timer = if (type.isTimed) timerRepo.findRunning(type, childId) else null
        // The tap path and the auto-open collector can both open the same card within the same
        // second (timer insert → observeRunning → auto-open, while the tap's own open is still
        // suspended on DataStore). Whoever lands second must NOT replace the user's live
        // DRAFT — but it MUST adopt the freshly-loaded timer: the losing panel may have been
        // built before the timer row committed (timer=null → the card would never render).
        openPanel.value?.takeIf { it.type == type }?.let { existing ->
            net.babybuddy.next.log.BbLog.d(
                TAG_UI,
                "openPanelFor ${type.name}: already open, keeping live draft, timer ${timer?.localId ?: existing.timer?.localId}",
            )
            openPanel.value = existing.copy(timer = existing.timer ?: timer)
            return
        }
        openPanel.value = OpenPanel(type, draft, timer)
        net.babybuddy.next.log.BbLog.d(TAG_UI, "openPanel set ${type.name} timer=${timer?.localId}")
    }

    /** Stored field values for this child+type; feeding defaults learn per child (spec §3.1). */
    private suspend fun panelDraftFor(type: EventType, childId: Long): PanelDraft {
        val stored = quickActions.panel(childId, type.name) ?: PanelDraft()
        if (type == EventType.FEEDING && stored.feedingType == null) {
            val def = quickActions.feedingDefault(childId)
            return stored.copy(feedingType = def.typeWire, feedingMethod = def.methodWire)
        }
        return stored
    }

    // -- QuickActionCallbacks (panel → VM contract; see QuickActionsSection.kt) -------

    override fun onSavePanel() = savePanel()

    override fun onFeedingTypeChange(wire: String) = onPanelFeedingTypeChange(wire)

    override fun onFeedingMethodChange(wire: String) = onPanelFeedingMethodChange(wire)

    override fun onAmountChange(text: String) = onPanelAmountChange(text)

    override fun onWetToggle() = onPanelWetToggle()

    override fun onSolidToggle() = onPanelSolidToggle()

    override fun onColorChange(wire: String) = onPanelColorChange(wire)

    override fun onExtraOptionsToggle() = onPanelExtraOptionsToggle()

    override fun onNoteChange(text: String) = onPanelNoteChange(text)

    override fun onMilestoneChange(text: String) = onPanelMilestoneChange(text)

    override fun onTextChange(text: String) = onPanelTextChange(text)

    override fun onStartAdjustStepped(minutesAgoDelta: Long) = onPanelStartAdjustStepped(minutesAgoDelta)

    override fun onStartTimePicked(millis: Long) = onPanelStartTimePicked(millis)

    // -- Timeline -------------------------------------------------------------------

    /**
     * Paged older-load (spec §2.4), P1-1/P1-3 fix: the render window is an INPUT of the uiState
     * combine, so growing it re-emits immediately; the decision keys off totalTimelineCount (the
     * full local query size) — NEVER the windowed count (a windowed-size comparison is circular
     * and wedged the window at its initial size, hiding edited/new rows beyond it). Once local
     * rows are exhausted, pull a larger per-type slice from the server; two consecutive empty
     * fetches mark the end of history for this session.
     */
    fun loadOlder() {
        if (loadingOlder.value || starvedOlderLoads >= 2) return
        val state = uiState.value
        if (state.timeline.size < state.totalTimelineCount) {
            visibleCount.value += TIMELINE_PAGE // more local rows to reveal
            return
        }
        viewModelScope.launch {
            loadingOlder.value = true
            try {
                val before = state.totalTimelineCount
                fetchedLimitPerType += TIMELINE_PAGE_PER_TYPE
                val result = eventRepo.refresh(limitPerType = fetchedLimitPerType)
                val grew = uiState.value.totalTimelineCount > before
                starvedOlderLoads = if (result is EventRepository.RefreshResult.Success && !grew) {
                    starvedOlderLoads + 1
                } else {
                    0
                }
                visibleCount.value += TIMELINE_PAGE
            } finally {
                loadingOlder.value = false
            }
        }
    }

    /** Long-press → same entry on the Baby Buddy web UI (spec §4.2). Null when unsynced. */
    fun webUrlFor(event: Event): String? {
        val serverId = event.serverId ?: return null
        val server = uiState.value.serverUrl?.trimEnd('/') ?: return null
        return "$server/${event.type.uiPath}/$serverId/"
    }

    // -- Quick Edit sheet (spec §5) ---------------------------------------------------

    /** Row tap → the sheet (spec §4.2). Never more fields than the quick action itself. */
    fun onTimelineRowTapped(event: Event) {
        net.babybuddy.next.log.BbLog.d(TAG_UI, "edit open ${event.type.name} ${event.localUuid}")
        viewModelScope.launch {
            val stored = eventRepo.event(event.localUuid) ?: return@launch
            editState.value = EditState(
                event = stored,
                draft = stored.toDraft(),
                failureReason = if (stored.syncState == SyncState.FAILED) {
                    presentFailure(stored).reason
                } else {
                    null
                },
            )
        }
    }

    fun closeEdit() {
        editState.value = null
    }

    fun onEditDraftChange(transform: (EventDraft) -> EventDraft) {
        val current = editState.value ?: return
        editState.value = current.copy(draft = transform(current.draft))
    }

    /** Absolute set from the platform date+time picker. */
    fun onEditStartTimeSet(millis: Long) {
        val current = editState.value ?: return
        if (current.event.type.isTimed) {
            val (start, end) = TimeEditLogic.moveStart(current.draft.startMillis, current.draft.endMillis, millis)
            onEditDraftChange { it.copy(startMillis = start, endMillis = end) }
        } else {
            onEditDraftChange { it.copy(startMillis = millis) }
        }
    }

    fun onEditEndTimeSet(millis: Long) {
        onEditDraftChange { it.copy(endMillis = millis) }
    }

    /**
     * Inline validation reason (Save disabled while non-null) — computed against the live
     * server clock so "no future" stays honest as time passes. Combines the type-field gate
     * (diaper needs Wet/Solid — mirrors the create panel) with the time constraints.
     */
    fun editValidationReason(state: EditState): String? =
        TimeEditLogic.validateTypeFields(state.event.type, state.draft.wet, state.draft.solid)
            ?: TimeEditLogic.validate(
                startMillis = state.draft.startMillis,
                endMillis = state.draft.endMillis,
                isTimed = state.event.type.isTimed,
                nowMillis = container.serverClock.nowMillis(),
            )

    /** Save → PATCH online or queued edit op offline; row updates in place (spec §5). */
    fun saveEdit() {
        net.babybuddy.next.log.BbLog.d(TAG_UI, "edit save ${currentLogId()}")
        val current = editState.value ?: return
        if (editValidationReason(current) != null) return
        val uuid = current.event.localUuid
        editState.value = null
        viewModelScope.launch {
            eventRepo.edit(uuid, current.draft)
            net.babybuddy.next.widget.WidgetRefresher.refreshAll(appContext)
        }
    }

    /** FAILED entry "Retry" (spec §6.2): re-queues the op; badge → PENDING. */
    fun retryEvent(localUuid: String) {
        net.babybuddy.next.log.BbLog.d(TAG_UI, "entry retry $localUuid")
        editState.value = null
        failureDialog.value = null
        viewModelScope.launch { eventRepo.retry(localUuid) }
    }

    /** Delete with confirm (UI owns the confirm dialog); local-only for never-synced rows. */
    fun deleteEvent(localUuid: String) {
        net.babybuddy.next.log.BbLog.d(TAG_UI, "entry delete $localUuid")
        editState.value = null
        failureDialog.value = null
        viewModelScope.launch { eventRepo.delete(localUuid) }
    }

    // -- Failure dialog (spec §6.2 / UX spec §5) --------------------------------------

    /** FAILED badge tap → parent-language reason + resolution actions. */
    fun onFailureBadgeTapped(event: Event) {
        net.babybuddy.next.log.BbLog.d(TAG_UI, "failure dialog ${event.localUuid}")
        viewModelScope.launch { onFailureBadgeTappedInternal(event) }
    }

    /** Parent-language presentation of a rejection: translated reason + loaded clash row. */
    private data class FailurePresentation(val reason: String, val clashLocalUuid: String?)

    /**
     * Shared by the failure dialog (badge tap + the P1-3 proactive surfacing) and the Quick Edit
     * sheet's failure banner (T6): one translation, so the same entry reads the same everywhere.
     */
    private suspend fun presentFailure(event: Event): FailurePresentation {
        var clash: Event? = null
        val conflictServerId = event.conflictServerId
        val conflictUiPath = event.conflictUiPath
        if (conflictServerId != null && conflictUiPath != null) {
            // Conflict refs carry the web path ("tummy-time"); match either spelling.
            val type = EventType.entries.firstOrNull {
                it.uiPath == conflictUiPath || it.apiPath == conflictUiPath
            }
            if (type != null) {
                clash = uiState.value.timeline.firstOrNull {
                    it.type == type && it.serverId == conflictServerId
                } ?: eventRepo.event(serverLocalUuid(type, conflictServerId))
            }
        }
        return FailurePresentation(
            reason = FailureMessage.translate(
                reason = event.failureReason,
                conflictUiPath = conflictUiPath,
                conflictServerId = conflictServerId,
                clash = clash,
            ),
            clashLocalUuid = clash?.localUuid,
        )
    }

    private suspend fun onFailureBadgeTappedInternal(event: Event) {
        if (event.syncState != SyncState.FAILED) return
        val presentation = presentFailure(event)
        failureDialog.value = FailureDialogState(
            event = event,
            reason = presentation.reason,
            clashLocalUuid = presentation.clashLocalUuid,
        )
    }

    fun dismissFailureDialog() {
        failureDialog.value = null
    }

    /** Failure dialog "Edit" → the Quick Edit sheet with the original values intact. */
    fun editFromFailureDialog() {
        val event = failureDialog.value?.event ?: return
        failureDialog.value = null
        onTimelineRowTapped(event)
    }

    // -- Session ----------------------------------------------------------------------

    fun signOut() {
        net.babybuddy.next.log.BbLog.d(TAG_UI, "sign out")
        viewModelScope.launch {
            container.signOut()
            _events.tryEmit(DashboardEvent.SignedOut)
        }
    }

    // -- Helpers -----------------------------------------------------------------------

    private fun PanelDraft.toEventDraft(type: EventType, childId: Long, now: Long): EventDraft {
        val amount = amountText.trim().replace(',', '.').toDoubleOrNull()?.coerceAtLeast(0.0)
        val startAdjust = startedMinutesAgo * 60_000L
        return EventDraft(
            type = type,
            childId = childId,
            // T2: the panel's "started X min ago" backdates the start; the timer save path
            // applies it against the timer start (duration rules unchanged).
            startMillis = now - startAdjust,
            endMillis = now, // timed saves override via TimerRepository.stopAndSave
            startAdjustMillis = startAdjust,
            feedingType = feedingType,
            feedingMethod = feedingMethod,
            amount = when (type) {
                EventType.FEEDING -> amount
                EventType.PUMPING -> amount ?: 0.0 // server-required (research doc §4)
                EventType.CHANGE -> amount
                else -> null
            },
            wet = if (type == EventType.CHANGE) wet else null,
            solid = if (type == EventType.CHANGE) solid else null,
            color = if (type == EventType.CHANGE) color?.takeIf { it.isNotBlank() } else null,
            noteText = when (type) {
                EventType.NOTE -> text.trim()
                else -> noteText.trim().ifBlank { null }
            },
            milestone = if (type == EventType.TUMMY_TIME) milestone.trim().ifBlank { null } else null,
        )
    }

    private fun currentLogId(): String =
        editState.value?.let { "${it.event.type.name} ${it.event.localUuid}" } ?: "none"

    companion object {
        const val TIMELINE_PAGE = 30
        const val TIMELINE_PAGE_PER_TYPE = 20
        const val INITIAL_FETCH_LIMIT_PER_TYPE = 50

        /** T3: how long the "All synced" chip stays after a drain completes. */
        const val ALL_SYNCED_SETTLE_MS = 4_000L

        /** T2: "started X min ago" clamp (a full day back covers every realistic correction). */
        const val MAX_STARTED_AGO_MINUTES = 1440
    }
}
