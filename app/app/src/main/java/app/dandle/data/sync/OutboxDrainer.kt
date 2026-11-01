package app.dandle.data.sync

import kotlinx.serialization.json.Json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import app.dandle.data.backend.BackendError
import app.dandle.data.backend.PushResult
import app.dandle.data.backend.SyncPusher
import app.dandle.data.db.EventDao
import app.dandle.data.db.EventEntity
import app.dandle.data.db.OutboxDao
import app.dandle.data.db.OutboxOpEntity
import app.dandle.domain.Clock
import app.dandle.domain.model.SyncState
import app.dandle.domain.model.EventType

/**
 * The outbox drain engine (spec §6, brief 00 pattern #1, brief 05 §1). Pure JVM (no android.*)
 * so the whole sync contract is unit-testable; SyncWorker is a thin WorkManager shell around
 * [drain]. Transport lives behind [SyncPusher] — this class owns only QUEUE semantics.
 *
 * Guarantees:
 * - Ops drain oldest-first (outbox id order) — replay order matches user intent.
 * - One failure never blocks others: content rejections (Validation/other-4xx) mark the single
 *   entry FAILED and draining continues with the next op.
 * - Network loss stops the drain cleanly (remaining ops keep their order for next time) —
 *   WorkManager retries with exponential backoff under a CONNECTED constraint.
 * - Auth expiry pauses the whole queue via the session-expired flag; nothing is deleted;
 *   draining resumes after re-login.
 * - CREATE/EDIT/DELETE are idempotent-ish on replay: state is only advanced after the server
 *   acknowledges, and stale ops (target row already advanced) are dropped, not replayed.
 */
