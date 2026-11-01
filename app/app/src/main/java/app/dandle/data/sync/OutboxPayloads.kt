package app.dandle.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
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
 * Outbox payload codec (brief 05 §3). Payloads are BACKEND-NEUTRAL drafts — the adapter
 * encodes its own wire body at push time, so a LOCAL draft (UUID child ids) never touches
 * the Baby Buddy wire shapes and vice versa:
 * - CREATE: {"v":2,"draft":{…}},
 * - EDIT:   {"serverId":"…","body":{"v":2,"draft":{…}}},
 * - DELETE: {"serverId":"…"}.
 * Legacy payloads (raw Baby Buddy write-DTO JSON from pre-05 queues) still decode; their
 * requests replay byte-identically through the BabyBuddy pusher's draft round-trip.
 */
object OutboxPayloads {

    /** Backend-neutral draft payload. Numeric identity? None — ids stay strings everywhere. */
    @kotlinx.serialization.Serializable
    private data class DraftPayload(
        val childId: String,
        val start: Long,
        val end: Long,
        val feedingType: String? = null,
        val feedingMethod: String? = null,
        val amount: Double? = null,
        val wet: Boolean? = null,
        val solid: Boolean? = null,
        val color: String? = null,
        val noteText: String? = null,
        val milestone: String? = null,
        val startAdjust: Long = 0,
    )

    fun encodeBody(json: Json, draft: EventDraft): String = buildJsonObject {
        put("v", JsonPrimitive(2))
        put(
            "draft",
            json.encodeToJsonElement(
                DraftPayload.serializer(),
                DraftPayload(
                    childId = draft.childId,
                    start = draft.startMillis,
                    end = draft.endMillis,
                    feedingType = draft.feedingType,
                    feedingMethod = draft.feedingMethod,
                    amount = draft.amount,
                    wet = draft.wet,
                    solid = draft.solid,
                    color = draft.color,
                    noteText = draft.noteText,
                    milestone = draft.milestone,
                    startAdjust = draft.startAdjustMillis,
                ),
            ),
        )
    }.toString()

    fun encodeEdit(json: Json, serverId: String, draft: EventDraft): String =
        buildJsonObject {
            put("serverId", JsonPrimitive(serverId))
            put("body", json.parseToJsonElement(encodeBody(json, draft)))
        }.toString()

    fun encodeDelete(serverId: String): String =
        buildJsonObject { put("serverId", JsonPrimitive(serverId)) }.toString()

    /**
     * Tolerates both encodings: pre-migration ops queued numeric Baby Buddy ids; new ops
     * queue backend-native strings (brief 05 §3).
     */
    fun decodeServerId(json: Json, payload: String): String =
        json.parseToJsonElement(payload).jsonObject["serverId"]!!.jsonPrimitive.content

    /** CREATE payloads: the body is the payload. EDIT payloads: unwrap the "body" member. */
    fun decodeBody(json: Json, payload: String): JsonObject {
        val obj = json.parseToJsonElement(payload).jsonObject
        return (obj["body"] as? JsonObject) ?: obj
    }

    /**
     * Rebuilds the draft a stored CREATE/EDIT payload carries (brief 05 §3): the drainer
     * hands drafts to the [app.dandle.data.backend.SyncPusher], which encodes them per
     * backend. v2 payloads parse directly; legacy write-DTO payloads round-trip through the
     * BabyBuddy shapes so replayed request bodies stay byte-identical to the day queued.
     */
    fun decodeDraft(json: Json, type: EventType, payload: String): EventDraft {
        val obj = json.parseToJsonElement(payload).jsonObject
        val body = (obj["body"] as? JsonObject) ?: obj
        val draftElement = body["draft"]
        if (draftElement is JsonObject) {
            val d = json.decodeFromJsonElement(DraftPayload.serializer(), draftElement)
            return EventDraft(
                type = type,
                childId = d.childId,
                startMillis = d.start,
                endMillis = d.end,
                feedingType = d.feedingType,
                feedingMethod = d.feedingMethod,
                amount = d.amount,
                wet = d.wet,
                solid = d.solid,
                color = d.color,
                noteText = d.noteText,
                milestone = d.milestone,
                startAdjustMillis = d.startAdjust,
            )
        }
        return decodeLegacyDraft(json, type, body)
    }

    /** Pre-05 payloads: raw Baby Buddy write-DTO JSON (numeric ids, ISO stamps). */
    private fun decodeLegacyDraft(json: Json, type: EventType, body: JsonObject): EventDraft {
        return when (type) {
            EventType.FEEDING -> json.decodeFromJsonElement(FeedingWriteDto.serializer(), body).let {
                EventDraft(
                    type = type, childId = it.child.toString(),
                    startMillis = Iso8601.parse(it.start), endMillis = Iso8601.parse(it.end),
                    feedingType = it.type, feedingMethod = it.method, amount = it.amount,
                    noteText = it.notes,
                )
            }
            EventType.CHANGE -> json.decodeFromJsonElement(ChangeWriteDto.serializer(), body).let {
                EventDraft(
                    type = type, childId = it.child.toString(),
                    startMillis = it.time?.let(Iso8601::parse) ?: 0L,
                    endMillis = it.time?.let(Iso8601::parse) ?: 0L,
                    amount = it.amount, wet = it.wet, solid = it.solid, color = it.color,
                    noteText = it.notes,
                )
            }
            EventType.SLEEP -> json.decodeFromJsonElement(SleepWriteDto.serializer(), body).let {
                EventDraft(
                    type = type, childId = it.child.toString(),
                    startMillis = Iso8601.parse(it.start), endMillis = Iso8601.parse(it.end),
                    noteText = it.notes,
                )
            }
            EventType.NOTE -> json.decodeFromJsonElement(NoteWriteDto.serializer(), body).let {
                EventDraft(
                    type = type, childId = it.child.toString(),
                    startMillis = Iso8601.parse(it.time), endMillis = Iso8601.parse(it.time),
                    noteText = it.note,
                )
            }
            EventType.PUMPING -> json.decodeFromJsonElement(PumpingWriteDto.serializer(), body).let {
                EventDraft(
                    type = type, childId = it.child.toString(),
                    startMillis = Iso8601.parse(it.start), endMillis = Iso8601.parse(it.end),
                    amount = it.amount, noteText = it.notes,
                )
            }
            EventType.TUMMY_TIME -> json.decodeFromJsonElement(TummyTimeWriteDto.serializer(), body).let {
                EventDraft(
                    type = type, childId = it.child.toString(),
                    startMillis = Iso8601.parse(it.start), endMillis = Iso8601.parse(it.end),
                    milestone = it.milestone, noteText = it.notes,
                )
            }
        }
    }
}
