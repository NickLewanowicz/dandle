package net.babybuddy.next.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.babybuddy.next.data.api.ApiException
import net.babybuddy.next.data.api.BabyBuddyApi
import net.babybuddy.next.data.api.TimerWriteDto
import net.babybuddy.next.data.db.BabyBuddyDatabase
import net.babybuddy.next.data.db.TimerEntity
import net.babybuddy.next.data.mapper.toEntity
import net.babybuddy.next.data.mapper.toModel
import net.babybuddy.next.domain.Clock
import net.babybuddy.next.domain.Iso8601
import net.babybuddy.next.domain.ServerClock
import net.babybuddy.next.domain.model.EventDraft
import net.babybuddy.next.domain.model.EventType
import net.babybuddy.next.domain.model.RunningTimer

/**
 * Timer semantics per spec §3: timers ALWAYS run locally (a Room row — survives process
 * death, works offline); when online, starting ALSO creates the server timer named
 * "{Type}-BBapp:<n>" so the web UI shows it (research doc §6), and stopping/saving deletes it.
 * Every server step is best-effort: offline start/stop simply syncs the completed entry later.
 */
class TimerRepository(
    private val apiProvider: () -> BabyBuddyApi,
    private val db: BabyBuddyDatabase,
    private val eventRepository: EventRepository,
    private val clock: Clock,
    private val serverClock: ServerClock,
) {

    private val timerDao get() = db.timerDao()

    fun observeRunning(): Flow<List<RunningTimer>> =
        timerDao.observeAll().map { rows -> rows.map { it.toModel() } }

    /** The running timer for this child+kind, or null (spec §3: never two of the same kind). */
    suspend fun findRunning(kind: EventType, childId: Long): RunningTimer? =
        timerDao.findRunning(childId, kind.name)?.toModel()

    /**
     * Starts a timer for [kind] + [childId]. Local row first; server timer created when the
     * call succeeds (id attached to the row). A duplicate running timer for the same child+kind
     * returns the existing one (spec §3: tapping focuses the running timer, never duplicates).
     */
    suspend fun start(kind: EventType, childId: Long): RunningTimer {
        require(kind.isTimed) { "${kind.name} is not a timer type" }
        timerDao.findRunning(childId, kind.name)?.let { return it.toModel() }

        val startedAt = serverClock.nowMillis()
        val localId = timerDao.insert(
            TimerEntity(
                id = 0,
                kind = kind.name,
                childId = childId,
                startedAtMillis = startedAt,
                serverTimerId = null,
                createdAtMillis = clock.nowMillis(),
            )
        )
        // Best-effort server timer (so it shows on the web); silent when offline.
        try {
            val created = apiProvider().createTimer(
                TimerWriteDto(
                    child = childId,
                    name = kind.serverTimerName,
                    start = Iso8601.format(startedAt),
                )
            )
            timerDao.setServerTimerId(localId, created.id)
        } catch (_: Exception) {
            // Offline / unreachable: the timer keeps running locally; the eventual entry syncs.
        }
        return timerDao.byId(localId)!!.toModel()
    }

    /**
     * Stops the timer and saves the entry (start = timer start, end = stop time — spec §3).
     * The entry goes through the normal local-first create path; the server timer (if any) is
     * deleted best-effort. Use [discard] to stop without saving.
     */
    suspend fun stopAndSave(timer: RunningTimer, draft: EventDraft) {
        require(draft.type == timer.kind)
        val end = serverClock.nowMillis()
        eventRepository.create(
            draft.copy(
                startMillis = timer.startedAtMillis,
                endMillis = end.coerceAtLeast(timer.startedAtMillis),
            )
        )
        removeTimer(timer)
    }

    /** Stops without logging anything (the >10s "Discard" path — spec §3). */
    suspend fun discard(timer: RunningTimer) {
        removeTimer(timer)
    }

    private suspend fun removeTimer(timer: RunningTimer) {
        timerDao.delete(timer.localId)
        timer.serverTimerId?.let { serverTimerId ->
            try {
                val response = apiProvider().deleteTimer(serverTimerId)
                check(response.isSuccessful || response.code() == 404)
            } catch (e: ApiException) {
                if (e.status !in 500..599) return // 4xx: timer already gone/owned elsewhere — nothing to clean
                throw e
            } catch (_: java.io.IOException) {
                // Offline: the server timer lingers; the entry already carries the times, and a
                // leftover "-BBapp:" timer is reconciled away by refresh() on next launch.
            }
        }
    }

    /**
     * Reconciles server-side "-BBapp:" timers into local rows (app relaunch, process death,
     * or a timer started from another device with this app's naming). Local rows win; server
     * timers we don't track get adopted so they can still be stopped/saved.
     */
    suspend fun refresh(childId: Long) {
        try {
            val page = apiProvider().listTimers(child = childId, limit = 100)
            val serverAppTimers = page.results.filter { timer ->
                timer.name != null && EventType.entries.any { it.serverTimerName == timer.name }
            }
            val trackedServerIds = timerDao.snapshot().mapNotNull { it.serverTimerId }.toSet()

            for (timer in serverAppTimers) {
                if (timer.id in trackedServerIds) continue
                val kind = EventType.entries.first { it.serverTimerName == timer.name }
                timerDao.insert(
                    TimerEntity(
                        id = 0,
                        kind = kind.name,
                        childId = timer.child ?: childId,
                        startedAtMillis = timer.start?.let { runCatching { Iso8601.parse(it) }.getOrDefault(clock.nowMillis()) }
                            ?: clock.nowMillis(),
                        serverTimerId = timer.id,
                        createdAtMillis = clock.nowMillis(),
                    )
                )
            }
        } catch (_: Exception) {
            // Offline: local timers keep running; nothing to reconcile.
        }
    }
}
