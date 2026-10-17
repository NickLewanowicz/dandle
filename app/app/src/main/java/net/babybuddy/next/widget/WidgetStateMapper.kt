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
    )

    data class RunningTimerState(val kind: EventType, val elapsedText: String)

    /** One row of the CareDashboard widget ("Last feed · 12m ago · 120 mL"). */
    data class CareRow(
        val type: EventType,
        val label: String,
        val sinceText: String,
        val detailText: String?,
    )

    data class CareDashboardState(
        val rows: List<CareRow>,
        val running: RunningTimerState?,
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

    /** The four QuickActions slots with running-state accents (spec §7.1). */
    fun quickActionSlots(
        configured: List<EventType>,
        timers: List<RunningTimer>,
        childId: Long,
        nowMillis: Long,
    ): List<QuickActionSlot> = configured.map { type ->
        val running = timers.firstOrNull { it.kind == type && it.childId == childId }
        QuickActionSlot(
            type = type,
            label = slotLabel(type),
            running = running != null,
            elapsedText = running?.let { elapsedMinutesText(nowMillis - it.startedAtMillis) },
        )
    }

    /**
     * CareDashboard rows (spec §7.2): last event per type, newest activity first per row,
     * plus the running-timer row when one is active. [visible] filters configured rows.
     */
    fun careDashboard(
        events: List<Event>,
        timers: List<RunningTimer>,
        childId: Long,
        nowMillis: Long,
        visible: Set<EventType>,
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
                sinceText = sinceText(nowMillis - event.endMillis),
                detailText = detailText(event),
            )
        }
        val running = timers.firstOrNull { it.childId == childId }?.let {
            RunningTimerState(it.kind, elapsedMinutesText(nowMillis - it.startedAtMillis))
        }
        return CareDashboardState(rows = rows, running = running)
    }

    fun rowLabel(type: EventType): String = when (type) {
        EventType.FEEDING -> "Last feed"
        EventType.CHANGE -> "Last diaper"
        EventType.SLEEP -> "Last nap"
        EventType.PUMPING -> "Last pump"
        EventType.NOTE -> "Last note"
        EventType.TUMMY_TIME -> "Last tummy time"
    }

    /** "12m ago" · amount for feed/pump, wet/solid for diaper, duration for nap (spec §7.2). */
    fun detailText(event: Event): String? = when (event.type) {
        EventType.FEEDING, EventType.PUMPING ->
            event.amount?.let { amount -> "${trimNumber(amount)} mL" }
        EventType.CHANGE -> when {
            event.wet == true && event.solid == true -> "Wet + Solid"
            event.wet == true -> "Wet"
            event.solid == true -> "Solid"
            else -> null
        }
        EventType.SLEEP, EventType.TUMMY_TIME -> durationMinutesText(event.durationMillis)
        EventType.NOTE -> null
    }

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
