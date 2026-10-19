package net.babybuddy.next.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Spec §8: the type scale favors glanceability — durations, counters, and "time since" values are
 * the most prominent text on their surface, in tabular figures (UX spec standing rules).
 */
val AppTypography = Typography()

/** Timer counters (running-timer cards): headline-size, tabular, HH:MM:ss. */
val TimerCounterStyle = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = FontWeight.Medium,
    fontSize = 28.sp,
    lineHeight = 34.sp,
    fontFeatureSettings = "tnum",
)

/** Timeline durations / "time since" values: tabular figures so columns read steady. */
val DurationStyle = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = FontWeight.Medium,
    fontSize = 14.sp,
    lineHeight = 20.sp,
    fontFeatureSettings = "tnum",
)
