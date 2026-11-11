package app.dandle.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 0.3.1 icon-regression fix (owner-reported): the resolver stub returned 🍼 unconditionally,
 * erasing breast feedings and solid food on every dashboard surface.
 */
class FeedingEmojiTest {

    @Test
    fun `breast methods show the breastfeeding emoji regardless of side`() {
        assertEquals("🤱", feedingEmoji("breast milk", "left breast"))
        assertEquals("🤱", feedingEmoji("breast milk", "right breast"))
        assertEquals("🤱", feedingEmoji("breast milk", "both breasts"))
    }

    @Test
    fun `solid food shows the solid-food emoji even with a bottle method`() {
        // Solid food DOES include bottle as a method (QA deep-dive 879c1fe) — type wins.
        assertEquals("🥄", feedingEmoji("solid food", "bottle"))
        assertEquals("🥄", feedingEmoji("solid food", null))
    }

    @Test
    fun `bottle and other methods keep the bottle emoji`() {
        assertEquals("🍼", feedingEmoji("formula", "bottle"))
        assertEquals("🍼", feedingEmoji("breast milk", "parent fed"))
        assertEquals("🍼", feedingEmoji("fortified breast milk", "self fed"))
        assertEquals("🍼", feedingEmoji(null, null))
    }
}
