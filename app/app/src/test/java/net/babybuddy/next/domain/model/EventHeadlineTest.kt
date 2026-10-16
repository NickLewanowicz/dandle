package net.babybuddy.next.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** Headline formats MUST be consistent app-wide (functional spec §4.1) — all six types. */
class EventHeadlineTest {

    private fun event(type: EventType, build: Event.() -> Event = { this }): String =
        EventHeadline.headline(
            Event(
                localUuid = "u1",
                serverId = 1,
                type = type,
                childId = 1,
                startMillis = 0,
                endMillis = 0,
            ).build(),
        )

    @Test
    fun `feeding reads method then amount`() {
        assertEquals(
            "Bottle · 120 mL",
            event(EventType.FEEDING) { copy(feedingMethod = "bottle", amount = 120.0) },
        )
    }

    @Test
    fun `feeding omits the amount segment when amount is null`() {
        assertEquals(
            "Left breast",
            event(EventType.FEEDING) { copy(feedingMethod = "left breast") },
        )
    }

    @Test
    fun `feeding trims trailing -0 from whole amounts`() {
        assertEquals(
            "Bottle · 3 mL",
            event(EventType.FEEDING) { copy(feedingMethod = "bottle", amount = 3.0) },
        )
        assertEquals(
            "Bottle · 2.5 mL",
            event(EventType.FEEDING) { copy(feedingMethod = "bottle", amount = 2.5) },
        )
    }

    @Test
    fun `diaper combinations`() {
        assertEquals("Wet", event(EventType.CHANGE) { copy(wet = true, solid = false) })
        assertEquals("Solid", event(EventType.CHANGE) { copy(wet = false, solid = true) })
        assertEquals("Wet + Solid", event(EventType.CHANGE) { copy(wet = true, solid = true) })
    }

    @Test
    fun `diaper appends the color when set`() {
        assertEquals(
            "Wet (Yellow)",
            event(EventType.CHANGE) { copy(wet = true, solid = false, color = "yellow") },
        )
    }

    @Test
    fun `sleep is just Sleep`() {
        assertEquals("Sleep", event(EventType.SLEEP))
    }

    @Test
    fun `note shows the first line only`() {
        assertEquals(
            "Great latch after feed",
            event(EventType.NOTE) { copy(noteText = "Great latch after feed\nsecond line") },
        )
    }

    @Test
    fun `note falls back when text is blank`() {
        assertEquals("Note", event(EventType.NOTE) { copy(noteText = "") })
        assertEquals("Note", event(EventType.NOTE))
    }

    @Test
    fun `pumping reads Pump then amount`() {
        assertEquals("Pump · 85 mL", event(EventType.PUMPING) { copy(amount = 85.0) })
        assertEquals("Pump", event(EventType.PUMPING) { copy(amount = null) })
    }

    @Test
    fun `tummy time appends the milestone when set`() {
        assertEquals("Tummy time", event(EventType.TUMMY_TIME) { copy(milestone = null) })
        assertEquals(
            "Tummy time · Rolled over",
            event(EventType.TUMMY_TIME) { copy(milestone = "Rolled over") },
        )
        // Blank milestone stays invisible.
        assertEquals("Tummy time", event(EventType.TUMMY_TIME) { copy(milestone = "   ") })
    }
}
