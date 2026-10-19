package net.babybuddy.next.widget

import net.babybuddy.next.domain.model.Event
import net.babybuddy.next.domain.model.EventType
import net.babybuddy.next.domain.model.RunningTimer

/**
 * DB → widget-state mapping (spec §7, UX spec §6). Pure function of Room snapshots so the whole
 * widget presentation is unit-testable: minute-granular "time since" (Xm / Xh / Xd with
 * future-skew clamping), durations, per-type details, and running-timer accents.
 */
object WidgetStateMapper {

    /** One button of the QuickActions widget. */
    data class QuickActionSlot(
        val type: EventType,
        val label: String,
        val running: Boolean,
        /** Minute-granular elapsed ("12m") when this slot's timer is running, else null. */
        val elapsedText: String?,
        /**
         * U10: subdued "last activity" line under the label when idle — "45m", "1h30m", "2d"
         * since the newest event of this type for this child. Null when there is no history.
         */
        val lastText: String?,
    )

    data class RunningTimerState(val kind: EventType, val elapsedText: String)

    /**
     * T7 (Priya): a timer running for ANOTHER child — "Zoe's nap · 32m". Isolation of data is
     * right; isolation of awareness is a forgotten-nap factory.
     */
    data class CrossChildTimerState(
        val kind: EventType,
        val childFirstName: String,
        val elapsedText: String,
    )

    /**
     * One row of the CareDashboard widget. timeText is a CLOCK time (or a start–end
     * range for long timed entries) — relative "3h" rots between widget refreshes and
     * under-reports age (Priya, persona round 2).
     */
    data class CareRow(
        val type: EventType,
        val label: String,
        val timeText: String,
        val detailText: String?,
    )

    data class CareDashboardState(
        val childName: String? = null,
        /** "Asleep since 13:05 · 1h 10m" — the first thing an at-work parent checks. */
        val runningSince: RunningSinceState? = null,
        val rows: List<CareRow>,
        val running: RunningTimerState?,
        /** T7: quiet cross-child running line; null = nothing to show. */
        val crossChild: CrossChildTimerState? = null,
    )

    /** The running timer phrased as STATE ("Asleep since 13:05"), not an action. */
    data class RunningSinceState(
        val kind: EventType,
        val sinceText: String,
        val elapsedText: String,
    )

    /** Short on-button labels (UX spec §6: Sleep / Bottle / Diaper / Pump). */
    fun slotLabel(type: EventType): String = when (type) {
        EventType.FEEDING -> "Bottle"
        EventType.CHANGE -> "Diaper"
        EventType.SLEEP -> "Sleep"
        EventType.NOTE -> "Note"
        EventType.PUMPING -> "Pump"
        EventType.TUMMY_TIME -> "Tummy"
    }

    /**
     * The four QuickActions slots (spec §7.1) with running-state accents and the U10 "last"
     * line: a running slot swaps to the type-color accent with the elapsed counter; idle slots
     * whisper when their type last happened ([latest] = newest event per type for this child).
     */
    fun quickActionSlots(
        configured: List<EventType>,
        timers: List<RunningTimer>,
        childId: Long,
        nowMillis: Long,
        latest: Map<EventType, net.babybuddy.next.domain.model.Event> = emptyMap(),
    ): List<QuickActionSlot> = configured.map { type ->
        val running = timers.firstOrNull { it.kind == type && it.childId == childId }
        QuickActionSlot(
            type = type,
            label = slotLabel(type),
            running = running != null,
            elapsedText = running?.let { elapsedMinutesText(nowMillis - it.startedAtMillis) },
            lastText = latest[type]
                ?.takeIf { running == null }
                ?.let { compactSince(nowMillis - it.endMillis) },
        )
    }

    /**
     * U10's compact "time since" for the widget's subdued last-line: "45m", "1h30m", "2d".
     * Same minute-granular contract as [sinceText] but keeps minutes under an hour so
     * "1h30m" reads as one unit instead of the app-side "1h 30m".
     */
    fun compactSince(elapsedMillis: Long): String {
        val minutes = (elapsedMillis / 60_000L).coerceAtLeast(0)
        val hours = minutes / 60
        val days = hours / 24
        return when {
            hours >= 24 -> "${days}d"
            hours >= 1 -> "${hours}h${(minutes % 60).toString().padStart(2, '0')}m"
            else -> "${minutes}m"
        }
    }

    /**
     * CareDashboard rows (spec §7.2): last event per type, newest activity first per row,
     * plus the running-timer row when one is active. [visible] filters configured rows;
     * [childName] feeds the widget header (UX-review W8).
     */
    fun careDashboard(
        events: List<Event>,
        timers: List<RunningTimer>,
        childId: Long,
        nowMillis: Long,
        visible: Set<EventType>,
        childName: String? = null,
    ): CareDashboardState {
        val latestByType = visible.associateWith { type ->
            events.filter { it.type == type && it.childId == childId }
                .maxByOrNull { it.endMillis }
        }
        val rows = visible.mapNotNull { type ->
            val event = latestByType[type] ?: return@mapNotNull null
            CareRow(
                type = type,
                label = rowLabel(type),
                timeText = entryTimeText(event, nowMillis),
                detailText = detailText(event),
            )
        }
        val running = timers.firstOrNull { it.childId == childId }?.let {
            RunningTimerState(it.kind, elapsedMinutesText(nowMillis - it.startedAtMillis))
        }
        val runningSince = timers.firstOrNull { it.childId == childId }?.let {
            RunningSinceState(
                kind = it.kind,
                sinceText = clockTime(it.startedAtMillis),
                elapsedText = elapsedMinutesText(nowMillis - it.startedAtMillis),
            )
        }
        return CareDashboardState(childName = childName, runningSince = runningSince, rows = rows, running = running)
    }

