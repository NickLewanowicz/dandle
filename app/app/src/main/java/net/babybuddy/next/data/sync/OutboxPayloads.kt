package net.babybuddy.next.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.babybuddy.next.domain.model.EventDraft
import net.babybuddy.next.domain.model.EventType
import net.babybuddy.next.data.mapper.toChangeWrite
import net.babybuddy.next.data.mapper.toFeedingWrite
import net.babybuddy.next.data.mapper.toNoteWrite
import net.babybuddy.next.data.mapper.toPumpingWrite
import net.babybuddy.next.data.mapper.toSleepWrite
import net.babybuddy.next.data.mapper.toTummyTimeWrite

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
