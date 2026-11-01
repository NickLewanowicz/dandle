package app.dandle.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import app.dandle.data.backend.BackendError
import app.dandle.data.backend.BackendException
import app.dandle.data.backend.PullGateway
import app.dandle.data.db.BabyBuddyDatabase
import app.dandle.data.db.EventEntity
import app.dandle.data.db.OutboxDao
import app.dandle.data.db.OutboxOpEntity
import app.dandle.data.mapper.toModel
import app.dandle.data.sync.OutboxDrainer
import app.dandle.data.sync.OutboxPayloads
import app.dandle.domain.Clock
import app.dandle.domain.model.SyncState
import app.dandle.domain.model.Event
import app.dandle.domain.model.EventDraft
import app.dandle.domain.model.EventType
import java.util.UUID

/**
 * Local-first event store (spec §6.1): every write applies to Room IMMEDIATELY and enqueues
 * an outbox op; a one-shot drain attempt follows (cheap if offline — the op just stays queued).
 * The UI only ever reads Room Flows. Server pull goes through [PullGateway] — this class has
 * no idea which backend is active (brief 05 §1).
 */
class EventRepository(
    private val puller: PullGateway,
    private val db: BabyBuddyDatabase,
    private val json: kotlinx.serialization.json.Json,
    private val clock: Clock,
    private val drainer: OutboxDrainer,
    /** Best-effort one-shot drain trigger after writes (e.g. SyncWorker.enqueue). */
    private val requestDrain: suspend () -> Unit,
    /** CLOUD merges by LWW + tombstones; server-authoritative backends overwrite SYNCED rows. */
    private val lwwMerge: () -> Boolean = { false },
) {

    private val eventDao get() = db.eventDao()
    private val outboxDao get() = db.outboxDao()

    /**
     * T1: local write-boundary signal for the timeline. Room's own invalidation is the primary
     * reactivity path, but on device we observed the timeline flow silently stop delivering fresh
     * results (suspend reads on the same database stayed fresh — duplicate guard saw the new row,
     * the flow did not; child-switch/restart resubscribed and "fixed" it). Robolectric cannot
     * reproduce it (journal mode/connection pool differ), so the UI must not depend on that
     * signal alone: every local write nudges a re-query through this flow.
     */
    val timelineWrites = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(
        extraBufferCapacity = 16,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    /** Fire-and-forget write marker; safe from any thread/coroutine. */
    fun nudgeTimeline() {
        app.dandle.log.BbLog.d("BB/SYNC", "timeline nudge")
        timelineWrites.tryEmit(Unit)
    }

    /**
     * The timeline is UI source of truth: Room's invalidated-observed flow PLUS a re-query on
     * every local write boundary (create/edit/delete/retry/refresh/drain). Duplicate emissions
     * are harmless — the uiState StateFlow dedupes structurally.
     */
    fun observeTimeline(childId: String): Flow<List<Event>> =
        kotlinx.coroutines.flow.merge(
            eventDao.observeTimeline(childId),
            timelineWrites.map { eventDao.timeline(childId) },
        ).map { rows -> rows.map { it.toModel() } }

    /** Newest same-type entry for the child — the duplicate guard's probe (spec §3.7). */
    suspend fun latestByType(childId: String, type: EventType): Event? =
        eventDao.latestByType(childId, type.name)?.toModel()

    /** One event by local id (Quick Edit sheet open — also covers FAILED rows). */
    suspend fun event(localUuid: String): Event? = eventDao.byLocalUuid(localUuid)?.toModel()

    fun observeFailedCount(): Flow<Int> = eventDao.observeFailedCount()

    fun observeUnsentCount(): Flow<Int> = eventDao.observeUnsentCount()

    fun observeEventCount(): Flow<Int> = eventDao.observeCount()

    // -- Writes -----------------------------------------------------------------

    /** Creates locally (PENDING) + outbox CREATE + drain attempt. Returns the local event. */
    suspend fun create(draft: EventDraft): Event {
        val now = clock.nowMillis()
        val entity = EventEntity(
            localUuid = UUID.randomUUID().toString(),
            serverId = null,
            type = draft.type.name,
            childId = draft.childId,
            startMillis = draft.startMillis,
            endMillis = if (draft.type.isTimed) draft.endMillis else draft.startMillis,
            feedingType = draft.feedingType,
            feedingMethod = draft.feedingMethod,
            amount = draft.amount,
            wet = draft.wet,
            solid = draft.solid,
            color = draft.color,
            noteText = draft.noteText,
            milestone = draft.milestone,
            syncState = SyncState.PENDING.name,
            failureReason = null,
            conflictUiPath = null,
            conflictServerId = null,
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        eventDao.upsert(entity)
        outboxDao.enqueue(
            OutboxOpEntity(
                opType = "CREATE",
                entityType = draft.type.name,
                localUuid = entity.localUuid,
                payload = OutboxPayloads.encodeBody(json, draft),
                createdAtMillis = now,
            )
        )
        drainInBackground()
        nudgeTimeline()
        return entity.toModel()
    }

    /**
     * Applies the edit locally and queues the sync:
     * - synced entries (serverId != null) → PENDING + EDIT op,
     * - never-synced entries (offline creates, incl. FAILED ones — spec §6.2 "Edit") →
     *   PENDING + fresh CREATE op with the updated body (their previous CREATE op is replaced).
     */
    suspend fun edit(localUuid: String, draft: EventDraft) {
        val existing = eventDao.byLocalUuid(localUuid) ?: return
        val now = clock.nowMillis()
        val updated = existing.copy(
            startMillis = draft.startMillis,
            endMillis = if (draft.type.isTimed) draft.endMillis else draft.startMillis,
            feedingType = draft.feedingType,
            feedingMethod = draft.feedingMethod,
            amount = draft.amount,
            wet = draft.wet,
            solid = draft.solid,
            color = draft.color,
            noteText = draft.noteText,
            milestone = draft.milestone,
            syncState = SyncState.PENDING.name,
            failureReason = null,
            conflictUiPath = null,
            conflictServerId = null,
            updatedAtMillis = now,
        )
        eventDao.upsert(updated)
        val serverId = existing.serverId
        // A stale CREATE op is replaced (its payload no longer matches the edited row).
        outboxDao.deleteCreateFor(updated.localUuid)
        outboxDao.enqueue(
            OutboxOpEntity(
                opType = if (serverId == null) "CREATE" else "EDIT",
                entityType = draft.type.name,
                localUuid = updated.localUuid,
                payload = if (serverId == null) {
                    OutboxPayloads.encodeBody(json, draft)
                } else {
                    OutboxPayloads.encodeEdit(json, serverId, draft)
                },
                createdAtMillis = now,
            )
        )
        drainInBackground()
        nudgeTimeline()
    }

    /**
     * Delete (spec §4.2): never-synced entries (incl. FAILED creates) are removed locally only;
     * synced entries are removed locally + queued DELETE for the server.
     */
    suspend fun delete(localUuid: String) {
        val existing = eventDao.byLocalUuid(localUuid) ?: return
        eventDao.delete(localUuid)
        val serverId = existing.serverId
        if (serverId == null) {
            // Never synced: drop any queued op together with the row (spec §4.2 "local only").
            outboxDao.deleteFor(localUuid)
        } else {
            outboxDao.enqueue(
                OutboxOpEntity(
                    opType = "DELETE",
                    entityType = existing.type,
                    localUuid = localUuid,
                    payload = OutboxPayloads.encodeDelete(serverId),
                    createdAtMillis = clock.nowMillis(),
                )
            )
        }
        drainInBackground()
        nudgeTimeline()
    }

    /** FAILED entry resolution "Retry" (spec §6.2): re-queue and go PENDING. */
    suspend fun retry(localUuid: String) {
        val existing = eventDao.byLocalUuid(localUuid) ?: return
        val now = clock.nowMillis()
        val draft = existing.toDraft()
        eventDao.markPending(localUuid, SyncState.PENDING.name, now)
        outboxDao.enqueue(
            OutboxOpEntity(
                opType = if (existing.serverId == null) "CREATE" else "EDIT",
                entityType = existing.type,
                localUuid = localUuid,
                payload = if (existing.serverId == null) {
                    OutboxPayloads.encodeBody(json, draft)
                } else {
                    OutboxPayloads.encodeEdit(json, existing.serverId, draft)
                },
                createdAtMillis = now,
            )
        )
        drainInBackground()
        nudgeTimeline()
    }

    // -- Server refresh -----------------------------------------------------------

    sealed interface RefreshResult {
        data object Success : RefreshResult
        data class Failure(val reason: Reason) : RefreshResult

        enum class Reason { UNREACHABLE, AUTH_EXPIRED, HTTP_ERROR }
    }

    /**
     * Pulls the newest slice of all six types into Room. Local-first rule: PENDING/FAILED rows
     * are never overwritten by server data (their truth is ahead of or diverged from the server).
     */
    suspend fun refresh(limitPerType: Int = 50): RefreshResult {
        return try {
            val fetched = puller.pullRecentEvents(limitPerType)
            val lww = lwwMerge()
            val merged = fetched.mapNotNull { incoming ->
                val existing = eventDao.byServerId(incoming.type, incoming.serverId!!)
                when {
                    // Cloud tombstone: adopt it (hide the row locally) wherever the record lives.
                    lww && incoming.deleted -> incoming.copy(
                        localUuid = existing?.localUuid ?: incoming.localUuid,
                        createdAtMillis = existing?.createdAtMillis ?: 0,
                    )
                    existing == null -> incoming
                    // PENDING/FAILED local edit wins over the (stale) server copy.
                    SyncState.valueOf(existing.syncState) != SyncState.SYNCED -> null
                    // Cloud LWW: the newer client write wins (server-clock-adjusted stamps).
                    lww && existing.updatedAtMillis > incoming.updatedAtMillis -> null
                    SyncState.valueOf(existing.syncState) == SyncState.SYNCED -> incoming.copy(
                        localUuid = existing.localUuid,
                        createdAtMillis = existing.createdAtMillis,
                        updatedAtMillis = existing.updatedAtMillis,
                    )
                    else -> null
                }
            }
            eventDao.upsertAll(merged)
            nudgeTimeline() // server pull changed local rows — re-render even if invalidation is lost
            RefreshResult.Success
        } catch (e: BackendException) {
            when (e.error) {
                is BackendError.Unreachable -> RefreshResult.Failure(RefreshResult.Reason.UNREACHABLE)
                is BackendError.AuthExpired -> RefreshResult.Failure(RefreshResult.Reason.AUTH_EXPIRED)
                else -> RefreshResult.Failure(RefreshResult.Reason.HTTP_ERROR)
            }
        } catch (e: java.io.IOException) {
            RefreshResult.Failure(RefreshResult.Reason.UNREACHABLE)
        }
    }

    suspend fun clear() {
        // Sign-out (spec §1.2): local cache + queued ops are wiped together; the server keeps
        // everything that already synced.
        eventDao.clear()
        db.outboxDao().clear()
    }

    private suspend fun drainInBackground() {
        // One-shot best-effort; failures are the SyncWorker's problem (backoff + connectivity).
        try {
            requestDrain()
        } catch (_: Exception) {
            // never let a drain trigger break the write path
        }
    }
}

/** Rebuilds an editable draft from a stored row (used by retry() and Stage 2's Quick Edit). */
fun EventEntity.toDraft(): EventDraft = EventDraft(
    type = EventType.valueOf(type),
    childId = childId,
    startMillis = startMillis,
    endMillis = endMillis,
    feedingType = feedingType,
    feedingMethod = feedingMethod,
    amount = amount,
    wet = wet,
    solid = solid,
    color = color,
    noteText = noteText,
    milestone = milestone,
)
