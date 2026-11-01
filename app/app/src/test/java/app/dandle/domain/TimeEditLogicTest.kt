package app.dandle.domain

import app.dandle.domain.model.EventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** Quick Edit time math + inline validation (spec §5). */
class TimeEditLogicTest {

    private val now = 1_760_000_000_000L // arbitrary "server now"

    // -- Dialed wall-clock resolution (backward-midnight; QA 2026-10-31) --------------

    // resolveDialedTime is zone-agnostic millis math; UTC keeps the fixtures DST-free
    // on any machine while the wall-clock narrative stays exact.
    private val zone = ZoneId.of("UTC")

    private fun at(date: LocalDate, hour: Int, minute: Int): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `at 00_44 dialing 23_46 resolves to YESTERDAY 23_46 and saves`() {
        val now = at(LocalDate.of(2026, 10, 31), 0, 44)
        val dialed = at(LocalDate.of(2026, 10, 31), 23, 46) // today's-date reading of the dial
        val resolved = TimeEditLogic.resolveDialedTime(dialed, now)
        assertEquals(at(LocalDate.of(2026, 10, 30), 23, 46), resolved)
        // The resolved draft must be valid (Save enabled, no inline reason).
        assertNull(TimeEditLogic.validate(resolved, resolved, isTimed = false, nowMillis = now))
    }

    @Test
    fun `small forward dial stays today and keeps the future error`() {
        val now = at(LocalDate.of(2026, 10, 31), 14, 0)
        val dialed = at(LocalDate.of(2026, 10, 31), 15, 30)
        val resolved = TimeEditLogic.resolveDialedTime(dialed, now)
        assertEquals(dialed, resolved)
        assertEquals(
            "Entries can't be in the future.",
            TimeEditLogic.validate(resolved, resolved, isTimed = false, nowMillis = now),
        )
    }

    @Test
    fun `same-day past dial is kept unchanged`() {
        val now = at(LocalDate.of(2026, 10, 31), 14, 0)
        assertEquals(
            at(LocalDate.of(2026, 10, 31), 13, 0),
            TimeEditLogic.resolveDialedTime(at(LocalDate.of(2026, 10, 31), 13, 0), now),
        )
        // Exactly now is kept too (<= is the keep branch).
        assertEquals(now, TimeEditLogic.resolveDialedTime(now, now))
    }

    @Test
    fun `forward dial past half a day resolves to yesterday even outside midnight`() {
        // 03:00 dialing 21:00: today-21:00 is 18 h ahead; yesterday-21:00 (6 h ago) is
        // the only sane reading of the dial.
        val now = at(LocalDate.of(2026, 10, 31), 3, 0)
        val dialed = at(LocalDate.of(2026, 10, 31), 21, 0)
        assertEquals(at(LocalDate.of(2026, 10, 30), 21, 0), TimeEditLogic.resolveDialedTime(dialed, now))
    }

    @Test
    fun `forward dial of exactly half a day is the ambiguity boundary and stays today`() {
        val now = at(LocalDate.of(2026, 10, 31), 2, 0)
        val dialed = at(LocalDate.of(2026, 10, 31), 14, 0)
        assertEquals(dialed, TimeEditLogic.resolveDialedTime(dialed, now))
    }

    @Test
    fun `a past-day pick is never rolled forward or back`() {
        val now = at(LocalDate.of(2026, 10, 31), 14, 0)
        // Editing a yesterday-evening entry stays on yesterday.
        val yesterdayEvening = at(LocalDate.of(2026, 10, 30), 22, 0)
        assertEquals(yesterdayEvening, TimeEditLogic.resolveDialedTime(yesterdayEvening, now))
    }

    // -- Start moves preserve duration ---------------------------------------------

    @Test
    fun `moving start -15m shifts end by the same delta`() {
        val (start, end) = TimeEditLogic.moveStart(
            startMillis = now,
            endMillis = now + 12 * 60_000,
            newStartMillis = now - 15 * 60_000,
        )
        assertEquals(now - 15 * 60_000, start)
        assertEquals(now + 12 * 60_000 - 15 * 60_000, end)
    }

