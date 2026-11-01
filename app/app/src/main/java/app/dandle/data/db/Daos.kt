package app.dandle.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ChildDao {
    @Upsert
    suspend fun upsertAll(children: List<ChildEntity>)

    @Query("SELECT * FROM children ORDER BY firstName")
    fun observeAll(): Flow<List<ChildEntity>>

    @Query("SELECT COUNT(*) FROM children")
    suspend fun count(): Int

    /**
     * Cloud identity adoption (P1 fix QA 2026-10-31): rekeys the row's primary key from the
     * local UUID to the cloud record id after a successful push. No FK constraints reference
     * children.id (see Entities.kt), so a plain PK update is safe.
     */
    @Query("UPDATE children SET id = :to WHERE id = :from")
    suspend fun rekeyId(from: String, to: String)

    @Query("DELETE FROM children")
    suspend fun clear()
}

@Dao
interface EventDao {
    @Upsert
    suspend fun upsert(event: EventEntity)

    @Upsert
    suspend fun upsertAll(events: List<EventEntity>)

    @Query("SELECT * FROM events WHERE localUuid = :localUuid")
    suspend fun byLocalUuid(localUuid: String): EventEntity?

    @Query("SELECT * FROM events WHERE type = :type AND serverId = :serverId LIMIT 1")
    suspend fun byServerId(type: String, serverId: String): EventEntity?

    @Query("SELECT * FROM events WHERE childId = :childId AND deleted = 0 ORDER BY startMillis DESC")
    fun observeTimeline(childId: String): Flow<List<EventEntity>>

    /** One-shot timeline read — the write-boundary reconciliation query (see EventRepository). */
    @Query("SELECT * FROM events WHERE childId = :childId AND deleted = 0 ORDER BY startMillis DESC")
    suspend fun timeline(childId: String): List<EventEntity>

    @Query("SELECT COUNT(*) FROM events WHERE deleted = 0")
    fun observeCount(): Flow<Int>

    /** Newest same-type entry for a child — the duplicate guard's probe (spec §3.7). */
    @Query(
        "SELECT * FROM events WHERE childId = :childId AND type = :type AND deleted = 0 " +
            "ORDER BY endMillis DESC LIMIT 1"
    )
    suspend fun latestByType(childId: String, type: String): EventEntity?

    @Query("SELECT COUNT(*) FROM events WHERE syncState = 'FAILED' AND deleted = 0")
    fun observeFailedCount(): Flow<Int>

    /** Entries not yet on the server (PENDING + FAILED) — the session-expiry "N unsent" count. */
    @Query("SELECT COUNT(*) FROM events WHERE syncState != 'SYNCED' AND deleted = 0")
    fun observeUnsentCount(): Flow<Int>

    @Query("DELETE FROM events WHERE localUuid = :localUuid")
    suspend fun delete(localUuid: String)

    @Query("DELETE FROM events")
    suspend fun clear()

    @Query(
        "UPDATE events SET syncState = :syncState, failureReason = :failureReason, " +
            "conflictUiPath = :conflictUiPath, conflictServerId = :conflictServerId, " +
            "updatedAtMillis = :updatedAt WHERE localUuid = :localUuid"
    )
    suspend fun updateSyncState(
        localUuid: String,
        syncState: String,
        failureReason: String?,
        conflictUiPath: String?,
        conflictServerId: String?,
        updatedAt: Long,
    )

    @Query("UPDATE events SET syncState = :syncState, failureReason = NULL, " +
        "conflictUiPath = NULL, conflictServerId = NULL, updatedAtMillis = :updatedAt " +
        "WHERE localUuid = :localUuid")
    suspend fun markPending(localUuid: String, syncState: String, updatedAt: Long)

    /** Cloud identity adoption (P1 fix QA 2026-10-31): move rows to the adopted child id. */
    @Query("UPDATE events SET childId = :to WHERE childId = :from")
    suspend fun reassignChild(from: String, to: String)
}

@Dao
interface TimerDao {
    @Insert
    suspend fun insert(timer: TimerEntity): Long

    @Query("SELECT * FROM timers ORDER BY startedAtMillis DESC")
    fun observeAll(): Flow<List<TimerEntity>>

    @Query("SELECT * FROM timers WHERE id = :id")
    suspend fun byId(id: Long): TimerEntity?

    @Query("SELECT * FROM timers")
    suspend fun snapshot(): List<TimerEntity>

    @Query("SELECT * FROM timers WHERE childId = :childId AND kind = :kind LIMIT 1")
    suspend fun findRunning(childId: String, kind: String): TimerEntity?

    @Query("UPDATE timers SET serverTimerId = :serverTimerId WHERE id = :id")
    suspend fun setServerTimerId(id: Long, serverTimerId: String?)

    /** Cloud identity adoption (P1 fix QA 2026-10-31): move rows to the adopted child id. */
    @Query("UPDATE timers SET childId = :to WHERE childId = :from")
    suspend fun reassignChild(from: String, to: String)

    @Query("DELETE FROM timers WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM timers")
    suspend fun clear()
}

@Dao
interface OutboxDao {
    @Insert
    suspend fun enqueue(op: OutboxOpEntity): Long

    /** Oldest-first — this order IS the sync order (spec §6.1). */
    @Query("SELECT * FROM outbox ORDER BY id ASC")
    suspend fun snapshot(): List<OutboxOpEntity>

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun delete(id: Long)

    /** Drops a queued CREATE for an entry (replaced on edit of a never-synced entry). */
    @Query("DELETE FROM outbox WHERE localUuid = :localUuid AND opType = 'CREATE'")
    suspend fun deleteCreateFor(localUuid: String)

    /** Drops every queued op for an entry (used when a never-synced entry is deleted locally). */
    @Query("DELETE FROM outbox WHERE localUuid = :localUuid")
    suspend fun deleteFor(localUuid: String)

    @Query("UPDATE outbox SET attempts = attempts + 1, lastError = :error WHERE id = :id")
    suspend fun recordAttempt(id: Long, error: String?)

    @Query("SELECT COUNT(*) FROM outbox")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM outbox")
    suspend fun clear()
}
