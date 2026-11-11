package app.dandle.ui.dashboard

import app.dandle.domain.FeedingOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The feeding two-state cascade (design framework v2.3 rule 5, spec §3.1): type gates methods
 * per FeedingOptions.methodsFor, and a complete choice (type + method) collapses to a one-line
 * summary so only Amount + Note remain. This pins the VISIBILITY state machine shared by the
 * running card and the Quick Edit sheet (the method-filtering matrix itself lives in
 * FeedingOptionsTest).
 */
class FeedingCascadeTest {

    @Test
    fun `no type shows only the type row`() {
        val cascade = feedingCascade(typeWire = null, methodWire = null, expanded = false)
        assertTrue(cascade.showTypeRow)
        assertFalse(cascade.showMethodRow)
        assertFalse(cascade.showSummary)
        assertNull(cascade.summaryText)
        assertFalse(cascade.isCollapsed)
    }

    @Test
    fun `type picked without method shows type and filtered method rows`() {
        val cascade = feedingCascade(typeWire = "formula", methodWire = null, expanded = false)
        assertTrue(cascade.showTypeRow)
        assertTrue(cascade.showMethodRow)
        assertFalse(cascade.showSummary)
        assertNull(cascade.summaryText)
    }

    @Test
    fun `complete choice collapses to a summary`() {
        val cascade = feedingCascade(typeWire = "formula", methodWire = "bottle", expanded = false)
        assertTrue(cascade.showSummary)
        assertTrue(cascade.isCollapsed)
        assertFalse(cascade.showTypeRow)
        assertFalse(cascade.showMethodRow)
        assertEquals("Formula · Bottle", cascade.summaryText)
    }

    @Test
    fun `summary uses the display labels including Fortified milk`() {
        assertEquals(
            "Fortified milk · Bottle",
            feedingCascade("fortified breast milk", "bottle", expanded = false).summaryText,
        )
        assertEquals(
            "Breast milk · Both breasts",
            feedingCascade("breast milk", "both breasts", expanded = false).summaryText,
        )
        assertEquals(
            "Solids · Parent fed",
            feedingCascade("solid food", "parent fed", expanded = false).summaryText,
        )
    }

    @Test
    fun `explicitly re-opened cascade shows the rows again instead of the summary`() {
        val cascade = feedingCascade(typeWire = "formula", methodWire = "bottle", expanded = true)
        assertTrue(cascade.showTypeRow)
        assertTrue(cascade.showMethodRow)
        assertFalse(cascade.showSummary)
        assertNull(cascade.summaryText)
    }

    @Test
    fun `the collapsed state gates the remaining fields (amount and note only)`() {
        // The card renders Amount + Note iff isCollapsed; pinned so a refactor cannot hide
        // the fields behind a picking state (framework rule 5: "only Amount + Note remain").
        assertTrue(feedingCascade("formula", "bottle", false).isCollapsed)
        assertFalse(feedingCascade("formula", null, false).isCollapsed)
        assertFalse(feedingCascade(null, null, false).isCollapsed)
        assertFalse(feedingCascade("formula", "bottle", true).isCollapsed)
    }

    @Test
    fun `cascade composes with the method filter matrix (breast milk vs formula)`() {
        // What the card actually renders per picked type — the cascade's method row options.
        val breast = FeedingOptions.methodsFor("breast milk").map { it.wire }
        assertEquals(6, breast.size)
        assertTrue("both breasts" in breast)

        val formula = FeedingOptions.methodsFor("formula").map { it.wire }
        assertEquals(listOf("bottle", "parent fed", "self fed"), formula)
    }
}
