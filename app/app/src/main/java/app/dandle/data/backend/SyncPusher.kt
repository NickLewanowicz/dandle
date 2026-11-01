package app.dandle.data.backend

import app.dandle.domain.model.EventDraft
import app.dandle.domain.model.EventType

/**
 * Transport for outbox ops (brief 05 §1): the drainer owns queue semantics, the backend
 * adapter owns encoding + wire errors. Implementations MUST map every failure onto
 * [BackendError] — no HTTP types may escape.
 */
interface SyncPusher {
    suspend fun pushCreate(type: EventType, draft: EventDraft): PushResult
    suspend fun pushEdit(type: EventType, serverId: String, draft: EventDraft): PushResult

    /**
     * 404 on delete counts as success ([PushResult.Deleted]) — another client may have beaten
     * us to it (spec §6.2). Auth failures do NOT special-case here: a delete's non-2xx is
     * reported as [BackendError.Http]; the drainer keeps the op queued (matching the historical
     * Response-based behavior where deletes never paused the queue).
     */
    suspend fun pushDelete(type: EventType, serverId: String): PushResult
}
