package app.dandle.data.backend

import app.dandle.domain.model.EventDraft
import app.dandle.domain.model.EventType

/**
 * On-device backend transports (brief 05 §4): there is no server, so "sync" completes
 * instantly and locally.
 *
 * [LocalSyncPusher] mints a fresh id per CREATE — the row's serverId must be non-null once
 * synced or every later edit would re-take the CREATE branch and duplicate (brief 05 §2's
 * load-bearing invariant). Crash-recovery stays correct: the drainer drops a stale CREATE
 * whose row already carries a serverId.
 *
 * [LocalPuller] returns nothing to merge — Room is already the only truth; refresh() then
 * reports Success with no error path (a LOCAL install never shows "offline").
 */
class LocalSyncPusher : SyncPusher {
    override suspend fun pushCreate(type: EventType, draft: EventDraft): PushResult =
        PushResult.Ok(java.util.UUID.randomUUID().toString())

    override suspend fun pushEdit(type: EventType, serverId: String, draft: EventDraft): PushResult =
        PushResult.Ok(serverId)

    override suspend fun pushDelete(type: EventType, serverId: String): PushResult =
        PushResult.Deleted
}

class LocalPuller : PullGateway {
    override suspend fun listChildren(): List<app.dandle.data.db.ChildEntity> = emptyList()
    override suspend fun pullRecentEvents(limitPerType: Int): List<app.dandle.data.db.EventEntity> = emptyList()
}
