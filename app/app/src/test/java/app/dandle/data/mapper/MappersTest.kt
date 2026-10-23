package app.dandle.data.mapper

import kotlinx.serialization.json.Json
import app.dandle.data.api.ChangeDto
import app.dandle.data.api.FeedingDto
import app.dandle.data.api.NoteDto
import app.dandle.domain.Iso8601
import app.dandle.domain.model.SyncState
import app.dandle.domain.model.EventDraft
import app.dandle.domain.model.EventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mapper round-trips + write-body shapes (server field names are law — research doc §4). */
class MappersTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }

    @Test
    fun `feeding dto maps to a SYNCED entity with parsed epoch times`() {
        val dto = json.decodeFromString(
            FeedingDto.serializer(),
            """{"id": 51, "child": 1, "start": "2026-10-16T09:00:00-04:00", "end": "2026-10-16T09:15:00-04:00",
                "duration": "00:15:00", "type": "formula", "method": "bottle", "amount": 120.0, "notes": null, "tags": []}""",
        )
        val entity = dto.toEntity()
        assertEquals("server:FEEDING:51", entity.localUuid)
        assertEquals(51L, entity.serverId)
        assertEquals(EventType.FEEDING.name, entity.type)
        assertEquals(SyncState.SYNCED.name, entity.syncState)
        assertEquals(Iso8601.parse("2026-10-16T09:00:00-04:00"), entity.startMillis)
        assertEquals(Iso8601.parse("2026-10-16T09:15:00-04:00"), entity.endMillis)
        assertEquals("formula", entity.feedingType)
        assertEquals(120.0, entity.amount!!, 0.0001)
    }

    @Test
    fun `change dto maps instant times with end equals start`() {
        val dto = json.decodeFromString(
            ChangeDto.serializer(),
            """{"id": 948, "child": 2, "time": "2026-10-16T08:30:00-04:00", "wet": true, "solid": false,
                "color": "yellow", "amount": 2.5, "notes": "seedy", "tags": []}""",
        )
        val entity = dto.toEntity()
        assertEquals(entity.startMillis, entity.endMillis)
        assertEquals(true, entity.wet)
        assertEquals(false, entity.solid)
        assertEquals("yellow", entity.color)
        assertEquals(EventType.CHANGE.name, entity.type)
    }

    @Test
    fun `note draft write always carries time (PATCH must not re-stamp)`() {
        val draft = EventDraft(
            type = EventType.NOTE,
            childId = 1,
            startMillis = Iso8601.parse("2026-10-16T10:00:00Z"),
            endMillis = Iso8601.parse("2026-10-16T10:00:00Z"),
            noteText = "First smile!",
        )
        val body = json.encodeToString(draft.toNoteWrite())
        assertTrue(body.contains("\"time\":"))
        assertTrue(body.contains("\"note\":\"First smile!\""))
        assertTrue(body.contains("\"child\":1"))
    }

    @Test
    fun `feeding draft write carries required type and method and omits nulls`() {
        val draft = EventDraft(
            type = EventType.FEEDING,
            childId = 1,
            startMillis = Iso8601.parse("2026-10-16T13:00:00Z"),
            endMillis = Iso8601.parse("2026-10-16T13:15:00Z"),
            feedingType = "breast milk",
            feedingMethod = "left breast",
        )
        val body = json.encodeToString(draft.toFeedingWrite())
        assertTrue(body.contains("\"type\":\"breast milk\""))
        assertTrue(body.contains("\"method\":\"left breast\""))
        assertTrue(!body.contains("\"amount\"")) // null omitted
        assertTrue(!body.contains("\"notes\""))
    }

    @Test
    fun `pumping draft defaults amount to zero (server-required)`() {
        val draft = EventDraft(
            type = EventType.PUMPING,
            childId = 1,
            startMillis = 0,
            endMillis = 60_000,
        )
        val body = json.encodeToString(draft.toPumpingWrite())
        assertTrue(body.contains("\"amount\":0.0"))
    }

    @Test
    fun `entity round-trips through toModel`() {
        val dto = json.decodeFromString(
            NoteDto.serializer(),
            """{"id": 12, "child": 1, "note": "First smile!", "image": null,
                "time": "2026-10-16T10:00:00-04:00", "tags": []}""",
        )
        val model = dto.toEntity().toModel()
        assertEquals(EventType.NOTE, model.type)
        assertEquals("First smile!", model.noteText)
        assertEquals(SyncState.SYNCED, model.syncState)
        assertNull(model.serverId?.let { null } ?: model.failureReason)
        assertEquals(model.startMillis, model.endMillis)
    }
}
