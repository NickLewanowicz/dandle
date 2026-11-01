package app.dandle.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** Headline formats MUST be consistent app-wide (functional spec §4.1) — all six types. */
class EventHeadlineTest {

    private fun event(type: EventType, build: Event.() -> Event = { this }): String =
        EventHeadline.headline(
            Event(
                localUuid = "u1",
                serverId = "1",
                type = type,
                childId = "1",
                startMillis = 0,
                endMillis = 0,
            ).build(),
        )

    @Test
    fun `feeding reads method then amount`() {
        assertEquals(
            "Bottle · Amount: 120",
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
            "Bottle · Amount: 3",
            event(EventType.FEEDING) { copy(feedingMethod = "bottle", amount = 3.0) },
        )
        assertEquals(
            "Bottle · Amount: 2.5",
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
    fun `diaper does NOT append the color (rendered once as the row swatch)`() {
        assertEquals(
            "Wet",
            event(EventType.CHANGE) { copy(wet = true, solid = false, color = "yellow") },
        )
        // Server stores "" for "no color" — no empty parens (seen in seeded data).
        assertEquals(
            "Wet",
            event(EventType.CHANGE) { copy(wet = true, solid = false, color = "") },
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
        assertEquals("Pump · Amount: 85", event(EventType.PUMPING) { copy(amount = 85.0) })
        assertEquals("Pump", event(EventType.PUMPING) { copy(amount = null) })
    }

    @Test
    fun `zero or negative amount reads as unknown - no amount segment`() {
        // UX-review W9: the known failed-sync pump (amount 0) must not read as data,
        // in the app exactly like in the widgets (WidgetStateMapper.detailText).
        assertEquals("Pump", event(EventType.PUMPING) { copy(amount = 0.0) })
        assertEquals(
            "Bottle",
            event(EventType.FEEDING) { copy(feedingMethod = "bottle", amount = 0.0) },
        )
        assertEquals("Pump", event(EventType.PUMPING) { copy(amount = -1.0) })
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
