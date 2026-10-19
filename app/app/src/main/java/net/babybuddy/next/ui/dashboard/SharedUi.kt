package net.babybuddy.next.ui.dashboard

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import net.babybuddy.next.R
import net.babybuddy.next.domain.model.EventType
import net.babybuddy.next.domain.model.SyncState
import net.babybuddy.next.ui.TestTags

/** Icon set (one drawable per event type — our own artwork, spec §8). */
fun EventType.iconRes(): Int = when (this) {
    EventType.FEEDING -> R.drawable.ic_bb_feeding
    EventType.CHANGE -> R.drawable.ic_bb_diaper
    EventType.SLEEP -> R.drawable.ic_bb_sleep
    EventType.NOTE -> R.drawable.ic_bb_notes
    EventType.PUMPING -> R.drawable.ic_bb_pumping
    EventType.TUMMY_TIME -> R.drawable.ic_bb_tummy_time
}

/**
 * Icon for a FEEDING entry, differentiated by side (persona round 2 / owner directive,
 * mimicking the original app's clarity): breast pairs mark the FED side solid and the
 * other dashed with a corner letter; bottle/parent-fed/self-fed keep the bottle.
 */
fun feedingIconRes(feedingMethod: String?): Int = when (feedingMethod) {
    "left breast" -> R.drawable.ic_bb_feeding_left
    "right breast" -> R.drawable.ic_bb_feeding_right
    "both breasts" -> R.drawable.ic_bb_feeding_both
    else -> R.drawable.ic_bb_feeding
}

/** Display label used across strip, panels, dialogs. */
val EventType.label: String
    get() = when (this) {
        EventType.FEEDING -> "Feeding"
        EventType.CHANGE -> "Diaper"
        EventType.SLEEP -> "Sleep"
        EventType.NOTE -> "Notes"
        EventType.PUMPING -> "Pumping"
        EventType.TUMMY_TIME -> "Tummy time"
    }

/**
 * Diaper color swatches (spec §3.2): fixed content palette so "yellow looks yellow in dark mode
 * too — swatches are content, not theme roles" (UX spec §3). Wire values are exact server enums
 * (research doc §4: black/brown/green/yellow/red among others); "Pink/red" maps to `red`.
 */
object DiaperSwatches {
    data class Swatch(val wire: String, val label: String, val color: Color)

    val ALL = listOf(
        Swatch("black", "Black", Color(0xFF1A1A1A)),
        Swatch("brown", "Brown", Color(0xFF795548)),
        Swatch("green", "Green", Color(0xFF43A047)),
        Swatch("yellow", "Yellow", Color(0xFFEBD233)),
        Swatch("red", "Pink/red", Color(0xFFE91E63)),
    )

    fun colorOf(wire: String?): Color = ALL.firstOrNull { it.wire == wire }?.color ?: Color(0xFF9E9E9E)

    fun labelOf(wire: String?): String = ALL.firstOrNull { it.wire == wire }?.label
        ?: wire?.replaceFirstChar { it.uppercase() }
        ?: ""
}

/**
 * Header sync-health chip states (UX spec §5); hidden is the clean default. Owner rework U7:
 * FAILED is deliberately absent — failed entries surface on their timeline row (warning badge on
 * the type icon), never duplicated as a header chip.
 */
sealed interface SyncChipState {
    data object Hidden : SyncChipState
    data object Offline : SyncChipState
    data object Syncing : SyncChipState

    /** T3: transient settle after a drain completes — the visible "caught up" moment. */
    data object AllSynced : SyncChipState
}

fun syncChipState(
    isOnline: Boolean,
    syncing: Boolean,
    allSynced: Boolean = false,
): SyncChipState = when {
    !isOnline -> SyncChipState.Offline
    syncing -> SyncChipState.Syncing
    allSynced -> SyncChipState.AllSynced
    else -> SyncChipState.Hidden
}

