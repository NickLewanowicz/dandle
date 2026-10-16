package net.babybuddy.next.ui.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.babybuddy.next.data.AppContainer
import net.babybuddy.next.data.repo.ChildRepository
import net.babybuddy.next.data.repo.EventRepository
import net.babybuddy.next.data.sync.SyncWorker
import net.babybuddy.next.domain.Clock
import net.babybuddy.next.domain.model.Child
import net.babybuddy.next.domain.model.Event
import net.babybuddy.next.domain.model.RunningTimer

data class DashboardUiState(
    val loading: Boolean = true,
    val children: List<Child> = emptyList(),
    val activeChild: Child? = null,
    val timeline: List<Event> = emptyList(),
    val runningTimers: List<RunningTimer> = emptyList(),
    /** Set after a refresh that couldn't reach the server (offline is normal, not an error). */
    val offlineAfterRefresh: Boolean = false,
)

sealed interface DashboardEvent {
    data object SignedOut : DashboardEvent
}

/**
 * Dashboard shell (spec §2 placeholder until Stage 2's quick actions): children from
 * ChildRepository, timeline from EventRepository, running timers from TimerRepository.
 * Active-child selection persists via SessionStore (spec §2.1).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModel(
    private val container: AppContainer,
    private val appContext: Context,
) : ViewModel() {

    private val childRepo: ChildRepository = container.childRepository
    private val eventRepo = container.eventRepository
    private val timerRepo = container.timerRepository

    private val _events = MutableSharedFlow<DashboardEvent>(extraBufferCapacity = 1)
    val events: MutableSharedFlow<DashboardEvent> = _events

    private val activeChildId = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)

    private val refreshing = kotlinx.coroutines.flow.MutableStateFlow(false)

    init {
        // Restore the persisted selection (spec §2.1); combine() defaults to the first child
        // when the stored id is null or no longer exists.
        viewModelScope.launch {
            activeChildId.value = container.sessionStore.sessionFlow.first().activeChildId
        }
    }

    val uiState: StateFlow<DashboardUiState> =
        combine(
            childRepo.children,
            container.sessionStore.sessionFlow,
            activeChildId.flatMapLatest { id ->
                val childId = id ?: return@flatMapLatest flowOf(emptyList())
                eventRepo.observeTimeline(childId)
            },
            timerRepo.observeRunning(),
            refreshing,
        ) { children, session, timeline, timers, refreshing ->
            val active = children.firstOrNull { it.id == (activeChildId.value ?: session.activeChildId) }
                ?: children.firstOrNull()
            DashboardUiState(
                loading = false,
                children = children,
                activeChild = active,
                timeline = timeline,
                runningTimers = timers.filter { it.childId == (active?.id ?: it.childId) },
                offlineAfterRefresh = false,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    fun selectChild(childId: Long) {
        activeChildId.value = childId
        viewModelScope.launch { container.sessionStore.setActiveChild(childId) }
    }

    /** Pull-to-refresh / app-open sync (spec §2.4): children + timers + events + outbox drain. */
    fun refresh() {
        viewModelScope.launch {
            refreshing.value = true
            childRepo.refresh()
            val childId = uiState.value.activeChild?.id
            if (childId != null) {
                timerRepo.refresh(childId)
            }
            val result = eventRepo.refresh()
            refreshing.value = false
            SyncWorker.enqueue(appContext)
            if (result is EventRepository.RefreshResult.Failure) {
                // surfaced subtly; the queued outbox still drains when reachable
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            container.signOut()
            _events.tryEmit(DashboardEvent.SignedOut)
        }
    }
}
