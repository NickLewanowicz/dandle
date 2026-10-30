package app.dandle.ui.dashboard

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import app.dandle.domain.model.EventType
import app.dandle.ui.theme.LocalReduceMotion
import kotlin.math.roundToInt

/**
 * Themed scene cards for running timers (design framework v2.3 rule 4): one motif per
 * activity — sleep = night, pumping = milk, tummy time = sun, feeding = bottle. A scene is a
 * SUBDUED BACKGROUND, never content: gradient + a few ambient decorations drawn z-below the
 * card's content, and a fixed content color set whose pairs all clear WCAG AA (≥4.5:1) — the
 * pairs are fixed per scene, so the card reads identically in the app's light and dark themes
 * (a night scene is dark, period; pinned by TimerSceneTest).
 *
 * Colors are stored as ARGB `Long`s (mock-fidelity source:
 * docs/design/candidates/dandle-ux-v3/mocks.html) so the contrast math in TimerSceneTest stays
 * free of any Compose dependency. A few values deliberately DEVIATE from the mock for
 * accessibility (owner: "contrast is vital") — the small-text link/accent colors, which missed
 * AA over the composited surfaces and were darkened: night Adjust #7ab0ff → #9CC3FF, milk
 * #e88a2d → #8A4608, sun #b47b28 → #7A4E0E, bottle #3f6ea5 → #35608F; the milk/sun/bottle save
 * glyphs were darkened the same way.
 */
enum class TimerSceneMotif { NIGHT, MILK, SUN, BOTTLE }

data class TimerSceneSpec(
    val motif: TimerSceneMotif,
    /** Gradient color stops as (position, ARGB) — mirrors the mock's linear-gradients. */
    val gradientStops: List<Pair<Float, Long>>,
    /** Primary text (name, counter, save glyph). */
    val content: Long,
    /** Secondary text (started row, field labels) — ≥4.5:1 over the gradient's worst case. */
    val contentDim: Long,
    val iconChip: Long,
    val saveBg: Long,
    val saveIcon: Long,
    /** Input field surface; on the night scene a light veil (never dark-on-dark). */
    val fieldBg: Long,
    val fieldText: Long,
    val fieldHint: Long,
    /** Links/buttons ("Adjust", steps). */
    val accent: Long,
    /** The stop-form band surface + its emphasized Save button. */
    val bandBg: Long,
    val saveStrongBg: Long,
    val saveStrongText: Long,
    /** Destructive ("Discard") on the band, tuned per scene lightness. */
    val destructive: Long,
    /** Choice chips (feeding cascade). The SELECTED pill is near-solid, so on dark scenes
     *  its text flips dark ([chipSelectedText]) while unselected pills keep [content]. */
    val chipBg: Long = 0x24FFFFFF,
    val chipSelectedBg: Long = 0xEBFFFFFF,
    val chipSelectedText: Long = 0xFF1A2050,
)

