package app.dandle.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The feeding type → method cascade (spec §3.1), verified against the existing app v2.6.4
 * (docs/flows/existing-app/04-feeding.md) — including solid food → bottle.
 */
class FeedingOptionsTest {

    @Test
    fun `type wire values are the exact server enums in server order`() {
        assertEquals(
            listOf("breast milk", "formula", "fortified breast milk", "solid food"),
            FeedingOptions.TYPES.map { it.wire },
        )
    }

    @Test
    fun `breast milk offers all six methods`() {
        assertEquals(
            listOf("left breast", "right breast", "both breasts", "bottle", "parent fed", "self fed"),
            FeedingOptions.methodsFor("breast milk").map { it.wire },
        )
    }

    @Test
    fun `formula fortified and solid food offer only bottle parent-fed self-fed`() {
        val expected = listOf("bottle", "parent fed", "self fed")
        assertEquals(expected, FeedingOptions.methodsFor("formula").map { it.wire })
        assertEquals(expected, FeedingOptions.methodsFor("fortified breast milk").map { it.wire })
        // Verified in the existing app: solid food DOES include bottle.
        assertEquals(expected, FeedingOptions.methodsFor("solid food").map { it.wire })
    }

    @Test
    fun `unknown or missing type falls back to the non-nursing trio`() {
        val expected = listOf("bottle", "parent fed", "self fed")
        assertEquals(expected, FeedingOptions.methodsFor(null).map { it.wire })
        assertEquals(expected, FeedingOptions.methodsFor("mystery").map { it.wire })
    }

    @Test
    fun `valid method survives a type change`() {
        assertEquals("bottle", FeedingOptions.resetMethod("formula", "bottle"))
        assertEquals("parent fed", FeedingOptions.resetMethod("solid food", "parent fed"))
        assertEquals("left breast", FeedingOptions.resetMethod("breast milk", "left breast"))
    }

    @Test
    fun `nursing method resets to bottle when the type stops offering it`() {
        assertEquals("bottle", FeedingOptions.resetMethod("formula", "left breast"))
        assertEquals("bottle", FeedingOptions.resetMethod("solid food", "both breasts"))
    }

    @Test
    fun `null or unknown current method resets to bottle`() {
        assertEquals("bottle", FeedingOptions.resetMethod("breast milk", null))
        assertEquals("bottle", FeedingOptions.resetMethod("breast milk", "unknown"))
    }

    @Test
    fun `labels use the server wire values with sentence case`() {
        assertEquals("Fortified milk", FeedingOptions.typeLabel("fortified breast milk"))
        assertEquals("Both breasts", FeedingOptions.methodLabel("both breasts"))
        assertEquals("Solids", FeedingOptions.typeLabel("solid food"))
    }

    @Test
    fun `first-use fallback is breast milk plus bottle`() {
        assertEquals("breast milk", FeedingOptions.DEFAULT_TYPE)
        assertEquals("bottle", FeedingOptions.DEFAULT_METHOD)
    }
}