    @Test
    fun `moving start +1m keeps the duration identical`() {
        val duration = 45 * 60_000L
        val (start, end) = TimeEditLogic.moveStart(now - duration, now, now - duration + 60_000)
        assertEquals(now - duration + 60_000, start)
        assertEquals(duration, end - start)
    }

    @Test
    fun `steppers move by exactly the advertised amounts`() {
        assertEquals(60_000, TimeEditLogic.step(0, 1))
        assertEquals(900_000, TimeEditLogic.step(0, 15))
        assertEquals(-900_000, TimeEditLogic.step(0, -15))
    }

    @Test
    fun `end moves are raw so validation can flag end-before-start`() {
        // No clamping: the sheet surfaces "End time must be after start time." instead.
        assertEquals(now - 60_000, TimeEditLogic.step(now, -1))
        assertEquals(
            "End time must be after start time.",
            TimeEditLogic.validate(now, now - 60_000, isTimed = true, nowMillis = now + 60_000),
        )
    }

    @Test
    fun `diaper edits need wet or solid, mirroring the create panel`() {
        assertEquals(
            "Turn on Wet or Solid first.",
            TimeEditLogic.validateTypeFields(EventType.CHANGE, wet = false, solid = false),
        )
        assertNull(TimeEditLogic.validateTypeFields(EventType.CHANGE, wet = true, solid = false))
        assertNull(TimeEditLogic.validateTypeFields(EventType.CHANGE, wet = false, solid = true))
        // Other types are never gated.
        assertNull(TimeEditLogic.validateTypeFields(EventType.SLEEP, wet = null, solid = null))
        assertNull(TimeEditLogic.validateTypeFields(EventType.NOTE, wet = false, solid = false))
    }

    // -- Validation ------------------------------------------------------------------

    @Test
    fun `end at or before start is rejected`() {
        assertEquals(
            "End time must be after start time.",
            TimeEditLogic.validate(now, now, isTimed = true, nowMillis = now + 60_000),
        )
        assertEquals(
            "End time must be after start time.",
            TimeEditLogic.validate(now, now - 1, isTimed = true, nowMillis = now + 60_000),
        )
    }

    @Test
    fun `future start or end is rejected`() {
        assertEquals(
            "Entries can't be in the future.",
            TimeEditLogic.validate(now + 1, now + 60_000, isTimed = true, nowMillis = now),
        )
        assertEquals(
            "Entries can't be in the future.",
            TimeEditLogic.validate(now - 60_000, now + 1, isTimed = true, nowMillis = now),
        )
        // Instant types only carry a time.
        assertEquals(
            "Entries can't be in the future.",
            TimeEditLogic.validate(now + 1, now + 1, isTimed = false, nowMillis = now),
        )
    }

    @Test
    fun `timed entries over 24 hours are rejected`() {
        assertEquals(
            "Timed entries can't be longer than 24 hours.",
            TimeEditLogic.validate(0, TimeEditLogic.MAX_DURATION_MILLIS + 1, isTimed = true, nowMillis = Long.MAX_VALUE / 2),
        )
        // Exactly 24h is allowed.
        assertNull(
            TimeEditLogic.validate(0, TimeEditLogic.MAX_DURATION_MILLIS, isTimed = true, nowMillis = Long.MAX_VALUE / 2),
        )
    }

    @Test
    fun `valid drafts produce no reason`() {
        assertNull(
            TimeEditLogic.validate(now - 45 * 60_000, now, isTimed = true, nowMillis = now),
        )
        assertNull(
            TimeEditLogic.validate(now, now, isTimed = false, nowMillis = now),
        )
    }

    @Test
    fun `end-before-start wins over the other reasons (first failure is shown)`() {
        // end <= start AND in the future AND over 24h → the first listed reason wins.
        val reason = TimeEditLogic.validate(
            startMillis = now - 25 * 60 * 60_000,
            endMillis = now - 26 * 60 * 60_000,
            isTimed = true,
            nowMillis = now,
        )
        assertNotNull(reason)
        assertEquals("End time must be after start time.", reason)
    }
}
