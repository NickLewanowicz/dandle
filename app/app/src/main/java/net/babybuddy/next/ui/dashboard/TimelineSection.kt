package net.babybuddy.next.ui.dashboard

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.animation.animateContentSize
import net.babybuddy.next.domain.ElapsedTimeFormatter
import net.babybuddy.next.domain.model.Event
import net.babybuddy.next.domain.model.EventType
import net.babybuddy.next.domain.model.EventHeadline
import net.babybuddy.next.domain.model.SyncState
import net.babybuddy.next.ui.TestTags
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val timeOfDayFormat = DateTimeFormatter.ofPattern("HH:mm")

/**
 * The merged six-type timeline (spec §4): day-grouped, newest first, rows with type icon +
 * headline + note preview + sync badge, trailing time-of-day and duration. Pull-to-refresh and
 * paged older-load are handled by the caller-provided callbacks; rows are clickable (Quick Edit
 * arrives in Stage 3) and long-pressable (opens the entry on the Baby Buddy web UI).
 */
@Composable
fun TimelineSection(
    events: List<Event>,
    totalCount: Int,
    loadingOlder: Boolean,
    onTapRow: (Event) -> Unit,
    onLongPressRow: (Event) -> Unit,
    onLoadOlder: (visibleCount: Int) -> Unit,
    modifier: Modifier = Modifier,
    scrollToUuid: String? = null,
) {
    if (totalCount == 0) {
        EmptyTimeline(modifier = modifier)
        return
    }
    val listState = rememberLazyListState()
    // "View last entry" (duplicate guard, spec §3.7): jump to the named row when present.
    LaunchedEffect(scrollToUuid, events.size) {
        if (scrollToUuid == null) return@LaunchedEffect
        val index = events.indexOfFirst { it.localUuid == scrollToUuid }
        if (index >= 0) {
            listState.animateScrollToItem(index)
        }
    }
    // Paged older-load: when the last visible item closes in on the end of the window, ask
    // the ViewModel for more (it reveals local rows first, then fetches older pages).
    LaunchedEffect(events.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { last ->
                if (events.isNotEmpty() && last >= events.size - 3) onLoadOlder(events.size)
            }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .testTag(TestTags.DASHBOARD_TIMELINE),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(events, key = { it.localUuid }) { event ->
            TimelineRow(
                event = event,
                showDayHeader = needsHeader(events, event),
                onTap = { onTapRow(event) },
                onLongPress = { onLongPressRow(event) },
                modifier = Modifier.animateItem(),
            )
        }
        if (loadingOlder) {
            item(key = "loading-older") {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}

/** Fresh-server empty state (UX spec §2): static line art + one line of copy. */
@Composable
fun EmptyTimeline(modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp)
            .testTag(TestTags.DASHBOARD_EMPTY_TIMELINE),
    ) {
        Row {
            Icon(
                painterResource(EventType.SLEEP.iconRes()),
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(16.dp))
            Icon(
                painterResource(EventType.FEEDING.iconRes()),
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "Log your first event with a button above.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TimelineRow(
    event: Event,
    showDayHeader: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.animateContentSize()) {
        if (showDayHeader) {
            Text(
                dayLabel(event.startMillis),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 4.dp)
                    .testTag(TestTags.DAY_HEADER),
            )
            HorizontalDivider()
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onTap, onLongClick = onLongPress)
                .padding(vertical = 8.dp)
                .testTag(TestTags.timelineRow(event.localUuid)),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(40.dp),
            ) {
                androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painterResource(event.type.iconRes()),
                        contentDescription = event.type.label,
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        EventHeadline.headline(event),
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = if (event.type == EventType.NOTE) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (event.type == EventType.CHANGE && !event.color.isNullOrBlank()) {
                        androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
                        ColorSwatchDot(DiaperSwatches.colorOf(event.color))
                        Text(
                            " " + DiaperSwatches.labelOf(event.color),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    event.noteText?.takeIf { it.isNotBlank() }?.let { note ->
                        Text(
                            note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                    }
                    SyncBadge(
                        state = SyncState.valueOf(event.syncState.name),
                        onTapFailed = onTap, // FAILED badge tap → failure dialog (Stage 3: dialog)
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    timeOfDayFormat.format(
                        Instant.ofEpochMilli(event.startMillis).atZone(ZoneId.systemDefault()),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (event.type.isTimed && event.durationMillis > 0) {
                    Text(
                        ElapsedTimeFormatter.durationShort(event.durationMillis),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun needsHeader(events: List<Event>, event: Event): Boolean {
    val index = events.indexOf(event)
    if (index == 0) return true
    val prev = events[index - 1]
    return !java.time.LocalDate.ofEpochDay(prev.startMillis / 86_400_000)
        .equals(java.time.LocalDate.ofEpochDay(event.startMillis / 86_400_000))
}

fun dayLabel(epochMillis: Long): String {
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
    val today = java.time.LocalDate.now(zone)
    return when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.month.getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + date.dayOfMonth
    }
}
