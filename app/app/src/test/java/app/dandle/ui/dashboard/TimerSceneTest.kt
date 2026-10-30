package app.dandle.ui.dashboard

import app.dandle.domain.model.EventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Themed scene cards (design framework v2.3 rule 4): one motif per activity, and EVERY
 * text/surface pair clears WCAG AA (≥4.5:1) — the owner made contrast non-negotiable after
 * earlier screenshots shipped dark-on-dark fields. Scenes are self-contained color systems,
 * so the same pairs hold in the app's light and dark themes (the card looks identical in
 * both; a night scene is dark, period).
 */
class TimerSceneTest {

    // -- Mapping -----------------------------------------------------------------------

    @Test
    fun `each timed type maps to its own motif`() {
        assertEquals(TimerSceneMotif.NIGHT, sceneFor(EventType.SLEEP)?.motif)
        assertEquals(TimerSceneMotif.MILK, sceneFor(EventType.PUMPING)?.motif)
        assertEquals(TimerSceneMotif.SUN, sceneFor(EventType.TUMMY_TIME)?.motif)
        assertEquals(TimerSceneMotif.BOTTLE, sceneFor(EventType.FEEDING)?.motif)
    }

    @Test
    fun `the four timed types get four distinct scenes`() {
        val scenes = listOf(EventType.SLEEP, EventType.PUMPING, EventType.TUMMY_TIME, EventType.FEEDING)
            .map { sceneFor(it) }
        assertEquals(4, scenes.toSet().size)
        scenes.forEach { assertNotNull(it) }
    }

    @Test
    fun `sheet-only types have no scene`() {
        assertNull(sceneFor(EventType.CHANGE))
        assertNull(sceneFor(EventType.NOTE))
    }

    @Test
    fun `gradients start at the mock's identity colors`() {
        assertEquals(0xFF071233L, sceneFor(EventType.SLEEP)!!.gradientStops.first().second)
        assertEquals(0xFFFFF3E4L, sceneFor(EventType.PUMPING)!!.gradientStops.first().second)
        assertEquals(0xFFFDF3E0L, sceneFor(EventType.TUMMY_TIME)!!.gradientStops.first().second)
        assertEquals(0xFFE7F3FFL, sceneFor(EventType.FEEDING)!!.gradientStops.first().second)
    }

    // -- Contrast (WCAG AA, owner requirement) -----------------------------------------

    @Test
    fun `every text pair clears 4_5 to 1 over the worst-case gradient point`() {
        listOf(EventType.SLEEP, EventType.PUMPING, EventType.TUMMY_TIME, EventType.FEEDING).forEach { type ->
            val scene = sceneFor(type)!!
            // Worst case = the gradient stop FARTHEST from the text color; evaluating both
            // stops and taking the minimum covers the whole gradient.
            val gradient = scene.gradientStops.map { it.second }

            assertAA(type, "content", minRatio(scene.content, gradient))
            assertAA(type, "contentDim", minRatio(scene.contentDim, gradient))
            assertAA(type, "accent (Adjust link)", minRatio(scene.accent, gradient))
            // The save glyph sits on its translucent chip — composite before judging.
            assertAA(
                type,
                "saveIcon",
                minRatio(scene.saveIcon, gradient.map { compositeOver(scene.saveBg, it) }),
            )
            // Field text/hint sit on the field veil over the gradient.
            val fieldBg = gradient.map { compositeOver(scene.fieldBg, it) }
            assertAA(type, "fieldText", minRatio(scene.fieldText, fieldBg))
            assertAA(type, "fieldHint", minRatio(scene.fieldHint, fieldBg))
            // Stop-band links sit on the band surface.
            val band = gradient.map { compositeOver(scene.bandBg, it) }
            assertAA(type, "destructive (Discard)", minRatio(scene.destructive, band))
            assertAA(type, "accent on band", minRatio(scene.accent, band))
            // Chip text (feeding cascade) on unselected and selected pills. The selected pill
            // is near-solid, so dark scenes flip its text (chipSelectedText).
            assertAA(type, "chip text", minRatio(scene.content, gradient.map { compositeOver(scene.chipBg, it) }))
            assertAA(
                type,
                "chip selected text",
                minRatio(scene.chipSelectedText, gradient.map { compositeOver(scene.chipSelectedBg, it) }),
            )
        }
    }

    @Test
    fun `the emphasized Save button clears 4_5 to 1 in every scene`() {
        listOf(EventType.SLEEP, EventType.PUMPING, EventType.TUMMY_TIME, EventType.FEEDING).forEach { type ->
            val scene = sceneFor(type)!!
            assertAA(type, "saveStrongText", contrast(scene.saveStrongBg, scene.saveStrongText))
        }
    }

    @Test
    fun `night fields are a LIGHT veil, never a dark field on a dark card`() {
        // Owner callout: dark-on-dark inputs are the cardinal sin. On the night scene the
        // field surface must be lighter than the gradient behind it, with near-white text.
        val night = sceneFor(EventType.SLEEP)!!
        val worst = night.gradientStops.map { it.second }.maxBy { luminance(it) }
        val field = compositeOver(night.fieldBg, worst)
        assertTrue(
            "night field ($field) must be lighter than the gradient ($worst)",
            luminance(field) > luminance(worst),
        )
        assertTrue("night field text must be near-white", contrast(field, night.fieldText) >= 4.5)
    }

    // -- Contrast math (same WCAG relative luminance as ThemePaletteTest, over ARGB longs) --

    private fun channel(argb: Long, shift: Int): Double {
        val v = ((argb shr shift) and 0xFF).toDouble() / 255.0
        return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
    }

    private fun luminance(argb: Long): Double =
        0.2126 * channel(argb, 16) + 0.7152 * channel(argb, 8) + 0.0722 * channel(argb, 0)

    private fun contrast(a: Long, b: Long): Double {
        val l1 = luminance(a)
        val l2 = luminance(b)
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    /** Minimum contrast of [fg] against each candidate background. */
    private fun minRatio(fg: Long, backgrounds: List<Long>): Double =
        backgrounds.minOf { contrast(fg, it) }

    private fun assertAA(type: EventType, what: String, ratio: Double) {
        assertTrue(
            "$type $what contrast ${"%.2f".format(ratio)} < 4.5 (WCAG AA)",
            ratio >= 4.5,
        )
    }
}
