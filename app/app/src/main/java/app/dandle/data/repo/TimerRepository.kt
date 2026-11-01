package app.dandle.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import app.dandle.data.backend.BackendError
import app.dandle.data.backend.BackendException
import app.dandle.data.backend.TimerMirror
import app.dandle.data.db.BabyBuddyDatabase
import app.dandle.data.db.TimerEntity
import app.dandle.data.mapper.toModel
import app.dandle.domain.Clock
import app.dandle.domain.Iso8601
import app.dandle.domain.ServerClock
import app.dandle.domain.model.EventDraft
import app.dandle.domain.model.EventType
import app.dandle.domain.model.RunningTimer

/**
 * Timer semantics per spec §3: timers ALWAYS run locally (a Room row — survives process
 * death, works offline); when a [TimerMirror] is present (Baby Buddy), starting ALSO creates
 * the server timer named "{Type}-BBapp:<n>" so the web UI shows it (research doc §6), and
 * stopping/saving deletes it. Every mirror step is best-effort: offline start/stop simply
 * syncs the completed entry later. Backends without a web UI pass `null` — timers then live
 * purely on this device (brief 05 §1).
 */
class TimerRepository(
    private val timerMirror: TimerMirror?,
    private val db: BabyBuddyDatabase,
    private val eventRepository: EventRepository,
    private val clock: Clock,
    private val serverClock: ServerClock,
) {

    private val timerDao get() = db.timerDao()

    /** BbLog tag for timer lifecycle logs (see app.dandle.log.BbLog). */
    private val TAG = app.dandle.log.BbLog.TAG_TIMER

    fun observeRunning(): Flow<List<RunningTimer>> =
        timerDao.observeAll().map { rows -> rows.map { it.toModel() } }

    /** The running timer for this child+kind, or null (spec §3: never two of the same kind). */
    suspend fun findRunning(kind: EventType, childId: Long): RunningTimer? =
        timerDao.findRunning(childId, kind.name)?.toModel()

    /** One-shot snapshot for widget rendering (no Flow collection on Glance's main). */
    suspend fun snapshotRunning(): List<RunningTimer> =
        timerDao.snapshot().map { it.toModel() }

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
        app.dandle.log.BbLog.d(TAG, "start ${kind.name} child=$childId localId=$localId at=$startedAt")
        // Best-effort server timer (so it shows on the web); silent when offline.
        try {
            val created = timerMirror?.create(childId, kind, Iso8601.format(startedAt))
            created?.let { timerDao.setServerTimerId(localId, it) }
        } catch (_: Exception) {
            // Offline / unreachable: the timer keeps running locally; the eventual entry syncs.
        }
        return timerDao.byId(localId)!!.toModel()
    }

    /**
     * Stops the timer and saves the entry (start = timer start, end = stop time — spec §3).
     * T2: a panel "started X min ago" adjustment ([EventDraft.startAdjustMillis]) backdates the
     * START only — the user is correcting when tracking actually began; the stop time and the
     * resulting duration math are unchanged. The entry goes through the normal local-first
     * create path; the server timer (if any) is deleted best-effort. Use [discard] to stop
     * without saving.
     */
    suspend fun stopAndSave(timer: RunningTimer, draft: EventDraft): app.dandle.domain.model.Event {
        require(draft.type == timer.kind)
        val end = serverClock.nowMillis()
        val start = (timer.startedAtMillis - draft.startAdjustMillis).coerceAtMost(end)
        app.dandle.log.BbLog.d(
            TAG,
            "stop+save ${timer.kind.name} localId=${timer.localId} elapsed=${end - timer.startedAtMillis}ms " +
                "adjust=${draft.startAdjustMillis}ms",
        )
        val created = eventRepository.create(
            draft.copy(
                startMillis = start,
                endMillis = end.coerceAtLeast(timer.startedAtMillis),
            )
        )
        removeTimer(timer)
        return created
    }

    /** Stops without logging anything (the >10s "Discard" path — spec §3). */
    suspend fun discard(timer: RunningTimer) {
        app.dandle.log.BbLog.d(TAG, "discard ${timer.kind.name} localId=${timer.localId}")
        removeTimer(timer)
    }

    private suspend fun removeTimer(timer: RunningTimer) {
        timerDao.delete(timer.localId)
        timer.serverTimerId?.let { serverTimerId ->
            if (timerMirror == null) return
            try {
                timerMirror.delete(serverTimerId)
            } catch (e: BackendException) {
                if (e.error !is BackendError.ServerError) return // 4xx: timer already gone/owned elsewhere
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
        val mirror = timerMirror ?: return
        try {
            val serverAppTimers = mirror.listForChild(childId).filter { timer ->
                timer.name != null && EventType.entries.any { it.serverTimerName == timer.name }
            }
            val trackedServerIds = timerDao.snapshot().mapNotNull { it.serverTimerId }.toSet()

            for (timer in serverAppTimers) {
                if (timer.id in trackedServerIds) continue
                val kind = EventType.entries.first { it.serverTimerName == timer.name }
                app.dandle.log.BbLog.d(TAG, "restore ${kind.name} serverId=${timer.id}")
                timerDao.insert(
                    TimerEntity(
                        id = 0,
                        kind = kind.name,
                        childId = timer.childId ?: childId,
                        startedAtMillis = timer.startIso?.let { runCatching { Iso8601.parse(it) }.getOrDefault(clock.nowMillis()) }
                            ?: clock.nowMillis(),
                        serverTimerId = timer.id,
                        createdAtMillis = clock.nowMillis(),
                    )
                )
            }
            // Reconcile the other direction: a local timer that WAS synced but no longer exists
            // on the server was stopped elsewhere (another device, the web UI, a QA purge) — it
            // must not keep "running" here forever. Offline-created timers (no server id) are
            // untouched: they are still waiting to sync.
            val serverIds = serverAppTimers.map { it.id }.toSet()
            for (local in timerDao.snapshot()) {
                val serverId = local.serverTimerId ?: continue
                if (local.childId == childId && serverId !in serverIds) {
                    app.dandle.log.BbLog.d(TAG, "drop stale ${local.kind} serverId=$serverId (gone on server)")
                    timerDao.delete(local.id)
                }
            }
        } catch (_: Exception) {
            // Offline: local timers keep running; nothing to reconcile.
        }
    }
}
