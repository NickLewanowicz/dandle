package app.dandle.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import app.dandle.domain.Iso8601
import app.dandle.domain.model.EventDraft
import app.dandle.domain.model.EventType
import app.dandle.data.api.ChangeWriteDto
import app.dandle.data.api.FeedingWriteDto
import app.dandle.data.api.NoteWriteDto
import app.dandle.data.api.PumpingWriteDto
import app.dandle.data.api.SleepWriteDto
import app.dandle.data.api.TummyTimeWriteDto
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

    /**
     * Rebuilds the draft a stored CREATE/EDIT payload carries (brief 05 phase 2): the drainer
     * hands drafts to the [app.dandle.data.backend.SyncPusher], which re-encodes them per
     * backend. Round-trips through the write DTOs, so replayed request bodies stay
     * byte-identical to the day they were queued.
     */
    fun decodeDraft(json: Json, type: EventType, payload: String): EventDraft {
        val body = decodeBody(json, payload)
        return when (type) {
            EventType.FEEDING -> json.decodeFromJsonElement(FeedingWriteDto.serializer(), body).let {
                EventDraft(
                    type = type, childId = it.child,
                    startMillis = Iso8601.parse(it.start), endMillis = Iso8601.parse(it.end),
                    feedingType = it.type, feedingMethod = it.method, amount = it.amount,
                    noteText = it.notes,
                )
            }
            EventType.CHANGE -> json.decodeFromJsonElement(ChangeWriteDto.serializer(), body).let {
                EventDraft(
                    type = type, childId = it.child,
                    startMillis = it.time?.let(Iso8601::parse) ?: 0L,
                    endMillis = it.time?.let(Iso8601::parse) ?: 0L,
                    amount = it.amount, wet = it.wet, solid = it.solid, color = it.color,
                    noteText = it.notes,
                )
            }
            EventType.SLEEP -> json.decodeFromJsonElement(SleepWriteDto.serializer(), body).let {
                EventDraft(
                    type = type, childId = it.child,
                    startMillis = Iso8601.parse(it.start), endMillis = Iso8601.parse(it.end),
                    noteText = it.notes,
                )
            }
            EventType.NOTE -> json.decodeFromJsonElement(NoteWriteDto.serializer(), body).let {
                EventDraft(
                    type = type, childId = it.child,
                    startMillis = Iso8601.parse(it.time), endMillis = Iso8601.parse(it.time),
                    noteText = it.note,
                )
            }
            EventType.PUMPING -> json.decodeFromJsonElement(PumpingWriteDto.serializer(), body).let {
                EventDraft(
                    type = type, childId = it.child,
                    startMillis = Iso8601.parse(it.start), endMillis = Iso8601.parse(it.end),
                    amount = it.amount, noteText = it.notes,
                )
            }
            EventType.TUMMY_TIME -> json.decodeFromJsonElement(TummyTimeWriteDto.serializer(), body).let {
                EventDraft(
                    type = type, childId = it.child,
                    startMillis = Iso8601.parse(it.start), endMillis = Iso8601.parse(it.end),
                    milestone = it.milestone, noteText = it.notes,
                )
            }
        }
    }
}
