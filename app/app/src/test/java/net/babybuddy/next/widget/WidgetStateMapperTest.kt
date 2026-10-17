package net.babybuddy.next.widget

import net.babybuddy.next.domain.model.Event
import net.babybuddy.next.domain.model.EventType
import net.babybuddy.next.domain.model.RunningTimer
import net.babybuddy.next.domain.model.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DB → widget-state mapping (spec §7, UX spec §6): minute-granular "time since" with
 * future-skew clamping, per-row details, running-timer accents.
 */
class WidgetStateMapperTest {

    private val now = 1_760_000_000_000L

    private fun event(
        type: EventType,
        childId: Long = 1,
        endedMinutesAgo: Long,
        amount: Double? = null,
        wet: Boolean? = null,
        solid: Boolean? = null,
        durationMinutes: Long = 0,
    ): Event {
        val end = now - endedMinutesAgo * 60_000
        return Event(
            localUuid = "u-$type-$endedMinutesAgo",
            serverId = 1,
            type = type,
            childId = childId,
            startMillis = end - durationMinutes * 60_000,
            endMillis = end,
            amount = amount,
            wet = wet,
            solid = solid,
            syncState = SyncState.SYNCED,
        )
    }

    private fun timer(kind: EventType, childId: Long = 1, startedMinutesAgo: Long = 12) =
        RunningTimer(
            localId = 1,
            kind = kind,
            childId = childId,
            startedAtMillis = now - startedMinutesAgo * 60_000,
            serverTimerId = null,
        )

    // -- since formatting (minute granularity) -----------------------------------------

    @Test
    fun `time since is minute granular`() {
        assertEquals("0m", WidgetStateMapper.sinceText(0))
        assertEquals("0m", WidgetStateMapper.sinceText(59_999))
        assertEquals("1m", WidgetStateMapper.sinceText(60_000))
        assertEquals("12m", WidgetStateMapper.sinceText(12 * 60_000L + 30_000))
        assertEquals("1h", WidgetStateMapper.sinceText(60 * 60_000L))
        assertEquals("2h", WidgetStateMapper.sinceText(125 * 60_000L))
    }

    @Test
    fun `time since rolls over to days`() {
        assertEquals("23h", WidgetStateMapper.sinceText(23 * 60 * 60_000L + 59 * 60_000L))
        assertEquals("1d", WidgetStateMapper.sinceText(24 * 60 * 60_000L))
        assertEquals("3d", WidgetStateMapper.sinceText(3 * 24 * 60 * 60_000L + 4 * 60_000L))
    }

    @Test
    fun `future skew clamps to 0m instead of going negative`() {
        assertEquals("0m", WidgetStateMapper.sinceText(-1))
        assertEquals("0m", WidgetStateMapper.sinceText(-45 * 60_000L))
    }

    // -- QuickActions slots ------------------------------------------------------------

    @Test
    fun `running slot is accented with minute-granular elapsed`() {
        val slots = WidgetStateMapper.quickActionSlots(
            configured = listOf(EventType.SLEEP, EventType.FEEDING, EventType.CHANGE, EventType.PUMPING),
            timers = listOf(timer(EventType.SLEEP, startedMinutesAgo = 12)),
            childId = 1,
            nowMillis = now,
        )
        val sleep = slots.first { it.type == EventType.SLEEP }
        assertTrue(sleep.running)
        assertEquals("12m", sleep.elapsedText)
        assertEquals("Sleep", sleep.label)
        // Other slots idle.
        val feeding = slots.first { it.type == EventType.FEEDING }
        assertEquals(false, feeding.running)
        assertNull(feeding.elapsedText)
    }

    @Test
    fun `sub-minute running timer reads 0m`() {
        val slots = WidgetStateMapper.quickActionSlots(
            configured = listOf(EventType.SLEEP),
            timers = listOf(timer(EventType.SLEEP, startedMinutesAgo = 0)), // started "now"
            childId = 1,
            nowMillis = now,
        )
        assertEquals("0m", slots.single().elapsedText)
    }

