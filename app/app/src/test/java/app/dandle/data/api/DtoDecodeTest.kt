package app.dandle.data.api

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DTO decoding against response shapes lifted verbatim from docs/research/babybuddy-api.md §8
 * (the wire is law). The timer fixture includes the microsecond-precision timestamp the server
 * actually emits (§8.7) to prove it decodes as an opaque string without crashing.
 */
class DtoDecodeTest {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }

    @Test
    fun `decodes paginated children`() {
        val body = """
            {"count":2,"next":null,"previous":null,"results":[
              {"id":1,"first_name":"Emma","last_name":"Stone","birth_date":"2025-01-02","birth_time":"08:30","slug":"emma-stone"},
              {"id":2,"first_name":"Leo","last_name":"","birth_date":"2026-03-10","slug":"leo"}
            ]}
        """.trimIndent()
        val page = json.decodeFromString<PaginatedDto<ChildDto>>(body)
        assertEquals(2, page.count)
        assertNull(page.next)
        assertEquals("Emma", page.results[0].firstName)
        assertEquals("08:30", page.results[0].birthTime)
        assertEquals("leo", page.results[1].slug)
        assertNull(page.results[1].birthTime)
    }

    @Test
    fun `decodes diaper change (research doc 8_1)`() {
        val body = """
            {"id": 948, "child": 1, "time": "2026-10-16T08:30:00-04:00", "wet": true,
             "solid": false, "color": "yellow", "amount": 2.5, "notes": "seedy", "tags": ["morning"]}
        """.trimIndent()
        val dto = json.decodeFromString<ChangeDto>(body)
        assertEquals(948L, dto.id)
        assertEquals(1L, dto.child)
        assertEquals("2026-10-16T08:30:00-04:00", dto.time)
        assertTrue(dto.wet)
        assertTrue(!dto.solid)
        assertEquals("yellow", dto.color)
        assertEquals(2.5, dto.amount!!, 0.0001)
    }

    @Test
    fun `decodes feeding (research doc 8_2)`() {
        val body = """
            {"id": 51, "child": 1, "start": "2026-10-16T09:00:00-04:00",
             "end": "2026-10-16T09:15:00-04:00", "duration": "00:15:00", "type": "formula",
             "method": "bottle", "amount": 120.0, "notes": null, "tags": []}
        """.trimIndent()
        val dto = json.decodeFromString<FeedingDto>(body)
        assertEquals("formula", dto.type)
        assertEquals("bottle", dto.method)
        assertEquals(120.0, dto.amount!!, 0.0001)
        assertEquals("00:15:00", dto.duration)
    }

    @Test
    fun `decodes pumping (research doc 8_3)`() {
        val body = """
            {"id": 7, "child": 1, "amount": 90.0, "start": "2026-10-16T15:03:00-04:00",
             "end": "2026-10-16T15:22:00-04:00", "duration": "00:19:00", "notes": "left side", "tags": []}
        """.trimIndent()
        val dto = json.decodeFromString<PumpingDto>(body)
        assertEquals(90.0, dto.amount!!, 0.0001)
        assertEquals("00:19:00", dto.duration)
    }

    @Test
    fun `decodes note (research doc 8_4)`() {
        val body = """
            {"id": 12, "child": 1, "note": "First smile!", "image": null,
             "time": "2026-10-16T10:00:00-04:00", "tags": []}
        """.trimIndent()
        val dto = json.decodeFromString<NoteDto>(body)
        assertEquals("First smile!", dto.note)
        assertNull(dto.image)
    }

    @Test
    fun `decodes tummy time (research doc 8_5)`() {
        val body = """
            {"id": 163, "child": 1, "start": "2026-10-16T12:30:00-04:00",
             "end": "2026-10-16T12:35:30-04:00", "duration": "00:05:30",
             "milestone": "Rolled over.", "notes": null, "tags": []}
        """.trimIndent()
        val dto = json.decodeFromString<TummyTimeDto>(body)
        assertEquals("Rolled over.", dto.milestone)
        assertEquals("00:05:30", dto.duration)
    }

    @Test
    fun `decodes sleep with nap flag (research doc 8_6)`() {
        val body = """
            {"id": 481, "child": 1, "start": "2026-10-15T19:30:00-04:00",
             "end": "2026-10-15T23:00:00-04:00", "duration": "03:30:00", "nap": false,
             "notes": "used new swaddle", "tags": []}
        """.trimIndent()
        val dto = json.decodeFromString<SleepDto>(body)
        assertEquals(false, dto.nap)
        assertEquals("03:30:00", dto.duration)
    }

    @Test
    fun `decodes timer created via POST (research doc 8_7, microsecond Z timestamp)`() {
        val body = """
            {"id": 5, "child": 1, "name": null, "start": "2026-10-16T19:59:40.013914Z",
             "duration": null, "user": 1}
        """.trimIndent()
        val dto = json.decodeFromString<TimerDto>(body)
        assertEquals(5L, dto.id)
        assertEquals(1L, dto.child!!)
        assertNull(dto.name)
        assertEquals("2026-10-16T19:59:40.013914Z", dto.start)
    }

    @Test
    fun `decodes profile with api_key`() {
        val body = """
            {"user": {"id": 1, "username": "admin", "first_name": "", "last_name": "", "email": "", "is_staff": true},
             "language": "en-US", "timezone": "UTC",
             "api_key": "2h23807gd72h7hop382p98hd823dw3g665g56"}
        """.trimIndent()
        val dto = json.decodeFromString<ProfileDto>(body)
        assertEquals("2h23807gd72h7hop382p98hd823dw3g665g56", dto.apiKey)
        assertEquals("admin", dto.user!!.username)
    }

    @Test
    fun `timer-consumed sleep response decodes with fractional duration`() {
        val body = """
            {"id": 482, "child": 1, "start": "2026-10-16T19:59:40.013914Z",
             "end": "2026-10-16T21:31:13.549099Z", "duration": "01:31:33.535185",
             "nap": true, "notes": "timer flow", "tags": []}
        """.trimIndent()
        val dto = json.decodeFromString<SleepDto>(body)
        assertEquals("01:31:33.535185", dto.duration)
        assertEquals(true, dto.nap)
    }

    @Test
    fun `unknown server fields are ignored (no crash on forward-compatible payloads)`() {
        val body = """{"id": 1, "child": 2, "time": "2026-01-01T00:00:00Z", "wet": true, "solid": true, "some_future_field": {"x": 1}}"""
        val dto = json.decodeFromString<ChangeDto>(body)
        assertEquals(1L, dto.id)
        assertTrue(dto.wet)
    }

    @Test
    fun `write dto serializes exact server field names and omits nulls`() {
        val body = json.encodeToString(
            FeedingWriteDto(
                child = 1,
                start = "2026-10-16T09:00:00-04:00",
                end = "2026-10-16T09:15:00-04:00",
                type = "formula",
                method = "bottle",
                amount = null,
                notes = null,
            )
        )
        assertTrue(body.contains("\"child\":1"))
        assertTrue(body.contains("\"type\":\"formula\""))
        assertTrue(body.contains("\"method\":\"bottle\""))
        assertTrue(body.contains("\"start\":"))
        assertTrue(!body.contains("amount"))   // omitted when null (explicitNulls=false)
        assertTrue(!body.contains("notes"))
        assertTrue(!body.contains("duration")) // read-only field never sent
    }

    @Test
    fun `pumping write keeps required amount`() {
        val body = json.encodeToString(
            PumpingWriteDto(child = 1, start = "s", end = "e", amount = 0.0, notes = null)
        )
        assertTrue(body.contains("\"amount\":0.0"))
    }
}
