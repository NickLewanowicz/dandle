package app.dandle.data.repo

import kotlinx.coroutines.flow.firstOrNull
import app.dandle.data.prefs.QuickActionStore
import app.dandle.data.prefs.SessionStore
import app.dandle.domain.ServerClock
import app.dandle.domain.StopGuard
import app.dandle.domain.model.Event
import app.dandle.domain.model.EventType
import app.dandle.domain.model.RunningTimer

/**
 * Widget-facing facade over the repositories (Stage-3 handoff note): home-screen trampolines and
 * Glance actions reuse EXACTLY the in-app semantics (DashboardViewModel) without reaching into
 * UI state. Widgets never show dialogs, so the stop-guard behaves differently there: when a
 * timer the user asked to stop is past the guard threshold, the widget path opens the app to the
 * timers strip instead (documented in docs/flows/new-app/widgets.md — QA reference).
 *
 * Reads are pure Room/DataStore snapshots — widgets never touch the UI layer.
 */
class QuickActionActions(
    private val timerRepository: TimerRepository,
    private val eventRepository: EventRepository,
    private val quickActionStore: QuickActionStore,
    private val childRepository: ChildRepository,
    private val sessionStore: SessionStore,
    private val serverClock: ServerClock,
) {

    /** The child widgets act on: persisted selection, falling back to the first child (spec §2). */
    suspend fun activeChildId(): String? =
        sessionStore.sessionFlow.firstOrNull()?.activeChildId
            ?: childRepository.children.firstOrNull()?.firstOrNull()?.id

    suspend fun runningTimer(kind: EventType, childId: String): RunningTimer? =
        timerRepository.findRunning(kind, childId)

    suspend fun startTimer(kind: EventType, childId: String): RunningTimer =
        timerRepository.start(kind, childId)

    suspend fun discardTimer(timer: RunningTimer) = timerRepository.discard(timer)

    /** True when stopping this timer in-app would run the >10s stop guard (spec §3). */
    fun stopGuardNeeded(timer: RunningTimer): Boolean =
        StopGuard.shouldConfirm(serverClock.nowMillis() - timer.startedAtMillis)

    suspend fun latestEvent(childId: String, type: EventType): Event? =
        eventRepository.latestByType(childId, type)
}
