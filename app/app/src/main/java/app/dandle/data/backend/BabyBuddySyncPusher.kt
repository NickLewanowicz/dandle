package app.dandle.data.backend

import kotlinx.serialization.json.Json
import app.dandle.data.api.ApiException
import app.dandle.data.api.BabyBuddyApi
import app.dandle.data.mapper.toChangeWrite
import app.dandle.data.mapper.toFeedingWrite
import app.dandle.data.mapper.toNoteWrite
import app.dandle.data.mapper.toPumpingWrite
import app.dandle.data.mapper.toSleepWrite
import app.dandle.data.mapper.toTummyTimeWrite
import app.dandle.domain.model.EventDraft
import app.dandle.domain.model.EventType

/**
 * Baby Buddy transport for the outbox (brief 05 phase 2). Mechanically extracted from
 * OutboxDrainer's dispatch `when` blocks; every DRF/BabyBuddy wire detail lives here —
 * the write-DTO encoding, the 403-message auth sniff, and the 400-body conflict parse —
 * and nothing but [PushResult]/[BackendError] escapes.
 */
class BabyBuddySyncPusher(
    private val apiProvider: () -> BabyBuddyApi,
    private val json: Json,
) : SyncPusher {

    override suspend fun pushCreate(type: EventType, draft: EventDraft): PushResult =
        wrap {
            // The Baby Buddy wire keeps numeric pks; the boundary stringifies (brief 05 §3).
            val id = when (type) {
                EventType.FEEDING -> apiProvider().createFeeding(draft.toFeedingWrite()).id
                EventType.CHANGE -> apiProvider().createChange(draft.toChangeWrite()).id
                EventType.SLEEP -> apiProvider().createSleep(draft.toSleepWrite()).id
                EventType.NOTE -> apiProvider().createNote(draft.toNoteWrite()).id
                EventType.PUMPING -> apiProvider().createPumping(draft.toPumpingWrite()).id
                EventType.TUMMY_TIME -> apiProvider().createTummyTime(draft.toTummyTimeWrite()).id
            }
            PushResult.Ok(id.toString())
        }

    override suspend fun pushEdit(type: EventType, serverId: String, draft: EventDraft): PushResult =
        wrap {
            val numeric = serverId.toLong()
            when (type) {
                EventType.FEEDING -> apiProvider().patchFeeding(numeric, draft.toFeedingWrite())
                EventType.CHANGE -> apiProvider().patchChange(numeric, draft.toChangeWrite())
                EventType.SLEEP -> apiProvider().patchSleep(numeric, draft.toSleepWrite())
                EventType.NOTE -> apiProvider().patchNote(numeric, draft.toNoteWrite())
                EventType.PUMPING -> apiProvider().patchPumping(numeric, draft.toPumpingWrite())
                EventType.TUMMY_TIME -> apiProvider().patchTummyTime(numeric, draft.toTummyTimeWrite())
            }
            PushResult.Ok(serverId)
        }

    override suspend fun pushDelete(type: EventType, serverId: String): PushResult =
        wrap {
            // Response-returning endpoints never throw on non-2xx; map codes directly
            // (deletes deliberately don't map 401 to AuthExpired — see SyncPusher doc).
            val numeric = serverId.toLong()
            val response = when (type) {
                EventType.FEEDING -> apiProvider().deleteFeeding(numeric)
                EventType.CHANGE -> apiProvider().deleteChange(numeric)
                EventType.SLEEP -> apiProvider().deleteSleep(numeric)
                EventType.NOTE -> apiProvider().deleteNote(numeric)
                EventType.PUMPING -> apiProvider().deletePumping(numeric)
                EventType.TUMMY_TIME -> apiProvider().deleteTummyTime(numeric)
            }
            when {
                response.isSuccessful || response.code() == 404 -> PushResult.Deleted
                else -> PushResult.Err(BackendError.Http(response.code(), null))
            }
        }

    private inline fun wrap(block: () -> PushResult): PushResult = try {
        block()
    } catch (e: ApiException) {
        PushResult.Err(e.toBackendError())
    } catch (e: retrofit2.HttpException) {
        PushResult.Err(ApiException.from(e).toBackendError())
    } catch (e: java.io.IOException) {
        PushResult.Err(BackendError.Unreachable)
    }

    private fun ApiException.toBackendError(): BackendError = when {
        isAuthFailure(this) -> BackendError.AuthExpired
        status == 400 -> BackendError.Validation(
            rawBody = rawBody,
            displayReason = validation?.displayReason,
            conflictUiPath = validation?.conflictingEntry?.uiPath,
            conflictServerId = validation?.conflictingEntry?.serverId,
        )
        status == 404 -> BackendError.NotFound
        status in 500..599 -> BackendError.ServerError(status)
        else -> BackendError.Http(status, rawBody)
    }

    /**
     * DRF returns 403 (not 401) for missing/invalid tokens (research doc §2) — but 403 also
     * means "permission denied" (e.g. caregiver delete), which must NOT pause the queue.
     * Distinguish by the server's own message.
     */
    private fun isAuthFailure(e: ApiException): Boolean {
        if (e.status == 401) return true
        if (e.status != 403) return false
        val body = e.rawBody.orEmpty()
        return listOf("Invalid token", "access has expired", "credentials were not provided")
            .any { body.contains(it, ignoreCase = true) }
    }
}
