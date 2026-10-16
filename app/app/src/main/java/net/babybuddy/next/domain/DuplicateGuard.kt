package net.babybuddy.next.domain

import net.babybuddy.next.domain.model.Event
import net.babybuddy.next.domain.model.EventType

/**
 * Duplicate guard at save (functional spec §3.7): when the same type already has an entry for
 * this child within the last 4 minutes, show ONE calm line and let the user decide —
 * "Add anyway?" where **Add is the default action** and "View last entry" is secondary.
 * Never blocks, never silent-dedupes.
 *
 * Recency is judged on the entry's end instant (== the event time for instant types; the save
 * moment for timer types), which is what "logged N min ago" means to a parent.
 */
object DuplicateGuard {

    const val WINDOW_MILLIS: Long = 4 * 60_000L

    data class Hit(val message: String, val lastEvent: Event)

    /**
     * Non-null when [last] — the newest same-type entry for the child — was logged within
     * [windowMillis] of [nowMillis].
     */
    fun check(nowMillis: Long, last: Event?, windowMillis: Long = WINDOW_MILLIS): Hit? {
        if (last == null) return null
        val elapsed = nowMillis - last.endMillis
        if (elapsed < 0 || elapsed > windowMillis) return null
        return Hit(message = message(last, elapsed), lastEvent = last)
    }

    /** Calm one-liner, e.g. "Wet diaper logged 4 min ago. Add anyway?" */
    fun message(last: Event, elapsedMillis: Long): String {
        val minutes = elapsedMillis / 60_000
        val ago = if (minutes < 1) "just now" else "$minutes min ago"
        return "${nounFor(last)} logged $ago. Add anyway?"
    }

    /** The noun the dialog opens with; diaper entries name the wet/solid combination. */
    fun nounFor(event: Event): String = when (event.type) {
        EventType.CHANGE -> when {
            event.wet == true && event.solid != true -> "Wet diaper"
            event.solid == true && event.wet != true -> "Solid diaper"
            else -> "Diaper change"
        }
        EventType.FEEDING -> "Feeding"
        EventType.SLEEP -> "Sleep"
        EventType.NOTE -> "Note"
        EventType.PUMPING -> "Pumping"
        EventType.TUMMY_TIME -> "Tummy time"
    }
}
