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
import net.babybuddy.next.data.sync.SyncWorker
import net.babybuddy.next.domain.DuplicateGuard
import net.babybuddy.next.domain.FeedingOptions
import net.babybuddy.next.domain.model.Child
import net.babybuddy.next.domain.model.Event
import net.babybuddy.next.domain.model.EventDraft
import net.babybuddy.next.domain.model.EventType
import net.babybuddy.next.domain.model.RunningTimer
import net.babybuddy.next.data.repo.TimerRepository

/** One open quick-action panel: its type, persisted field draft, and running timer (if any). */
data class OpenPanel(
    val type: EventType,
    val draft: PanelDraft,
    val timer: RunningTimer?,
)

/** The calm duplicate line (spec §3.7) waiting on the user's Add / View-last decision. */
data class DuplicatePrompt(
    val message: String,
    /** Local row of the entry named in the message, for "View last entry". */
    val lastEventLocalUuid: String,
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
    // Sync health (UX spec §5 SyncStatusChip + badges)
    val isOnline: Boolean = true,
    /** A user-initiated (pull-to-refresh) re-sync is running. */
    val refreshing: Boolean = false,
    /** Any sync activity (refresh or outbox drain) — drives "Syncing…". */
    val syncing: Boolean = false,
    val failedCount: Int = 0,
    val unsentCount: Int = 0,
    val sessionExpired: Boolean = false,
    val serverUrl: String? = null,
    /** One-shot: show the long-press hint snackbar on the first populated timeline. */
    val showLongPressHint: Boolean = false,
)

sealed interface DashboardEvent {
    data object SignedOut : DashboardEvent
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

    private val activeChildId = MutableStateFlow<Long?>(null)
    private val refreshing = MutableStateFlow(false)
    private val openPanel = MutableStateFlow<OpenPanel?>(null)
    private val duplicatePrompt = MutableStateFlow<DuplicatePrompt?>(null)
    private val showLongPressHint = MutableStateFlow(false)

    // Pagination (spec §2.4: load older on scroll)
    private val visibleCount = MutableStateFlow(TIMELINE_PAGE)
    private val loadingOlder = MutableStateFlow(false)
    private var fetchedLimitPerType = INITIAL_FETCH_LIMIT_PER_TYPE
    private var starvedOlderLoads = 0

    /** Full (unwindowed) local timeline size, refreshed by the combine below. */
    private val totalTimelineCount = MutableStateFlow(0)

    /** A save the duplicate guard is holding until the user picks "Add anyway". */
    private var heldSave: (suspend () -> Unit)? = null

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