/** Scene for a timed type; null for non-card types (diaper/note are sheets, no scene). */
fun sceneFor(type: EventType): TimerSceneSpec? = when (type) {
    EventType.SLEEP -> TimerSceneSpec(
        motif = TimerSceneMotif.NIGHT,
        gradientStops = listOf(
            0f to 0xFF071233,
            0.58f to 0xFF0D2A6B,
            0.77f to 0xFF173E9E, // mock's 130% endpoint clamps to the last color
        ),
        content = 0xFFFFFFFF,
        contentDim = 0xC7FFFFFF,
        iconChip = 0x665A78FF,
        saveBg = 0x805A78FF,
        saveIcon = 0xFFFFFFFF,
        fieldBg = 0x21FFFFFF,
        fieldText = 0xFFFFFFFF,
        fieldHint = 0xCCFFFFFF,
        accent = 0xFF9CC3FF,
        bandBg = 0x2E000000,
        saveStrongBg = 0xFF3D55C8,
        saveStrongText = 0xFFFFFFFF,
        destructive = 0xFFFFC9C2,
        chipBg = 0x24FFFFFF,
        chipSelectedBg = 0xEBFFFFFF,
        chipSelectedText = 0xFF1A2050,
    )
    EventType.PUMPING -> TimerSceneSpec(
        motif = TimerSceneMotif.MILK,
        gradientStops = listOf(0f to 0xFFFFF3E4, 0.77f to 0xFFFFE3EE),
        content = 0xFF7A4A2B,
        contentDim = 0xE67A4A2B,
        iconChip = 0x4DE88A2D,
        saveBg = 0x66E88A2D,
        saveIcon = 0xFF7A4A2B,
        fieldBg = 0x0F000000,
        fieldText = 0xFF7A4A2B,
        fieldHint = 0xE67A4A2B,
        accent = 0xFF8A4608,
        bandBg = 0x0F000000,
        saveStrongBg = 0xFF7A4A2B,
        saveStrongText = 0xFFFFFFFF,
        destructive = 0xFFB3261E,
    )
    EventType.TUMMY_TIME -> TimerSceneSpec(
        motif = TimerSceneMotif.SUN,
        gradientStops = listOf(0f to 0xFFFDF3E0, 0.77f to 0xFFFFE8C7),
        content = 0xFF7A5522,
        contentDim = 0xE67A5522,
        iconChip = 0x40D8963C,
        saveBg = 0x66D8963C,
        saveIcon = 0xFF6B4A1D,
        fieldBg = 0x0F000000,
        fieldText = 0xFF7A5522,
        fieldHint = 0xE67A5522,
        accent = 0xFF7A4E0E,
        bandBg = 0x0F000000,
        saveStrongBg = 0xFF7A5522,
        saveStrongText = 0xFFFFFFFF,
        destructive = 0xFFB3261E,
    )
    EventType.FEEDING -> TimerSceneSpec(
        motif = TimerSceneMotif.BOTTLE,
        gradientStops = listOf(0f to 0xFFE7F3FF, 0.77f to 0xFFD5E9FF),
        content = 0xFF28527A,
        contentDim = 0xE628527A,
        iconChip = 0x4D5A8CC8,
        saveBg = 0x665A8CC8,
        saveIcon = 0xFF1F4467,
        fieldBg = 0x0F000000,
        fieldText = 0xFF28527A,
        fieldHint = 0xE628527A,
        accent = 0xFF35608F,
        bandBg = 0x0F000000,
        saveStrongBg = 0xFF28527A,
        saveStrongText = 0xFFFFFFFF,
        destructive = 0xFFB3261E,
    )
    EventType.CHANGE, EventType.NOTE -> null
}

/** The card background brush from the spec's stops. */
fun TimerSceneSpec.backgroundBrush(): Brush = Brush.linearGradient(
    *gradientStops.map { (position, argb) -> position to Color(argb) }.toTypedArray(),
)

