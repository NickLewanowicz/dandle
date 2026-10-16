package net.babybuddy.next.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import net.babybuddy.next.data.api.ApiException
import net.babybuddy.next.data.api.BabyBuddyApi
import net.babybuddy.next.data.db.BabyBuddyDatabase
import net.babybuddy.next.data.db.EventEntity
import net.babybuddy.next.data.db.OutboxDao
import net.babybuddy.next.data.db.OutboxOpEntity
import net.babybuddy.next.data.mapper.toEntity
import net.babybuddy.next.data.mapper.toModel
import net.babybuddy.next.data.sync.OutboxDrainer
import net.babybuddy.next.data.sync.OutboxPayloads
import net.babybuddy.next.domain.Clock
import net.babybuddy.next.domain.model.SyncState
import net.babybuddy.next.domain.model.Event
import net.babybuddy.next.domain.model.EventDraft
import net.babybuddy.next.domain.model.EventType
import retrofit2.HttpException
import java.util.UUID

/**
 * Local-first event store (spec §6.1): every write applies to Room IMMEDIATELY and enqueues
 * an outbox op; a one-shot drain attempt follows (cheap if offline — the op just stays queued).
 * The UI only ever reads Room Flows.
 */
class EventRepository(
    private val apiProvider: () -> BabyBuddyApi,
    private val db: BabyBuddyDatabase,
    private val json: kotlinx.serialization.json.Json,
    private val clock: Clock,
    private val drainer: OutboxDrainer,
    /** Best-effort one-shot drain trigger after writes (e.g. SyncWorker.enqueue). */
    private val requestDrain: suspend () -> Unit,
) {

    private val eventDao get() = db.eventDao()
    private val outboxDao get() = db.outboxDao()

    fun observeTimeline(childId: Long): Flow<List<Event>> =
        eventDao.observeTimeline(childId).map { rows -> rows.map { it.toModel() } }

    /** Newest same-type entry for the child — the duplicate guard's probe (spec §3.7). */
    suspend fun latestByType(childId: Long, type: EventType): Event? =
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
            val api = apiProvider()
            val fetched = buildList {
                addAll(api.listFeedings(limit = limitPerType, ordering = "-end").results.map { it.toEntity() })
                addAll(api.listChanges(limit = limitPerType, ordering = "-time").results.map { it.toEntity() })
                addAll(api.listSleep(limit = limitPerType, ordering = "-end").results.map { it.toEntity() })
                addAll(api.listNotes(limit = limitPerType, ordering = "-time").results.map { it.toEntity() })
                addAll(api.listPumping(limit = limitPerType, ordering = "-end").results.map { it.toEntity() })
                addAll(api.listTummyTimes(limit = limitPerType, ordering = "-start").results.map { it.toEntity() })
            }
            val merged = fetched.mapNotNull { incoming ->
                val existing = eventDao.byServerId(incoming.type, incoming.serverId!!)
                when {
                    existing == null -> incoming
                    SyncState.valueOf(existing.syncState) == SyncState.SYNCED -> incoming.copy(
                        localUuid = existing.localUuid,
                        createdAtMillis = existing.createdAtMillis,
                        updatedAtMillis = existing.updatedAtMillis,
                    )
                    // PENDING/FAILED local edit wins over the (stale) server copy.
                    else -> null
                }
            }
            eventDao.upsertAll(merged)
            RefreshResult.Success
        } catch (e: ApiException) {
            if (e.status == 401 || e.status == 403) {
                RefreshResult.Failure(RefreshResult.Reason.AUTH_EXPIRED)
            } else {
                RefreshResult.Failure(RefreshResult.Reason.HTTP_ERROR)
            }
        } catch (e: HttpException) {
            RefreshResult.Failure(RefreshResult.Reason.HTTP_ERROR)
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
