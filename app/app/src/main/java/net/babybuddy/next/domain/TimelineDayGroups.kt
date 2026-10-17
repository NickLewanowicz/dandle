package net.babybuddy.next.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * Timeline day-grouping (spec §2, UX spec §2): groups are DEVICE-LOCAL calendar days — the same
 * zone the row timestamps and the day labels render in.
 *
 * Regression (P1-2): grouping used to be UTC-epoch-day (`millis / 86_400_000`) while the label
 * used the local zone — any device timezone offset from UTC split one local day into duplicate
 * headers (two "Today" groups) whenever the rows straddled UTC midnight.
 */
object TimelineDayGroups {

    fun localDate(epochMillis: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()

    /**
     * True when [millis] starts a NEW day group relative to [prevMillis] (null prev = first
     * row = always a header). Both instants are compared as LOCAL calendar days.
     */
    fun needsNewLocalDay(prevMillis: Long?, millis: Long, zone: ZoneId): Boolean {
        val date = localDate(millis, zone)
        if (prevMillis == null) return true
        return localDate(prevMillis, zone) != date
    }

    /** Day-group label: "Today" / "Yesterday" / locale date ("October 14"). */
    fun label(epochMillis: Long, zone: ZoneId, today: LocalDate): String {
        val date = localDate(epochMillis, zone)
        return when (date) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> date.month.getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + date.dayOfMonth
        }
    }
}