    fun rowLabel(type: EventType): String = when (type) {
        EventType.FEEDING -> "Last feed"
        EventType.CHANGE -> "Last diaper"
        EventType.SLEEP -> "Last nap"
        EventType.PUMPING -> "Last pump"
        EventType.NOTE -> "Last note"
        EventType.TUMMY_TIME -> "Last tummy time"
    }

    /** Friendly noun for the cross-child line — "Zoe's [nap]" (T7). */
    fun friendlyNoun(type: EventType): String = when (type) {
        EventType.FEEDING -> "feed"
        EventType.CHANGE -> "diaper change"
        EventType.SLEEP -> "nap"
        EventType.NOTE -> "note"
        EventType.PUMPING -> "pump"
        EventType.TUMMY_TIME -> "tummy time"
    }

    /**
     * T7: the first timer running for a child OTHER than [childId], phrased as
     * "[firstName]'s [noun]" + minute-granular elapsed ("Zoe's nap", "32m"). Null when no
     * other-child timer runs (the line must not render). Unknown child name degrades to
     * "Another child" rather than lying about whose it is.
     */
    fun crossChildTimer(
        timers: List<RunningTimer>,
        childId: Long,
        otherChildFirstName: String?,
        nowMillis: Long,
    ): CrossChildTimerState? {
        val other = timers.firstOrNull { it.childId != childId } ?: return null
        return CrossChildTimerState(
            kind = other.kind,
            childFirstName = otherChildFirstName?.ifBlank { null } ?: "Another child",
            elapsedText = elapsedMinutesText(nowMillis - other.startedAtMillis),
        )
    }

    /**
     * "12m ago" · amount for feed/pump, wet/solid for diaper, duration for nap (spec §7.2).
     * Amount ≤ 0 reads as unknown (UX-review W9): the known failed-sync pump with amount 0
     * must not present "0 mL" as data. Kept in lockstep with [EventHeadline].
     */
    fun detailText(event: Event): String? = when (event.type) {
        EventType.FEEDING, EventType.PUMPING ->
            event.amount?.takeIf { it > 0.0 }?.let { amount -> "${trimNumber(amount)} mL" }
        EventType.CHANGE -> when {
            event.wet == true && event.solid == true -> "Wet + Solid"
            event.wet == true -> "Wet"
            event.solid == true -> "Solid"
            else -> null
        }
        // Sleep/tummy: the start–end range in timeText IS the duration — a separate
        // "40m" next to it was redundant (and "0m · 0m" read as broken data, Priya).
        EventType.SLEEP, EventType.TUMMY_TIME, EventType.NOTE -> null
    }

    /**
     * Clock time of an entry's END (or a start–end range when it ran >= 60 min or
     * crossed local midnight) — the owner's timeline tweak, mirrored here. Clock
     * times stay true even when the widget is minutes-stale; "3h" under-reports.
     */
    fun entryTimeText(event: Event, nowMillis: Long): String {
        val zone = java.time.ZoneId.systemDefault()
        val end = java.time.Instant.ofEpochMilli(event.endMillis).atZone(zone)
        val start = java.time.Instant.ofEpochMilli(event.startMillis).atZone(zone)
        val crossesMidnight = start.toLocalDate() != end.toLocalDate()
        return if (event.type.isTimed && (event.endMillis - event.startMillis >= 3_600_000 || crossesMidnight)) {
            "${clockTime(event.startMillis)}–${clockTime(event.endMillis)}"
        } else {
            clockTime(event.endMillis)
        }
    }

    /** "13:05" in the device's zone (24h, matching the in-app timeline). */
    fun clockTime(epochMillis: Long): String =
        java.time.format.DateTimeFormatter.ofPattern("HH:mm")
            .format(java.time.Instant.ofEpochMilli(epochMillis).atZone(java.time.ZoneId.systemDefault()))

    /**
     * Minute-granular "time since" (UX spec §6: counters are minute-granular; staleness is
     * acceptable and documented). Negative elapsed (server/device clock skew) clamps to 0m.
     */
    fun sinceText(elapsedMillis: Long): String {
        val minutes = (elapsedMillis / 60_000L).coerceAtLeast(0)
        val hours = minutes / 60
        val days = hours / 24
        return when {
            hours >= 24 -> "${days}d"
            hours >= 1 -> "${hours}h"
            else -> "${minutes}m"
        }
    }

    /** Minute-granular duration ("45m", "1h 05m"); sub-minute reads 0m (no widget ticking). */
    fun elapsedMinutesText(elapsedMillis: Long): String =
        durationMinutesText((elapsedMillis / 60_000L).coerceAtLeast(0) * 60_000L)

    fun durationMinutesText(durationMillis: Long): String {
        val totalMinutes = (durationMillis / 60_000L).coerceAtLeast(0)
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) String.format(java.util.Locale.US, "%dh %02dm", hours, minutes) else "${minutes}m"
    }

    private fun trimNumber(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
}
