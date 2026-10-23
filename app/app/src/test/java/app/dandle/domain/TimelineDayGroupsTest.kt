package app.dandle.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * P1-2 regression: day groups must follow the DEVICE-LOCAL calendar day. The old UTC-epoch-day
 * grouping split a single local day into duplicate "Today" headers whenever the rows straddled
 * UTC midnight (exactly the QA-emulator state: device TZ -04:00, rows 2026-10-17T03:44Z and
 * 2026-10-16T19:46Z — both "October 16" locally, different UTC days).
 */
class TimelineDayGroupsTest {

    private val zone: ZoneId = ZoneId.of("America/New_York") // -04:00 in October
    private val today: LocalDate = LocalDate.of(2026, 10, 16)

    private fun utc(iso: String): Long = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    // Spans UTC midnight (Oct 17 vs Oct 16 UTC) while both are the SAME local day (Oct 16).
    private val lateEveningUtcNextDay = utc("2026-10-17T03:44:00Z") // local Oct 16 23:44
    private val sameDayEvening = utc("2026-10-16T19:46:00Z")       // local Oct 16 15:46
    private val previousDayEvening = utc("2026-10-15T22:46:00Z")   // local Oct 15 18:46

    @Test
    fun `rows spanning UTC midnight but on one local day do NOT start a new group`() {
        // UTC days differ — the old grouping fired a duplicate header exactly here.
        assertFalse(
            TimelineDayGroups.needsNewLocalDay(lateEveningUtcNextDay, sameDayEvening, zone),
        )
        assertEquals("Today", TimelineDayGroups.label(lateEveningUtcNextDay, zone, today))
        assertEquals("Today", TimelineDayGroups.label(sameDayEvening, zone, today))
    }

    @Test
    fun `a real local-day change starts a new group`() {
        assertTrue(
            TimelineDayGroups.needsNewLocalDay(previousDayEvening, sameDayEvening, zone),
        )
        assertEquals("Today", TimelineDayGroups.label(sameDayEvening, zone, today))
        assertEquals(
            "Yesterday",
            TimelineDayGroups.label(previousDayEvening, zone, today),
        )
    }

    @Test
    fun `first row always starts a group`() {
        assertTrue(TimelineDayGroups.needsNewLocalDay(null, sameDayEvening, zone))
    }

    @Test
    fun `labels cover today yesterday and locale dates`() {
        assertEquals(
            "Beyond yesterday stays a date label",
            "October 13",
            TimelineDayGroups.label(utc("2026-10-13T19:46:00Z"), zone, today),
        )
        assertEquals(
            "Yesterday",
            TimelineDayGroups.label(previousDayEvening, zone, today),
        )
        val label = TimelineDayGroups.label(sameDayEvening, zone, today.plusDays(3))
        assertTrue("date label: $label", label.matches(Regex("""\w+ \d+""")))
    }
}