    @Test
    fun `another child's timer does not accent this widget's slots`() {
        val slots = WidgetStateMapper.quickActionSlots(
            configured = listOf(EventType.SLEEP),
            timers = listOf(timer(EventType.SLEEP, childId = 2, startedMinutesAgo = 12)),
            childId = 1,
            nowMillis = now,
        )
        assertEquals(false, slots.single().running)
        assertNull(slots.single().elapsedText)
    }

    // -- CareDashboard rows ------------------------------------------------------------

    @Test
    fun `care rows read the newest event per type with minute-granular since`() {
        val state = WidgetStateMapper.careDashboard(
            events = listOf(
                event(EventType.FEEDING, endedMinutesAgo = 12, amount = 120.0),
                event(EventType.FEEDING, endedMinutesAgo = 200, amount = 90.0), // older, ignored
                event(EventType.CHANGE, endedMinutesAgo = 34, wet = true),
                event(EventType.SLEEP, endedMinutesAgo = 120, durationMinutes = 45),
                event(EventType.PUMPING, endedMinutesAgo = 180, amount = 85.0),
            ),
            timers = emptyList(),
            childId = 1,
            nowMillis = now,
            visible = setOf(EventType.FEEDING, EventType.CHANGE, EventType.SLEEP, EventType.PUMPING),
        )
        val feed = state.rows.first { it.type == EventType.FEEDING }
        assertEquals("Last feed", feed.label)
        assertEquals("12m", feed.sinceText)
        assertEquals("120 mL", feed.detailText)
        assertEquals("34m", state.rows.first { it.type == EventType.CHANGE }.sinceText)
        assertEquals("Wet", state.rows.first { it.type == EventType.CHANGE }.detailText)
        assertEquals("2h", state.rows.first { it.type == EventType.SLEEP }.sinceText)
        assertEquals("45m", state.rows.first { it.type == EventType.SLEEP }.detailText)
        assertEquals("85 mL", state.rows.first { it.type == EventType.PUMPING }.detailText)
        assertNull(state.running)
    }

    @Test
    fun `wet + solid diaper detail and hour-plus nap duration`() {
        val state = WidgetStateMapper.careDashboard(
            events = listOf(
                event(EventType.CHANGE, endedMinutesAgo = 5, wet = true, solid = true),
                event(EventType.SLEEP, endedMinutesAgo = 300, durationMinutes = 185),
            ),
            timers = emptyList(),
            childId = 1,
            nowMillis = now,
            visible = setOf(EventType.CHANGE, EventType.SLEEP),
        )
        assertEquals("Wet + Solid", state.rows.first { it.type == EventType.CHANGE }.detailText)
        assertEquals("3h 05m", state.rows.first { it.type == EventType.SLEEP }.detailText)
    }

    @Test
    fun `rows respect configured visibility and missing data is omitted`() {
        val state = WidgetStateMapper.careDashboard(
            events = listOf(event(EventType.FEEDING, endedMinutesAgo = 12, amount = 120.0)),
            timers = emptyList(),
            childId = 1,
            nowMillis = now,
            visible = setOf(EventType.FEEDING, EventType.PUMPING), // pump configured but has no data
        )
        assertEquals(1, state.rows.size)
        assertEquals(EventType.FEEDING, state.rows.single().type)
    }

    @Test
    fun `another child's events never appear in the rows`() {
        val state = WidgetStateMapper.careDashboard(
            events = listOf(event(EventType.FEEDING, childId = 2, endedMinutesAgo = 5, amount = 50.0)),
            timers = emptyList(),
            childId = 1,
            nowMillis = now,
            visible = setOf(EventType.FEEDING),
        )
        assertTrue(state.rows.isEmpty())
    }

    @Test
    fun `running timer row carries the kind and minute-granular elapsed`() {
        val state = WidgetStateMapper.careDashboard(
            events = emptyList(),
            timers = listOf(timer(EventType.FEEDING, startedMinutesAgo = 66)),
            childId = 1,
            nowMillis = now,
            visible = setOf(EventType.FEEDING),
        )
        assertEquals(EventType.FEEDING, state.running!!.kind)
        assertEquals("1h 06m", state.running!!.elapsedText)
    }
}
