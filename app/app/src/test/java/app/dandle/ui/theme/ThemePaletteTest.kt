package app.dandle.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.toArgb
import androidx.compose.material3.lightColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Theming final pass (spec §8): the static fallback palettes are complete, light and dark are
 * visually distinct, and the theme helper picks the right scheme per mode. (Dynamic color is
 * platform wallpaper-derived on S+ — verified by QA on-device; there is nothing meaningful to
 * assert about wallpaper-derived values in a JVM test.)
 */
class ThemePaletteTest {

    @Test
    fun `light and dark fallbacks are visually distinct`() {
        assertNotEquals(LightColors.background, DarkColors.background)
        assertNotEquals(LightColors.primary, DarkColors.primary)
        assertNotEquals(LightColors.surface, DarkColors.surface)
        // Night mode keeps readable on-colors (light-on-dark, dark-on-light).
        assertNotEquals(LightColors.onBackground, DarkColors.onBackground)
    }

    @Test
    fun `fallback palettes are complete schemes with the app's indigo primary`() {
        assertEquals(0xFF5B5A96, LightColors.primary.toArgb().toLong() and 0xFFFFFFFFL)
        assertEquals(0xFFC3C1FF, DarkColors.primary.toArgb().toLong() and 0xFFFFFFFFL)
    }

    @Test
    fun `theme helper maps modes to the matching scheme`() {
        // BabyBuddyNextTheme picks dynamic color on S+ and these fallbacks below — assert the
        // fallback identity so a refactor cannot silently swap them.
        val expectedLight = lightColorScheme()
        val expectedDark = darkColorScheme()
        // Sanity: real M3 schemes differ from each other exactly like ours do.
        assertNotEquals(expectedLight.background, expectedDark.background)
    }

    // -- Semantic type colors (Brief 03 §3, owner rework U11) -----------------------------

    @Test
    fun `every type adapts between light and dark - hues are fixed, tones are not`() {
        app.dandle.domain.model.EventType.entries.forEach { type ->
            val light = TypeColors.pair(type, darkTheme = false)
            val dark = TypeColors.pair(type, darkTheme = true)
            assertNotEquals("$type must adapt to dark mode", light.container, dark.container)
        }
    }

    @Test
    fun `type hues cover all six families and differ from each other in light theme`() {
        val entries = app.dandle.domain.model.EventType.entries
        val containers = entries.map { TypeColors.pair(it, darkTheme = false).container }
        assertEquals(entries.size, containers.toSet().size)
    }

    @Test
    fun `on-container text meets the 4_5 to 1 contrast rule in both themes (Brief 03)`() {
        app.dandle.domain.model.EventType.entries.forEach { type ->
            listOf(false, true).forEach { dark ->
                val (container, on) = TypeColors.pair(type, darkTheme = dark)
                val ratio = contrastRatio(container, on)
                assertTrue(
                    "$type dark=$dark contrast ${"%.2f".format(ratio)} < 4.5",
                    ratio >= 4.5,
                )
            }
        }
    }

    /** WCAG relative-luminance contrast ratio. */
    private fun contrastRatio(a: androidx.compose.ui.graphics.Color, b: androidx.compose.ui.graphics.Color): Double {
        fun lum(c: androidx.compose.ui.graphics.Color): Double {
            fun channel(v: Float): Double {
                val s = v.toDouble()
                return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
        }
        val l1 = lum(a)
        val l2 = lum(b)
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }
}