class OutboxDrainer(
    private val pusher: SyncPusher,
    private val json: Json,
    private val eventDao: EventDao,
    private val outboxDao: OutboxDao,
    private val clock: Clock,
    /** Reads the session-expired pause flag (SessionStore mirror). */
    private val isAuthPaused: () -> Boolean,
    /** Marks the session expired (DataStore) — called once, on the first auth failure. */
    private val onAuthExpired: suspend () -> Unit,
    /** Drain-activity signal for the header's SyncStatusChip (UX spec §5). */
    private val onDrainActiveChange: (Boolean) -> Unit = {},
    /** P1-3: localUuid of an entry the server just rejected (400-class) — proactive dialog. */
    private val onEntryFailed: (String) -> Unit = {},
) {

    /** BbLog tag for sync-engine logs (see app.dandle.log.BbLog). */
    private val TAG = app.dandle.log.BbLog.TAG_SYNC

    sealed interface Outcome {
        /** Every op either landed or terminally failed; outbox empty or fully processed. */
        data object Completed : Outcome

        /** Network lost mid-drain; remaining ops untouched (order preserved). */
        data object NetworkDown : Outcome

        /** Auth expired; queue paused (spec §6.3.6). */
        data object PausedAuth : Outcome
    }

    private val mutex = kotlinx.coroutines.sync.Mutex()

    /** Serialized drain: write-path one-shots and SyncWorker never double-post. */
    suspend fun drain(): Outcome = mutex.withLock {
        onDrainActiveChange(true)
        try {
            if (isAuthPaused()) {
                app.dandle.log.BbLog.d(TAG, "drain paused (auth expired)")
                return Outcome.PausedAuth
            }
            val ops = outboxDao.snapshot()
            app.dandle.log.BbLog.d(TAG, "drain start (${ops.size} queued)")
            val outcome = drainOnce()
            app.dandle.log.BbLog.d(TAG, "drain end: $outcome")
            return outcome
        } finally {
            onDrainActiveChange(false)
        }
    }

    private suspend fun drainOnce(): Outcome {
        for (op in outboxDao.snapshot()) {
            if (isAuthPaused()) return Outcome.PausedAuth
            val event = eventDao.byLocalUuid(op.localUuid)
            if (event == null && op.opType != OpTypes.DELETE) {
                // Target row vanished (deleted locally while queued); nothing to sync.
                outboxDao.delete(op.id)
                continue
            }
            app.dandle.log.BbLog.d(TAG, "dispatch #${op.id} ${op.opType} ${op.entityType}")
            val result = when (op.opType) {
                OpTypes.CREATE -> processCreate(op, event!!)
                OpTypes.EDIT -> processEdit(op, event)
                OpTypes.DELETE -> processDelete(op)
                else -> {
                    outboxDao.delete(op.id)
                    OpResult.Done
                }
            }
            when (result) {
                OpResult.Done -> Unit
                OpResult.Continue -> Unit
                OpResult.NetworkDown -> return Outcome.NetworkDown
                OpResult.Paused -> return Outcome.PausedAuth
            }
        }
        return Outcome.Completed
    }

    private object OpTypes {
        const val CREATE = "CREATE"
        const val EDIT = "EDIT"
        const val DELETE = "DELETE"
    }

    private enum class OpResult { Done, Continue, NetworkDown, Paused }

    // -- Op processors ---------------------------------------------------------

    private suspend fun processCreate(op: OutboxOpEntity, event: EventEntity): OpResult {
        if (event.serverId != null) {
            // Already created (e.g. crash between POST and op-delete); drop the stale op.
            outboxDao.delete(op.id)
            return OpResult.Done
        }
        val type = EventType.valueOf(op.entityType)
        val draft = OutboxPayloads.decodeDraft(json, type, op.payload)
        return when (val pushed = pusher.pushCreate(type, draft)) {
            is PushResult.Ok -> {
                eventDao.upsert(
                    event.copy(
                        serverId = pushed.serverId,
                        syncState = SyncState.SYNCED.name,
                        failureReason = null,
                        conflictUiPath = null,
                        conflictServerId = null,
                        updatedAtMillis = clock.nowMillis(),
                    )
                )
                outboxDao.delete(op.id)
                OpResult.Done
            }
            is PushResult.Deleted -> OpResult.Done // impossible for CREATE; kept for exhaustiveness
            is PushResult.Err -> handleError(op, pushed.error)
        }
    }

    private suspend fun processEdit(op: OutboxOpEntity, event: EventEntity?): OpResult {
        val serverId = OutboxPayloads.decodeServerId(json, op.payload)
        if (event == null || event.serverId != serverId) {
            // Entry deleted locally or its identity moved on; the queued edit is meaningless.
            outboxDao.delete(op.id)
            return OpResult.Done
        }
        val type = EventType.valueOf(op.entityType)
        val draft = OutboxPayloads.decodeDraft(json, type, op.payload)
        return when (val pushed = pusher.pushEdit(type, serverId, draft)) {
            is PushResult.Ok -> {
                eventDao.upsert(
                    event.copy(
                        syncState = SyncState.SYNCED.name,
                        failureReason = null,
                        conflictUiPath = null,
                        conflictServerId = null,
                        updatedAtMillis = clock.nowMillis(),
                    )
                )
                outboxDao.delete(op.id)
                OpResult.Done
            }
            is PushResult.Deleted -> OpResult.Done // impossible for EDIT; kept for exhaustiveness
            is PushResult.Err -> handleError(op, pushed.error)
        }
    }

    private suspend fun processDelete(op: OutboxOpEntity): OpResult {
        val serverId = OutboxPayloads.decodeServerId(json, op.payload)
        val type = EventType.valueOf(op.entityType)
        return when (val pushed = pusher.pushDelete(type, serverId)) {
            is PushResult.Deleted -> {
                // Deleted (or already gone — another device beat us to it).
                eventDao.delete(op.localUuid)
                outboxDao.delete(op.id)
                OpResult.Done
            }
            is PushResult.Ok -> OpResult.Done // impossible for DELETE; kept for exhaustiveness
            is PushResult.Err -> when (pushed.error) {
                // Deletes never fail an entry (historical Response-based behavior): record the
                // attempt and leave the op queued; only network loss stops the drain.
                is BackendError.Http, is BackendError.ServerError -> {
                    outboxDao.recordAttempt(op.id, "HTTP ${statusOf(pushed.error)}")
                    OpResult.Done
                }
                is BackendError.Unreachable -> OpResult.NetworkDown
                is BackendError.AuthExpired -> {
                    onAuthExpired()
                    OpResult.Paused
                }
                else -> OpResult.Done
            }
        }
    }

    private fun statusOf(error: BackendError): Int = when (error) {
        is BackendError.Http -> error.status
        is BackendError.ServerError -> error.status
        else -> 0
    }

    // -- Error mapping ----------------------------------------------------------

    private suspend fun handleError(op: OutboxOpEntity, error: BackendError): OpResult {
        return when (error) {
            is BackendError.AuthExpired -> {
                onAuthExpired()
                OpResult.Paused
            }
            is BackendError.Validation -> {
                // Server rejected the content — permanent for this payload. Badge the entry,
                // drop the op (spec §6.2: FAILED is user-resolvable; never blocks other entries).
                app.dandle.log.BbLog.d(
                    TAG,
                    "op #${op.id} ${op.opType} rejected: ${error.displayReason ?: error.rawBody?.take(200)}",
                )
                eventDao.updateSyncState(
                    localUuid = op.localUuid,
                    syncState = SyncState.FAILED.name,
                    failureReason = error.displayReason ?: error.rawBody?.take(500)
                        ?: "The server rejected this entry.",
                    conflictUiPath = error.conflictUiPath,
                    conflictServerId = error.conflictServerId,
                    updatedAt = clock.nowMillis(),
                )
                // Callback AFTER the row is marked (the UI reads the FAILED state in the callback).
                onEntryFailed(op.localUuid)
                outboxDao.delete(op.id)
                OpResult.Continue
            }
            is BackendError.NotFound -> {
                // Edit target is gone server-side: surface it and drop the doomed op.
                eventDao.updateSyncState(
                    localUuid = op.localUuid,
                    syncState = SyncState.FAILED.name,
                    failureReason = "This entry was deleted on the server.",
                    conflictUiPath = null,
                    conflictServerId = null,
                    updatedAt = clock.nowMillis(),
                )
                outboxDao.delete(op.id)
                OpResult.Continue
            }
            is BackendError.ServerError -> {
                outboxDao.recordAttempt(op.id, "HTTP ${error.status}")
                OpResult.Continue // server hiccup; keep draining the rest, retry later
            }
            is BackendError.Http -> {
                // Other 4xx (unexpected): terminally fail the entry like a 400.
                onEntryFailed(op.localUuid)
                eventDao.updateSyncState(
                    localUuid = op.localUuid,
                    syncState = SyncState.FAILED.name,
                    failureReason = "The server rejected this entry (HTTP ${error.status}).",
                    conflictUiPath = null,
                    conflictServerId = null,
                    updatedAt = clock.nowMillis(),
                )
                outboxDao.delete(op.id)
                OpResult.Continue
            }
            is BackendError.Unreachable -> OpResult.NetworkDown
        }
    }
}
