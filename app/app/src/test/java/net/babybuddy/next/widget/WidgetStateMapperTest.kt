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
        // Priya (persona round 2): rows show CLOCK times — relative "12m" under-reports
        // as the widget goes stale between refreshes.
        assertEquals(WidgetStateMapper.clockTime(now - 12 * 60_000), feed.timeText)
        assertEquals("120 mL", feed.detailText)
        assertEquals(WidgetStateMapper.clockTime(now - 34 * 60_000), state.rows.first { it.type == EventType.CHANGE }.timeText)
        assertEquals("Wet", state.rows.first { it.type == EventType.CHANGE }.detailText)
        // Sleep 45m (>5min): start-end range (owner rule — no math for tired parents).
        val napRange = state.rows.first { it.type == EventType.SLEEP }
        assertEquals(
            WidgetStateMapper.clockTime(now - 165 * 60_000) + "–" + WidgetStateMapper.clockTime(now - 120 * 60_000),
            napRange.timeText,
        )
        // Duration detail dropped for timed rows — the range carries it now.
        assertNull(napRange.detailText)
        assertEquals("85 mL", state.rows.first { it.type == EventType.PUMPING }.detailText)
        assertNull(state.running)
    }

    @Test
    fun `long nap shows a start-end range and running timer reads as state`() {
        val state = WidgetStateMapper.careDashboard(
            events = listOf(
                // 8h nap ending 30m ago (started 8.5h ago, duration 8h) — the overnight case.
                event(EventType.SLEEP, endedMinutesAgo = 30, durationMinutes = 8 * 60),
            ),
            timers = listOf(timer(EventType.SLEEP, startedMinutesAgo = 45)),
            childId = 1,
            nowMillis = now,
            visible = setOf(EventType.SLEEP),
        )
        val nap = state.rows.first { it.type == EventType.SLEEP }
        val zone = java.time.ZoneId.systemDefault()
        val fmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
        val endClock = fmt.format(java.time.Instant.ofEpochMilli(now - 30 * 60_000).atZone(zone))
        val startClock = fmt.format(java.time.Instant.ofEpochMilli(now - 30 * 60_000 - 8 * 60 * 60_000).atZone(zone))
        assertEquals("${startClock}–${endClock}", nap.timeText)
        assertNull(nap.detailText)
        // Priya: the running row reads as STATE at the top, not as a stop button.
        val since = state.runningSince!!
        assertEquals(EventType.SLEEP, since.kind)
        assertEquals(WidgetStateMapper.clockTime(now - 45 * 60_000), since.sinceText)
        assertEquals("45m", since.elapsedText)
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
        // 185-minute nap: timeText is the start-end range (>=60m) — the range IS the duration.
        val nap = state.rows.first { it.type == EventType.SLEEP }
        assertEquals(
            WidgetStateMapper.clockTime(now - 485 * 60_000) + "–" + WidgetStateMapper.clockTime(now - 300 * 60_000),
            nap.timeText,
        )
        assertNull(nap.detailText)
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
    fun `zero or negative amount reads as unknown - row shows only the since text`() {
        // UX-review W9: the known failed-sync pump with amount 0 must not read as data.
        val state = WidgetStateMapper.careDashboard(
            events = listOf(
                event(EventType.PUMPING, endedMinutesAgo = 9 * 60, amount = 0.0),
                event(EventType.FEEDING, endedMinutesAgo = 12, amount = -1.0),
            ),
            timers = emptyList(),
            childId = 1,
            nowMillis = now,
            visible = setOf(EventType.PUMPING, EventType.FEEDING),
        )
        assertEquals(WidgetStateMapper.clockTime(now - 9 * 60 * 60_000), state.rows.first { it.type == EventType.PUMPING }.timeText)
        assertNull(state.rows.first { it.type == EventType.PUMPING }.detailText)
        assertNull(state.rows.first { it.type == EventType.FEEDING }.detailText)
    }

    @Test
    fun `dashboard state carries the child name for the header`() {
        // UX-review W8: the widget header names whose data it shows.
        val state = WidgetStateMapper.careDashboard(
            events = listOf(event(EventType.FEEDING, endedMinutesAgo = 12, amount = 120.0)),
            timers = emptyList(),
            childId = 1,
            nowMillis = now,
            visible = setOf(EventType.FEEDING),
            childName = "Milo",
        )
        assertEquals("Milo", state.childName)
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

    // -- T7: cross-child running line ---------------------------------------------------

    @Test
    fun `cross-child timer line names the other child kind and elapsed`() {
        val cross = WidgetStateMapper.crossChildTimer(
            timers = listOf(
                timer(EventType.SLEEP, childId = 1, startedMinutesAgo = 5), // own child: ignored
                timer(EventType.SLEEP, childId = 2, startedMinutesAgo = 32),
            ),
            childId = 1,
            otherChildFirstName = "Zoe",
            nowMillis = now,
        )
        assertEquals(EventType.SLEEP, cross!!.kind)
        assertEquals("Zoe", cross.childFirstName)
        assertEquals("32m", cross.elapsedText)
    }

    @Test
    fun `cross-child line is null when only this child's timer runs`() {
        val cross = WidgetStateMapper.crossChildTimer(
            timers = listOf(timer(EventType.SLEEP, childId = 1, startedMinutesAgo = 5)),
            childId = 1,
            otherChildFirstName = "Zoe",
            nowMillis = now,
        )
        assertEquals(null, cross)
    }

    @Test
    fun `cross-child line with unknown child name degrades without lying`() {
        val cross = WidgetStateMapper.crossChildTimer(
            timers = listOf(timer(EventType.PUMPING, childId = 2, startedMinutesAgo = 7)),
            childId = 1,
            otherChildFirstName = null,
            nowMillis = now,
        )
        assertEquals("Another child", cross!!.childFirstName)
        assertEquals("7m", cross.elapsedText)
        assertEquals("pump", WidgetStateMapper.friendlyNoun(EventType.PUMPING))
        assertEquals("nap", WidgetStateMapper.friendlyNoun(EventType.SLEEP))
    }

    // -- U10: subdued "last" line on idle slots -------------------------------------------

    @Test
    fun `idle slot whispers when its type last happened (compact since)`() {
        val slots = WidgetStateMapper.quickActionSlots(
            configured = listOf(EventType.CHANGE, EventType.FEEDING),
            timers = emptyList(),
            childId = 1,
            nowMillis = now,
            latest = mapOf(
                EventType.CHANGE to event(EventType.CHANGE, endedMinutesAgo = 90),
                EventType.FEEDING to event(EventType.FEEDING, endedMinutesAgo = 3 * 24 * 60 + 5),
            ),
        )
        assertEquals("1h30m", slots[0].lastText)
        assertEquals("3d", slots[1].lastText)
    }

    @Test
    fun `running slot shows elapsed, not the last line`() {
        val slots = WidgetStateMapper.quickActionSlots(
            configured = listOf(EventType.SLEEP),
            timers = listOf(timer(EventType.SLEEP, startedMinutesAgo = 17)),
            childId = 1,
            nowMillis = now,
            latest = mapOf(EventType.SLEEP to event(EventType.SLEEP, endedMinutesAgo = 50)),
        )
        assertTrue(slots[0].running)
        assertEquals("17m", slots[0].elapsedText)
        assertNull("a running slot must not also whisper 'last'", slots[0].lastText)
    }

    @Test
    fun `idle slot with no history has no last line`() {
        val slots = WidgetStateMapper.quickActionSlots(
            configured = listOf(EventType.PUMPING),
            timers = emptyList(),
            childId = 1,
            nowMillis = now,
            latest = emptyMap(),
        )
        assertNull(slots[0].lastText)
    }

    @Test
    fun `compact since keeps minutes under an hour and zero pads the remainder`() {
        assertEquals("45m", WidgetStateMapper.compactSince(45 * 60_000L))
        assertEquals("1h30m", WidgetStateMapper.compactSince(90 * 60_000L))
        assertEquals("2h05m", WidgetStateMapper.compactSince(125 * 60_000L))
        assertEquals("2d", WidgetStateMapper.compactSince(48 * 60 * 60_000L + 30_000))
        assertEquals("0m", WidgetStateMapper.compactSince(-1))
    }
}