/** ARGB over-composite (fg over bg) — shared with TimerSceneTest's contrast math. */
fun compositeOver(fg: Long, bg: Long): Long {
    fun ch(argb: Long, shift: Int): Long = (argb shr shift) and 0xFFL
    val fa = ch(fg, 24)
    fun mix(shift: Int): Long {
        val f = ch(fg, shift)
        val b = ch(bg, shift)
        return (f * fa + b * (0xFFL - fa)) / 0xFFL
    }
    return (0xFFL shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
}

/**
 * Ambient scene decorations, z-below the card content (compose order guarantees that — the
 * backdrop is the first child of the card Box). Night: crescent moon + three twinkling stars;
 * milk: floating drops; sun: one soft halo; bottle: gradient only. ALL motion is ambient and
 * gates on [LocalReduceMotion] (framework rule 9) — with reduce-motion the scenes are static.
 */
@Composable
fun SceneBackdrop(spec: TimerSceneSpec, modifier: Modifier = Modifier) {
    val reduceMotion = LocalReduceMotion.current
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val width = maxWidth
        when (spec.motif) {
            TimerSceneMotif.NIGHT -> {
                // Crescent: gold circle with an offset circle punched out (offscreen layer
                // confines the Clear blend to this canvas — never the gradient behind).
                // Parked LEFT of the header's save chip — the mock's far-edge position made
                // the crescent collide with the save control and read as an artifact.
                Canvas(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 8.dp, end = 74.dp)
                        .size(26.dp)
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
                ) {
                    drawCircle(Color(0xFFFFD98A), radius = size.minDimension / 2f)
                    drawCircle(
                        Color.Transparent,
                        radius = size.minDimension / 2f,
                        center = center + Offset(x = 7.dp.toPx(), y = -3.dp.toPx()),
                        blendMode = BlendMode.Clear,
                    )
                }
                Star(Modifier.offset(x = width * 0.56f, y = 14.dp), delayMillis = 0, reduceMotion)
                Star(Modifier.offset(x = width * 0.78f, y = 44.dp), delayMillis = 800, reduceMotion)
                Star(Modifier.offset(x = width * 0.32f, y = 34.dp), delayMillis = 1_600, reduceMotion)
            }

            TimerSceneMotif.MILK -> {
                // Drops stay in the HEADER band of the card: the fields below are translucent,
                // and a drop drifting behind them reads as a blob inside the input (rule 9:
                // ambient motion never shares space with content).
                FloatingDrop(
                    Modifier.offset(x = width * 0.14f, y = 10.dp),
                    delayMillis = 0,
                    reduceMotion,
                )
                FloatingDrop(
                    Modifier.offset(x = width * 0.5f, y = 26.dp),
                    delayMillis = 1_300,
                    reduceMotion,
                )
                FloatingDrop(
                    Modifier.offset(x = width * 0.88f, y = 52.dp),
                    delayMillis = 2_600,
                    reduceMotion,
                )
            }

            TimerSceneMotif.SUN -> {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 18.dp, y = (-18).dp)
                        .size(88.dp)
                        .background(Color(0x2ED8963C), androidx.compose.foundation.shape.CircleShape),
                )
            }

            TimerSceneMotif.BOTTLE -> Unit // gradient only — the feeding card is busy enough
        }
    }
}

/** One twinkling star (2dp dot, opacity .15→.9 over 2.6s, staggered). Static when reduced. */
@Composable
private fun Star(modifier: Modifier = Modifier, delayMillis: Int, reduceMotion: Boolean) {
    val alpha = if (reduceMotion) {
        0.5f
    } else {
        val transition = rememberInfiniteTransition(label = "star-twinkle")
        val twinkle by transition.animateFloat(
            initialValue = 0.15f,
            targetValue = 0.9f,
            animationSpec = infiniteRepeatable(
                animation = tween(1_300, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
                initialStartOffset = StartOffset(delayMillis),
            ),
            label = "twinkle",
        )
        twinkle
    }
    Box(
        modifier = modifier
            .size(2.dp)
            .background(Color(0xFFCFE0FF).copy(alpha = alpha), androidx.compose.foundation.shape.CircleShape),
    )
}

/** One floating milk drop (a small capsule drifting ±10px, staggered). Static when reduced. */
@Composable
private fun FloatingDrop(modifier: Modifier = Modifier, delayMillis: Int, reduceMotion: Boolean) {
    val drift = if (reduceMotion) {
        0f
    } else {
        val transition = rememberInfiniteTransition(label = "drop-float")
        val float by transition.animateFloat(
            initialValue = -10f,
            targetValue = 10f,
            animationSpec = infiniteRepeatable(
                animation = tween(1_900, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
                initialStartOffset = StartOffset(delayMillis),
            ),
            label = "float",
        )
        float
    }
    val density = androidx.compose.ui.platform.LocalDensity.current
    Box(
        modifier = modifier
            // Lambda offset: the float animates the placement pass, not recomposition.
            .offset { androidx.compose.ui.unit.IntOffset(0, (drift * density.density).roundToInt()) }
            .size(width = 9.dp, height = 13.dp)
            .clip(RoundedCornerShape(50))
            .background(Color(0x4DE88A2D)),
    )
}
