package app.dandle.ui.theme

import android.provider.Settings
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * True when the user asked the system for reduced motion (Developer options / Accessibility
 * "Remove animations" → animator duration scale 0). Ambient decoration (scene star twinkle,
 * drop float) MUST gate on this — motion is ambience, never information (design framework
 * v2.3 rule 9), so with reduce-motion on the scenes render fully static and every state
 * change still communicates through content, not animation.
 *
 * Provided once per composition by [BabyBuddyNextTheme] (read at composition time; users
 * flipping the system setting get the new value on the next activity recreation — a
 * live ContentObserver would re-render the whole tree for a setting that changes rarely).
 */
val LocalReduceMotion = staticCompositionLocalOf { false }

/** Reads the system animator duration scale; 0 means "remove animations". */
fun reducedMotionSetting(resolver: android.content.ContentResolver): Boolean =
    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
