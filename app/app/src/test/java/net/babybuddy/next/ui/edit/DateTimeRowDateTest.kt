package net.babybuddy.next.ui.edit

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The Material DatePicker reports the selected day as UTC-noon-based millis.
 * The picked LOCAL calendar date must be that UTC date — a UTC-midnight
 * round-trip combined with a local `atTime().atZone()` shifted the day by one
 * for evening entries (2026-10-19 regression: "Today 20:03" EDT, picking
 * 19:05, produced TOMORROW 19:05 and validation refused the save).
 */
class DateTimeRowDateTest {

    @Test
    fun `picker millis map to the same calendar date as the local picker showed`() {
        // A selection made while viewing Oct 18 (any instant of that LOCAL day).
        val oct18LocalNoonEdt = Instant.parse("2026-10-18T16:00:00Z") // 12:00 EDT
        assertEquals(LocalDate.of(2026, 10, 18), localDateFromDatePickerMillis(oct18LocalNoonEdt.toEpochMilli()))
    }

    @Test
    fun `picker millis at the UTC-noon anchor map to that date`() {
        val utcNoonOct18 = Instant.parse("2026-10-18T12:00:00Z")
        assertEquals(LocalDate.of(2026, 10, 18), localDateFromDatePickerMillis(utcNoonOct18.toEpochMilli()))
    }

    @Test
    fun `evening entry plus picked time stays on the same LOCAL day`() {
        val zone = ZoneId.systemDefault()
        // "Today 20:03" EDT on Oct 18 (20:03 EDT == Oct 19 00:03 UTC — the rollover case).
        val todayEvening = Instant.parse("2026-10-19T00:03:00Z").toEpochMilli()
        val pickedDate = localDateFromDatePickerMillis(utcNoonOf(LocalDate.of(2026, 10, 18)))
        val combined = pickedDate.atTime(19, 5).atZone(zone).toInstant().toEpochMilli()
        assertEquals(
            "an hour-earlier pick must stay on Oct 18, not roll to Oct 19",
            Instant.parse("2026-10-18T23:05:00Z").toEpochMilli(),
            combined,
        )
    }

    private fun utcNoonOf(date: LocalDate): Long =
        date.atStartOfDay(ZoneId.of("UTC")).plusHours(12).toInstant().toEpochMilli()
}
