package app.dandle.ui.theme

import androidx.compose.ui.graphics.Color
import app.dandle.domain.model.EventType

/**
 * Semantic per-type colors (Brief 03 §3): the six type hues are INFORMATION, not decoration —
 * they do not follow dynamic color. Hue families match the original app's tints (muscle-memory
 * compatibility: `babybuddy-for-android` values/colors.xml — diaper pale yellow, sleep periwinkle,
 * feeding pale rose, pumping magenta, tummy pale green) expressed as M3 tone pairs: container
 * ≈ tone 90 / on-container ≈ tone 30 in light theme, inverted in dark. Text pairs meet ≥4.5:1
 * (ThemePaletteTest pins the ratios); icon-on-container ≥3:1.
 *
 * These are the ONLY type colors in the app: timeline icon chips, running-timer card tiles,
 * quick-action running highlight, and widget running accents all read from here so a type is
 * the same color everywhere it appears.
 */
data class TypeColorPair(val container: Color, val onContainer: Color)

object TypeColors {

    /** Raw ARGB pairs (container to onContainer), shared with the widget layer (Glance needs ColorProvider, not Color). */
    fun argbPair(type: EventType, darkTheme: Boolean): Pair<Int, Int> {
        val (container, on) = when (type) {
            EventType.FEEDING -> if (darkTheme) FEEDING_DARK to FEEDING_ON_DARK else FEEDING_LIGHT to FEEDING_ON_LIGHT
            EventType.CHANGE -> if (darkTheme) CHANGE_DARK to CHANGE_ON_DARK else CHANGE_LIGHT to CHANGE_ON_LIGHT
            EventType.SLEEP -> if (darkTheme) SLEEP_DARK to SLEEP_ON_DARK else SLEEP_LIGHT to SLEEP_ON_LIGHT
            EventType.NOTE -> if (darkTheme) NOTE_DARK to NOTE_ON_DARK else NOTE_LIGHT to NOTE_ON_LIGHT
            EventType.PUMPING -> if (darkTheme) PUMPING_DARK to PUMPING_ON_DARK else PUMPING_LIGHT to PUMPING_ON_LIGHT
            EventType.TUMMY_TIME -> if (darkTheme) TUMMY_DARK to TUMMY_ON_DARK else TUMMY_LIGHT to TUMMY_ON_LIGHT
        }
        return container.toInt() to on.toInt()
    }

    fun pair(type: EventType, darkTheme: Boolean): TypeColorPair {
        val (container, on) = argbPair(type, darkTheme)
        return TypeColorPair(Color(container), Color(on))
    }

    // Fixed pairs (Brief 03 §3): container ≈ tone 90, on ≈ tone 30 in light; inverted in dark.
    private const val FEEDING_LIGHT = 0xFFFFD9DC
    private const val FEEDING_ON_LIGHT = 0xFF5C1120
    private const val FEEDING_DARK = 0xFF6B2E3B
    private const val FEEDING_ON_DARK = 0xFFFFD9DC

    private const val CHANGE_LIGHT = 0xFFF2E59E
    private const val CHANGE_ON_LIGHT = 0xFF443C00
    private const val CHANGE_DARK = 0xFF574B00
    private const val CHANGE_ON_DARK = 0xFFF2E59E

    private const val SLEEP_LIGHT = 0xFFDFE0FF
    private const val SLEEP_ON_LIGHT = 0xFF1A2050
    private const val SLEEP_DARK = 0xFF3C4273
    private const val SLEEP_ON_DARK = 0xFFDFE0FF

    private const val PUMPING_LIGHT = 0xFFF7D7F0
    private const val PUMPING_ON_LIGHT = 0xFF551049
    private const val PUMPING_DARK = 0xFF6D2A60
    private const val PUMPING_ON_DARK = 0xFFF7D7F0

    private const val TUMMY_LIGHT = 0xFFC4EBC6
    private const val TUMMY_ON_LIGHT = 0xFF0D3A14
    private const val TUMMY_DARK = 0xFF2B5531
    private const val TUMMY_ON_DARK = 0xFFC4EBC6

    private const val NOTE_LIGHT = 0xFFCFE5E4
    private const val NOTE_ON_LIGHT = 0xFF0F3C3A
    private const val NOTE_DARK = 0xFF204D4B
    private const val NOTE_ON_DARK = 0xFFCFE5E4
}