/**
 * The type's semantic color pair (Brief 03 §3, owner rework U11): same hue family as the
 * original app, fixed across themes with light/dark tone pairs. One source for timeline icon
 * chips, running-timer card tiles, quick-action running highlight, and widget accents.
 */
@Composable
fun EventType.typeColorPair(): net.babybuddy.next.ui.theme.TypeColorPair =
    net.babybuddy.next.ui.theme.TypeColors.pair(this, androidx.compose.foundation.isSystemInDarkTheme())

@Composable
fun SyncStatusChip(state: SyncChipState, onTap: () -> Unit) {
    Crossfade(targetState = state, animationSpec = tween(150), label = "sync-chip") { current ->
        when (current) {
            SyncChipState.Hidden -> {}
            SyncChipState.Offline -> Surface(
                onClick = onTap,
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.testTag(TestTags.DASHBOARD_SYNC_CHIP),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_bb_cloud_off),
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Offline",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }

            SyncChipState.Syncing -> Surface(
                onClick = onTap,
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.testTag(TestTags.DASHBOARD_SYNC_CHIP),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        "Syncing…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }

            // T3: the calm "caught up" moment after queued entries land (settles a few seconds).
            SyncChipState.AllSynced -> Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.testTag(TestTags.DASHBOARD_SYNC_CHIP),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        "All synced ✓",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
    }
}

/**
 * Per-entry sync badge (UX spec §5, T3): SYNCED renders nothing; PENDING is a tonal pill —
 * a real badge, not bare muted text (Elena looked for the promised "Will sync" mark and found
 * none; it must read at arm's length). FAILED renders nothing HERE (owner rework U7): the row
 * marks it with a warning badge overlaid on the type icon (see [FailedIconBadge]) so the row
 * keeps its normal height; the row tap opens the failure dialog. Transitions fade at 150 ms.
 */
@Composable
fun SyncBadge(state: SyncState, modifier: Modifier = Modifier) {
    Crossfade(targetState = state, animationSpec = tween(150), label = "sync-badge", modifier = modifier) { current ->
        when (current) {
            SyncState.SYNCED, SyncState.FAILED -> {}
            SyncState.PENDING -> Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.testTag(TestTags.BADGE_PENDING),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_bb_clock),
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        "Will sync",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(start = 3.dp),
                    )
                }
            }
        }
    }
}

/**
 * The FAILED marker (owner rework U7): a small error-colored dot with the warning glyph, meant
 * to sit at the top-end corner of a type icon chip — the "caution over the primary icon" the
 * owner asked for. Tapping the row explains the failure.
 */
@Composable
fun FailedIconBadge(modifier: Modifier = Modifier) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.error,
        modifier = modifier
            .size(16.dp)
            .testTag(TestTags.BADGE_FAILED),
    ) {
        androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
            Icon(
                painterResource(R.drawable.ic_bb_warning),
                contentDescription = "Sync failed",
                modifier = Modifier.size(10.dp),
                tint = MaterialTheme.colorScheme.onError,
            )
        }
    }
}

/** Small colored dot used for diaper color previews in timeline rows. */
@Composable
fun ColorSwatchDot(color: Color, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .size(10.dp)
            .background(color, CircleShape),
    )
}

/**
 * The standard keyboard "Done" behavior for every single-line field in the app
 * (owner rework follow-up): Done dismisses the keyboard AND clears focus. A
 * still-focused field lets UiAutomator's accessibility pass re-summon the IME
 * on every dump, which kept moving the window layout under the QA scripts.
 */
@Composable
fun doneKeyboardActions(): androidx.compose.foundation.text.KeyboardActions {
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    return androidx.compose.foundation.text.KeyboardActions(onDone = { focusManager.clearFocus(force = true) })
}

val imeDoneOptions: androidx.compose.foundation.text.KeyboardOptions =
    androidx.compose.foundation.text.KeyboardOptions(
        imeAction = androidx.compose.ui.text.input.ImeAction.Done,
    )
