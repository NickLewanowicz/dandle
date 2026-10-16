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

/** Original icon set (one drawable per event type — no copied assets, spec §8). */
fun EventType.iconRes(): Int = when (this) {
    EventType.FEEDING -> R.drawable.ic_bb_feeding
    EventType.CHANGE -> R.drawable.ic_bb_diaper
    EventType.SLEEP -> R.drawable.ic_bb_sleep
    EventType.NOTE -> R.drawable.ic_bb_notes
    EventType.PUMPING -> R.drawable.ic_bb_pumping
    EventType.TUMMY_TIME -> R.drawable.ic_bb_tummy_time
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

/** Header sync-health chip states (UX spec §5); hidden is the clean default. */
sealed interface SyncChipState {
    data object Hidden : SyncChipState
    data object Offline : SyncChipState
    data object Syncing : SyncChipState
    data class Failed(val count: Int) : SyncChipState
}

fun syncChipState(isOnline: Boolean, syncing: Boolean, failedCount: Int): SyncChipState = when {
    failedCount > 0 -> SyncChipState.Failed(failedCount)
    !isOnline -> SyncChipState.Offline
    syncing -> SyncChipState.Syncing
    else -> SyncChipState.Hidden
}

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

            is SyncChipState.Failed -> Surface(
                onClick = onTap,
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.testTag(TestTags.DASHBOARD_SYNC_CHIP),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_bb_warning),
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        "${current.count} failed",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * Per-entry sync badge (UX spec §5): SYNCED renders nothing; PENDING is a muted clock +
 * "Will sync"; FAILED is the app's only filled badge (errorContainer pill, tap → failure
 * dialog). Badge transitions fade at 150 ms (motion budget).
 */
@Composable
fun SyncBadge(state: SyncState, modifier: Modifier = Modifier, onTapFailed: () -> Unit = {}) {
    Crossfade(targetState = state, animationSpec = tween(150), label = "sync-badge", modifier = modifier) { current ->
        when (current) {
            SyncState.SYNCED -> {}
            SyncState.PENDING -> Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.testTag(TestTags.BADGE_PENDING),
            ) {
                Icon(
                    painterResource(R.drawable.ic_bb_clock),
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Will sync",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 3.dp),
                )
            }

            SyncState.FAILED -> Surface(
                onClick = onTapFailed,
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.testTag(TestTags.BADGE_FAILED),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_bb_warning),
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        "Sync failed",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(start = 3.dp),
                    )
                }
            }
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
