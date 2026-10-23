package app.dandle.ui.theme

import androidx.compose.ui.graphics.Color

// Static fallback palette (dynamic color is used on Android 12+). Spec §8: "self-hosted apps
// shouldn't scream brand" — a muted dusk-indigo seed with warm neutrals, calm in both modes.
val SeedPrimary = Color(0xFF5B5A96)
val SeedPrimaryDark = Color(0xFFC3C1FF)
val SeedTertiary = Color(0xFF7A5368)

val LightPrimary = Color(0xFF5B5A96)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFE2DFFF)
val LightOnPrimaryContainer = Color(0xFF171547)
val LightSecondary = Color(0xFF5D5C72)
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFE2E0F9)
val LightOnSecondaryContainer = Color(0xFF1A1A2C)
val LightTertiary = Color(0xFF7A5368)
val LightOnTertiary = Color(0xFFFFFFFF)
val LightTertiaryContainer = Color(0xFFFFD8E8)
val LightOnTertiaryContainer = Color(0xFF2E1120)
val LightError = Color(0xFFBA1A1A)
val LightOnError = Color(0xFFFFFFFF)
val LightErrorContainer = Color(0xFFFFDAD6)
val LightOnErrorContainer = Color(0xFF410002)
val LightBackground = Color(0xFFFCF8FF)
val LightOnBackground = Color(0xFF1B1B21)
val LightSurface = Color(0xFFFCF8FF)
val LightOnSurface = Color(0xFF1B1B21)
val LightSurfaceVariant = Color(0xFFE4E1EC)
val LightOnSurfaceVariant = Color(0xFF46464F)
val LightOutline = Color(0xFF777680)
val LightOutlineVariant = Color(0xFFC8C5D0)

val DarkPrimary = Color(0xFFC3C1FF)
val DarkOnPrimary = Color(0xFF2A2870)
val DarkPrimaryContainer = Color(0xFF413E8D)
val DarkOnPrimaryContainer = Color(0xFFE2DFFF)
val DarkSecondary = Color(0xFFC6C4DD)
val DarkOnSecondary = Color(0xFF2F2F42)
val DarkSecondaryContainer = Color(0xFF464559)
val DarkOnSecondaryContainer = Color(0xFFE2E0F9)
val DarkTertiary = Color(0xFFEAB9D1)
val DarkOnTertiary = Color(0xFF482538)
val DarkTertiaryContainer = Color(0xFF613B4F)
val DarkOnTertiaryContainer = Color(0xFFFFD8E8)
val DarkError = Color(0xFFFFB4AB)
val DarkOnError = Color(0xFF690005)
val DarkErrorContainer = Color(0xFF93000A)
val DarkOnErrorContainer = Color(0xFFFFDAD6)
val DarkBackground = Color(0xFF131318)
val DarkOnBackground = Color(0xFFE4E1E9)
val DarkSurface = Color(0xFF131318)
val DarkOnSurface = Color(0xFFE4E1E9)
val DarkSurfaceVariant = Color(0xFF46464F)
val DarkOnSurfaceVariant = Color(0xFFC8C5D0)
val DarkOutline = Color(0xFF918F9A)
val DarkOutlineVariant = Color(0xFF46464F)

// Content palette for diaper-color swatches (UX spec §2: swatches are content, not theme roles —
// fixed so "yellow" looks yellow in dark mode too). Verified server enum: research doc §4 lists
// black, brown, gray, green, orange, red, white, yellow (+ empty). The pink/red display label in
// the spec maps to the server's "red" value.
val DiaperColorSwatches: Map<String, Color> = mapOf(
    "black" to Color(0xFF2B2B2B),
    "brown" to Color(0xFF795548),
    "gray" to Color(0xFF9E9E9E),
    "green" to Color(0xFF66BB6A),
    "orange" to Color(0xFFFB8C00),
    "red" to Color(0xFFE53935),
    "white" to Color(0xFFFAFAFA),
    "yellow" to Color(0xFFFDD835),
)
