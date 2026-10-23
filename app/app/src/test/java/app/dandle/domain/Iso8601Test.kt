package app.dandle.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.format.DateTimeParseException

/**
 * ISO-8601 wire contract (research doc §1/§2.3): the server emits offsets ("…-04:00", "Z") and
 * microsecond precision (timer stamps); we emit UTC second-precision. Every datetime crossing
 * the wire goes through [Iso8601], so these pins guard all six mappers at once.
 */
class Iso8601Test {

    @Test
    fun `parses Zulu timestamps to epoch millis`() {
        assertEquals(1_759_986_000_000L, Iso8601.parse("2025-10-09T05:00:00Z"))
    }

    @Test
    fun `parses explicit offsets to the correct instant regardless of zone`() {
        // 05:00Z expressed as -04:00 and the same instant as +05:30 — all must agree.
        assertEquals(1_759_986_000_000L, Iso8601.parse("2025-10-09T01:00:00-04:00"))
        assertEquals(1_759_986_000_000L, Iso8601.parse("2025-10-09T10:30:00+05:30"))
        assertEquals(
            Iso8601.parse("2025-10-09T05:00:00Z"),
            Iso8601.parse("2025-10-09T01:00:00-04:00"),
        )
    }

    @Test
    fun `parses fractional seconds at server microsecond precision`() {
        // Timer stamps arrive like "2025-10-09T05:00:00.123456Z" (research doc 8_7);
        // sub-millisecond remainder floors away, the millis part survives.
        assertEquals(1_759_986_000_123L, Iso8601.parse("2025-10-09T05:00:00.123456Z"))
        assertEquals(1_759_986_000_500L, Iso8601.parse("2025-10-09T05:00:00.5Z"))
    }

    @Test
    fun `format stamps UTC with Z and truncates sub-second precision`() {
        val stamped = Iso8601.format(1_759_986_000_999L)
        assertEquals("2025-10-09T05:00:00Z", stamped)
        assertTrue("UTC stamps must end in Z, was: $stamped", stamped.endsWith("Z"))
    }

    @Test
    fun `format and parse round-trip whole-second instants across offsets`() {
        // Only whole-second instants round-trip: we always SEND second precision (format
        // truncates), so a fractional input parses but cannot survive a round trip by design.
        val shapes = listOf(
            "2025-10-09T05:00:00Z",
            "2025-10-09T01:00:00-04:00",
            "2025-10-09T10:30:00+05:30",
        )
        for (shape in shapes) {
            val millis = Iso8601.parse(shape)
            assertEquals(shape, millis, Iso8601.parse(Iso8601.format(millis)))
        }
        // Fractional parse → format collapses onto the whole-second boundary.
        assertEquals(
            Iso8601.parse("2025-10-09T05:00:00Z"),
            Iso8601.parse(Iso8601.format(Iso8601.parse("2025-10-09T05:00:00.123456Z"))),
        )
    }

    @Test
    fun `server clock offset flows into outbound stamps and round-trips`() {
        // Device clock frozen at wall; server is 90s ahead (the captured Date-header skew).
        val wall = 1_759_986_000_000L
        val serverClock = ServerClock(Clock.fixed(wall)).apply { restoreOffset(90_000L) }

        val stamp = Iso8601.format(serverClock.nowMillis())

        assertEquals("2025-10-09T05:01:30Z", stamp)
        assertEquals(wall + 90_000L, Iso8601.parse(stamp))
    }

    @Test
    fun `offset-less naive timestamps are rejected loudly instead of shifting silently`() {
        // The server always sends an offset (research doc §1); parsing a naive string must fail
        // fast rather than invent a zone and store an entry hours away from when it happened.
        try {
            Iso8601.parse("2025-10-09T05:00:00")
            throw AssertionError("expected DateTimeParseException for an offset-less timestamp")
        } catch (_: DateTimeParseException) {
            // expected
        }
    }
}
