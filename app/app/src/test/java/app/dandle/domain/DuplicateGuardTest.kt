package app.dandle.domain

import app.dandle.domain.model.Event
import app.dandle.domain.model.EventType
import app.dandle.domain.model.SyncState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Duplicate guard window (spec §3.7): same type within 4 minutes → one calm line. */
class DuplicateGuardTest {

    private val now = 1_760_000_000_000L

    private fun event(
        type: EventType,
        startMinusMinutes: Long,
        durationMinutes: Long = 0,
        wet: Boolean? = null,
        solid: Boolean? = null,
    ) = Event(
        localUuid = "local-1",
        serverId = "7",
        type = type,
        childId = "1",
        startMillis = now - startMinusMinutes * 60_000,
        endMillis = now - startMinusMinutes * 60_000 + durationMinutes * 60_000,
        wet = wet,
        solid = solid,
        syncState = SyncState.SYNCED,
    )

    @Test
    fun `no previous entry means no hit`() {
        assertNull(DuplicateGuard.check(now, null))
    }

    @Test
    fun `entry logged exactly 4 minutes ago is still within the window`() {
        val hit = DuplicateGuard.check(
            now,
            event(EventType.CHANGE, 4, wet = true),
            windowMillis = DuplicateGuard.WINDOW_MILLIS,
        )
        assertNotNull(hit)
        assertEquals("Wet diaper logged 4 min ago. Add anyway?", hit!!.message)
    }

    @Test
    fun `entry older than the window is not a hit`() {
        assertNull(DuplicateGuard.check(now, event(EventType.CHANGE, 5)))
        // A long nap that ended 5 minutes ago is also outside the window.
        assertNull(DuplicateGuard.check(now, event(EventType.SLEEP, 185, durationMinutes = 180)))
    }

    @Test
    fun `just-logged entry reads as logged just now`() {
        val hit = DuplicateGuard.check(now, event(EventType.CHANGE, 0, wet = true))!!
        assertEquals("Wet diaper logged just now. Add anyway?", hit.message)
    }

    @Test
    fun `future-dated entry does not trigger the guard`() {
        val future = event(EventType.CHANGE, -10) // ends 10 min in the future
        assertNull(DuplicateGuard.check(now, future))
    }

    @Test
    fun `recency is judged on the end instant so a long nap that just ended still hits`() {
        // Nap started 3 hours ago, ended 1 minute ago.
        val hit = DuplicateGuard.check(now, event(EventType.SLEEP, 181, durationMinutes = 180))
        assertNotNull(hit)
        assertEquals("Sleep logged 1 min ago. Add anyway?", hit!!.message)
    }

    @Test
    fun `diaper nouns name the wet-solid combination`() {
        assertEquals("Wet diaper", DuplicateGuard.nounFor(event(EventType.CHANGE, 1, wet = true, solid = false)))
        assertEquals("Solid diaper", DuplicateGuard.nounFor(event(EventType.CHANGE, 1, wet = false, solid = true)))
        assertEquals(
            "Diaper change",
            DuplicateGuard.nounFor(event(EventType.CHANGE, 1, wet = true, solid = true)),
        )
        assertEquals(
            "Diaper change",
            DuplicateGuard.nounFor(event(EventType.CHANGE, 1, wet = false, solid = false)),
        )
    }

    @Test
    fun `other types use their plain nouns`() {
        assertEquals("Feeding", DuplicateGuard.nounFor(event(EventType.FEEDING, 1)))
        assertEquals("Sleep", DuplicateGuard.nounFor(event(EventType.SLEEP, 1)))
        assertEquals("Note", DuplicateGuard.nounFor(event(EventType.NOTE, 1)))
        assertEquals("Pumping", DuplicateGuard.nounFor(event(EventType.PUMPING, 1)))
        assertEquals("Tummy time", DuplicateGuard.nounFor(event(EventType.TUMMY_TIME, 1)))
    }
}
