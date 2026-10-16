package net.babybuddy.next.domain

/**
 * Duration/elapsed formats, app-wide (functional spec §4.1, UX spec §2):
 * - Active timer counters: `HH:MM:ss` (tabular figures; the most glanceable element in the app).
 * - Timeline durations: `45s` under a minute, `12m` under an hour, `1h 05m` above.
 * Pure Kotlin; deterministic by construction (no clock reads).
 */
object ElapsedTimeFormatter {

    /** Live timer counter format, e.g. `00:47:12`. Hours are 2+ digits, never wrap. */
    fun timerCounter(millis: Long): String {
        val totalSeconds = millis.coerceAtLeast(0) / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }

    /** Compact timeline duration, e.g. `45s`, `12m`, `1h 05m`. Sub-minute truncates to seconds. */
    fun durationShort(millis: Long): String {
        val totalSeconds = millis.coerceAtLeast(0) / 1000
        return when {
            totalSeconds < 60 -> "${totalSeconds}s"
            totalSeconds < 3600 -> "${totalSeconds / 60}m"
            else -> {
                val h = totalSeconds / 3600
                val m = (totalSeconds % 3600) / 60
                "%dh %02dm".format(h, m)
            }
        }
    }
}
