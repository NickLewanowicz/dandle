package app.dandle.domain

import app.dandle.domain.model.Event
import app.dandle.domain.model.EventType
import app.dandle.domain.model.SyncState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Parent-language translation of server rejections (spec §3.8/§6.2) — overlap errors must name
 * the clashing entry, session expiry must reassure with the unsent count.
 */
class FailureMessageTest {

    // "10/30/2026, 2:15 PM" style — the range formatter is device-local; assert on stability
    // by constructing expectations from the same zone the code uses is overkill: instead we
    // pin translation on the LOCAL-clash path with a fixed zone via formatted content checks.
    private val zone = java.time.ZoneId.systemDefault()

    private fun atHour(hour: Int, minute: Int): Long =
        java.time.LocalDateTime.of(2026, 10, 16, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun clashEvent(type: EventType = EventType.SLEEP) = Event(
        localUuid = "clash",
        serverId = "4",
        type = type,
        childId = "1",
        startMillis = atHour(13, 15),
        endMillis = atHour(14, 0),
        syncState = SyncState.SYNCED,
    )

    @Test
    fun `overlap with a locally loaded clash names kind and local time range`() {
        val message = FailureMessage.translate(
            reason = "Another entry intersects the specified time period. " +
                "Conflicting entry: Sleep (10/16/26 01:15 PM - 10/16/26 02:00 PM)",
            conflictUiPath = "sleep",
            conflictServerId = "4",
            clash = clashEvent(),
        )
        // Format follows the DEVICE localized time (persona round 2: the dialog must
        // speak the same dialect as the HH:mm timeline) — build the expectation with
        // the same formatter instead of pinning a locale string.
        val fmt = java.time.format.DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT)
        val zone = java.time.ZoneId.systemDefault()
        val t1 = fmt.format(java.time.Instant.ofEpochMilli(clashEvent().startMillis).atZone(zone))
        val t2 = fmt.format(java.time.Instant.ofEpochMilli(clashEvent().endMillis).atZone(zone))
        assertEquals("This overlaps the $t1–$t2 nap.", message)
    }

    @Test
    fun `overlap without a local row parses kind and range from the reason text`() {
        val message = FailureMessage.translate(
            reason = "Another entry intersects the specified time period. " +
                "Conflicting entry: Sleep (11/19/17 03:00 - 11/19/17 04:30)",
            conflictUiPath = "sleep",
            conflictServerId = "4",
        )
        assertEquals("This overlaps the 03:00–04:30 nap.", message)
    }

    @Test
    fun `overlap with conflict ref but unparseable text still names the kind`() {
        val message = FailureMessage.translate(
            reason = "Another entry intersects the specified time period.",
            conflictUiPath = "feedings",
            conflictServerId = "9",
        )
        assertEquals("This overlaps an existing feed.", message)
    }

    @Test
    fun `kind labels read like a parent speaks`() {
        assertEquals(
            "This overlaps an existing diaper change.",
            FailureMessage.translate(reason = "intersects", conflictUiPath = "changes"),
        )
        assertEquals(
            "This overlaps an existing tummy time.",
            FailureMessage.translate(reason = "intersects", conflictUiPath = "tummy-time"),
        )
        assertEquals(
            "This overlaps an existing pump.",
            FailureMessage.translate(reason = "intersects", conflictUiPath = "pumping"),
        )
    }

    @Test
    fun `overlap with nothing parseable stays calm and generic`() {
        assertEquals(
            "This overlaps another entry.",
            FailureMessage.translate(reason = "Another entry intersects the specified time period."),
        )
    }

    @Test
    fun `future-time rejections are translated`() {
        assertEquals(
            "Entries can't be in the future.",
            FailureMessage.translate(reason = "start: Date time must be in the past."),
        )
    }

    @Test
    fun `deleted-on-server rejections are translated`() {
        assertEquals(
            "This entry was deleted on the server.",
            FailureMessage.translate(reason = "This entry was deleted on the server."),
        )
    }

    @Test
    fun `unknown reasons pass through verbatim`() {
        assertEquals(
            "notes: This field cannot be blank.",
            FailureMessage.translate(reason = "notes: This field cannot be blank."),
        )
    }

    @Test
    fun `missing reason falls back kindly`() {
        assertEquals(
            "The server rejected this entry. Edit it or try again.",
            FailureMessage.translate(reason = null),
        )
        assertEquals(
            "The server rejected this entry. Edit it or try again.",
            FailureMessage.translate(reason = "  "),
        )
    }

    @Test
    fun `session-expired copy reassures with the unsent count`() {
        assertEquals(
            "Session expired — your 3 unsent entries are safe. Sign in and they'll go up.",
            FailureMessage.sessionExpired(3),
        )
        assertEquals(
            "Session expired — your 1 unsent entry is safe. Sign in and they'll go up.",
            FailureMessage.sessionExpired(1),
        )
    }
}