    override fun onCleared() {
        try {
            connectivity?.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }
    }

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
        ) { session, online, refreshing, loadingOlder ->
            Combined2(session, online, refreshing, loadingOlder)
        },
        activeChildId.flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else eventRepo.observeTimeline(id)
        },
        openPanel,
        duplicatePrompt,
    ) { core, sessionCore, timeline, panel, prompt ->
        val storedId = activeChildId.value ?: sessionCore.session.activeChildId
        val active = core.children.firstOrNull { it.id == storedId } ?: core.children.firstOrNull()
        val childTimers = core.timers.filter { it.childId == (active?.id ?: it.childId) }
        // Open panels only make sense for the active child.
        val panelForChild = panel?.takeIf { it.timer == null || it.timer.childId == active?.id }
        if (timeline.size != totalTimelineCount.value) totalTimelineCount.value = timeline.size
        DashboardUiState(
            loading = false,
            children = core.children,
            activeChild = active,
            timeline = timeline.take(visibleCount.value),
            totalTimelineCount = timeline.size,
            loadingOlder = sessionCore.loadingOlder,
            runningTimers = childTimers,
            openPanel = panelForChild,
            duplicatePrompt = prompt,
            isOnline = sessionCore.isOnline,
            refreshing = sessionCore.refreshing,
            syncing = sessionCore.refreshing || core.drainActive,
            failedCount = core.failedCount,
            unsentCount = core.unsentCount,
            sessionExpired = sessionCore.session.sessionExpired,
            serverUrl = sessionCore.session.serverUrl,
            showLongPressHint = showLongPressHint.value,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

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
        SyncWorker.enqueue(appContext)
        refresh()
    }

    // -- Quick actions (spec §3) -------------------------------------------------------

    fun onQuickActionTap(type: EventType) {
        val child = uiState.value.activeChild ?: return
        if (type.isTimed) {
            val running = uiState.value.runningTimers.firstOrNull { it.kind == type }
            when {
                running == null -> viewModelScope.launch {
                    timerRepo.start(type, child.id)
                    openPanelFor(type, child.id)
                }

                openPanel.value?.type == type -> requestStopGuard(running)

                else -> viewModelScope.launch { openPanelFor(type, child.id) }
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

    /** Save from the panel (or the strip card) — runs the duplicate guard first (spec §3.7). */
    fun savePanel() {
        val panel = openPanel.value ?: return
        val child = uiState.value.activeChild ?: return
        if (!isPanelSaveEnabled(panel)) return
        val now = container.serverClock.nowMillis()
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
        viewModelScope.launch {
            timerRepo.discard(timer)
            val panel = openPanel.value?.takeIf { it.type == timer.kind }
            if (panel != null) {
                openPanel.value = null
                quickActions.savePanel(timer.childId, panel.type.name, panel.draft.copy(open = false))
            }
        }
    }

    fun onDuplicateAdd() {
        duplicatePrompt.value = null
        val save = heldSave
        heldSave = null
        if (save != null) {
            viewModelScope.launch { save() }
        }
    }

    fun onDuplicateDismiss() {
        duplicatePrompt.value = null
        heldSave = null
    }

    private suspend fun commitSave(type: EventType, childId: Long, draft: EventDraft, timer: RunningTimer?) {
        if (type.isTimed) {
            val running = timer ?: timerRepo.findRunning(type, childId) ?: return
            timerRepo.stopAndSave(running, draft)
        } else {
            eventRepo.create(draft)
        }
        if (type == EventType.FEEDING && draft.feedingType != null && draft.feedingMethod != null) {
            quickActions.rememberFeeding(childId, draft.feedingType!!, draft.feedingMethod!!)
        }
        quickActions.clearPanel(childId, type.name)
        if (openPanel.value?.type == type) openPanel.value = null
    }

    private suspend fun openPanelFor(type: EventType, childId: Long) {
        // One editor at a time (original app): persist the outgoing panel closed, keep values.
        openPanel.value?.takeIf { it.type != type }?.let { current ->
            quickActions.savePanel(childId, current.type.name, current.draft.copy(open = false))
        }
        val draft = panelDraftFor(type, childId).copy(open = true)
        quickActions.savePanel(childId, type.name, draft)
        val timer = if (type.isTimed) timerRepo.findRunning(type, childId) else null
        openPanel.value = OpenPanel(type, draft, timer)
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

    // -- Timeline -------------------------------------------------------------------

    /**
     * Paged older-load (spec §2.4): first reveal more local rows; once local data is exhausted,
     * pull a larger slice per type from the server. Two consecutive empty fetches mark the
     * end of history for this session.
     */
    fun loadOlder(visibleItemCount: Int) {
        if (loadingOlder.value || starvedOlderLoads >= 2) return
        if (visibleCount.value < visibleItemCount) {
            visibleCount.value += TIMELINE_PAGE
            return
        }
        viewModelScope.launch {
            loadingOlder.value = true
            val before = totalTimelineCount.value
            fetchedLimitPerType += TIMELINE_PAGE_PER_TYPE
            val result = eventRepo.refresh(limitPerType = fetchedLimitPerType)
            val grew = totalTimelineCount.value > before
            starvedOlderLoads = if (result is EventRepository.RefreshResult.Success && !grew) {
                starvedOlderLoads + 1
            } else {
                0
            }
            visibleCount.value += TIMELINE_PAGE
            loadingOlder.value = false
        }
    }

    /** Long-press → same entry on the Baby Buddy web UI (spec §4.2). Null when unsynced. */
    fun webUrlFor(event: Event): String? {
        val serverId = event.serverId ?: return null
        val server = uiState.value.serverUrl?.trimEnd('/') ?: return null
        return "$server/${event.type.uiPath}/$serverId/"
    }

    /** Row tap — the Quick Edit sheet arrives in Stage 3; clickable now, deliberately inert. */
    fun onTimelineRowTapped(@Suppress("UNUSED_PARAMETER") event: Event) = Unit

    // -- Session ----------------------------------------------------------------------

    fun signOut() {
        viewModelScope.launch {
            container.signOut()
            _events.tryEmit(DashboardEvent.SignedOut)
        }
    }

    // -- Helpers -----------------------------------------------------------------------

    private fun PanelDraft.toEventDraft(type: EventType, childId: Long, now: Long): EventDraft {
        val amount = amountText.trim().replace(',', '.').toDoubleOrNull()?.coerceAtLeast(0.0)
        return EventDraft(
            type = type,
            childId = childId,
            startMillis = now,
            endMillis = now, // timed saves override via TimerRepository.stopAndSave
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
    )

    companion object {
        const val TIMELINE_PAGE = 30
        const val TIMELINE_PAGE_PER_TYPE = 20
        const val INITIAL_FETCH_LIMIT_PER_TYPE = 50
    }
}
