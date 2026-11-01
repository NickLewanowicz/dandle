package app.dandle.data.backend

import app.dandle.data.db.ChildEntity
import app.dandle.data.db.EventEntity

/**
 * Server→device pull (brief 05 §1). Implementations return rows ready to merge into Room:
 * deterministic local ids (`serverLocalUuid`), `serverId` set, `syncState = SYNCED`.
 * Methods THROW [BackendError] on failure — callers translate to their own result types.
 */
interface PullGateway {
    suspend fun listChildren(): List<ChildEntity>

    /** Newest slice of all six event types (the dashboard/timeline working set). */
    suspend fun pullRecentEvents(limitPerType: Int): List<EventEntity>
}
