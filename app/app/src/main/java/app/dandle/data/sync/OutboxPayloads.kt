package app.dandle.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import app.dandle.domain.model.EventDraft
import app.dandle.domain.model.EventType
import app.dandle.data.mapper.toChangeWrite
import app.dandle.data.mapper.toFeedingWrite
import app.dandle.data.mapper.toNoteWrite
import app.dandle.data.mapper.toPumpingWrite
import app.dandle.data.mapper.toSleepWrite
import app.dandle.data.mapper.toTummyTimeWrite

/**
 * Outbox payload codec. Payloads store the exact request body that will be replayed, so a
 * queued CREATE from last week posts byte-identical JSON today:
 * - CREATE: the write DTO JSON,
 * - EDIT:   {"serverId": n, "body": {…write DTO…}},
 * - DELETE: {"serverId": n}.
 */
object OutboxPayloads {

    fun encodeBody(json: Json, draft: EventDraft): String = when (draft.type) {
        EventType.FEEDING -> json.encodeToString(draft.toFeedingWrite())
        EventType.CHANGE -> json.encodeToString(draft.toChangeWrite())
        EventType.SLEEP -> json.encodeToString(draft.toSleepWrite())
        EventType.NOTE -> json.encodeToString(draft.toNoteWrite())
        EventType.PUMPING -> json.encodeToString(draft.toPumpingWrite())
        EventType.TUMMY_TIME -> json.encodeToString(draft.toTummyTimeWrite())
    }

    fun encodeEdit(json: Json, serverId: Long, draft: EventDraft): String =
        buildJsonObject {
            put("serverId", JsonPrimitive(serverId))
            put("body", json.parseToJsonElement(encodeBody(json, draft)))
        }.toString()

    fun encodeDelete(serverId: Long): String =
        buildJsonObject { put("serverId", JsonPrimitive(serverId)) }.toString()

    fun decodeServerId(json: Json, payload: String): Long =
        json.parseToJsonElement(payload).jsonObject["serverId"]!!.jsonPrimitive.content.toLong()

    /** CREATE payloads: the body is the payload. EDIT payloads: unwrap the "body" member. */
    fun decodeBody(json: Json, payload: String): JsonObject {
        val obj = json.parseToJsonElement(payload).jsonObject
        return (obj["body"] as? JsonObject) ?: obj
    }
}
