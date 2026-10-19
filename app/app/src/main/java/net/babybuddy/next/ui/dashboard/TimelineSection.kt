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
import net.babybuddy.next.domain.TimelineDayGroups
import net.babybuddy.next.domain.model.Event
import net.babybuddy.next.domain.model.EventType
import net.babybuddy.next.domain.model.EventHeadline
import net.babybuddy.next.domain.model.SyncState
import net.babybuddy.next.ui.TestTags
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val timeOfDayFormat = DateTimeFormatter.ofPattern("HH:mm")

/**
 * The merged six-type timeline (spec §4): day-grouped, newest first, rows with type icon +
 * headline + note preview + sync marking, trailing time-of-day and duration. Pull-to-refresh and
 * paged older-load are handled by the caller-provided callbacks; rows are clickable (Quick Edit)
 * and long-pressable (opens the entry on the Baby Buddy web UI).
 *
 * [leadingItem] (owner rework U2): the running-timer card is composed as the list's FIRST item —
 * literally "an expanded top element of the list" — so it scrolls with the timeline and a focused
 * text field inside it is brought into view above the keyboard (the fixed header + grid never
 * move).
 */
@Composable
fun TimelineSection(
    events: List<Event>,
    totalCount: Int,
    loadingOlder: Boolean,
    onTapRow: (Event) -> Unit,
    onLongPressRow: (Event) -> Unit,
    onFailureBadge: (Event) -> Unit,
    onLoadOlder: () -> Unit,
    modifier: Modifier = Modifier,
    scrollToUuid: String? = null,
    leadingItem: (@Composable () -> Unit)? = null,
) {
    if (totalCount == 0) {
        Column(modifier = modifier) {
            leadingItem?.invoke()
            EmptyTimeline(modifier = Modifier.weight(1f))
        }
        return
    }
    val listState = rememberLazyListState()
    val leadingCount = if (leadingItem != null) 1 else 0
    // A keyed LazyColumn anchors its scroll position to the first visible item's KEY, so an
    // item inserted at index 0 lands ABOVE the viewport. The card must be seen the moment a
    // timer starts: reveal it explicitly.
    LaunchedEffect(leadingItem != null) {
        if (leadingItem != null) listState.animateScrollToItem(0)
    }
    // "View last entry" (duplicate guard, spec §3.7) and the post-save reveal (U2): jump to
    // the named row when present (same key-anchoring reason).
    LaunchedEffect(scrollToUuid, events.size) {
        if (scrollToUuid == null) return@LaunchedEffect
        val index = events.indexOfFirst { it.localUuid == scrollToUuid }
        if (index >= 0) {
            listState.animateScrollToItem(index + leadingCount)
        }
    }
    // Paged older-load: when the list cannot scroll FURTHER DOWN (the true end of the rendered
    // window — P1-1/P1-3 fix: a windowed-size comparison wedged the window at its initial size,
    // hiding new/edited rows beyond it), ask the ViewModel for more.
    LaunchedEffect(events.size) {
        snapshotFlow { listState.canScrollForward }
            .collect { canScrollForward ->
                if (events.isNotEmpty() && !canScrollForward) onLoadOlder()
            }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .testTag(TestTags.DASHBOARD_TIMELINE),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (leadingItem != null) {
            item(key = "leading-card") { leadingItem() }
        }
        items(events, key = { it.localUuid }) { event ->
            val failed = event.syncState == SyncState.FAILED
            TimelineRow(
                event = event,
                showDayHeader = needsHeader(events, event),
                // FAILED rows (and their badge) open the failure dialog, not the sheet (UX §5).
                onTap = { if (failed) onFailureBadge(event) else onTapRow(event) },
                onLongPress = { onLongPressRow(event) },
                onFailedBadge = { onFailureBadge(event) },
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
    onFailedBadge: () -> Unit,
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
            // U11: the type's semantic color is the scan layer; U7: a FAILED entry carries the
            // warning badge ON the icon (normal row height; the tap explains).
            val typeColors = event.type.typeColorPair()
            androidx.compose.foundation.layout.Box {
                Surface(
                    shape = CircleShape,
                    color = typeColors.container,
                    modifier = Modifier.size(40.dp),
                ) {
                    androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painterResource(event.type.iconRes()),
                            contentDescription = event.type.label,
                            modifier = Modifier.size(22.dp),
                            tint = typeColors.onContainer,
                        )
                    }
                }
                if (event.syncState == SyncState.FAILED) {
                    FailedIconBadge(
                        modifier = Modifier.align(Alignment.TopEnd),
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
                        style = MaterialTheme.typography.bodyLarge.copy(
                            // U8: note content reads as WORDS, not a structured event — italics
                            // keep it unmistakably distinct from type labels it could collide
                            // with ("Feeding interrupted" the note vs a feeding event).
                            fontStyle = if (event.type == EventType.NOTE) {
                                androidx.compose.ui.text.font.FontStyle.Italic
                            } else {
                                null
                            },
                        ),
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
                    // Note entries: headline IS the first line — don't repeat it below (UX polish).
                    val preview = event.noteText
                        ?.takeIf { it.isNotBlank() && it.trim() != EventHeadline.headline(event).trim() }
                    preview?.let { note ->
                        Text(
                            note,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                    }
                    SyncBadge(
                        state = SyncState.valueOf(event.syncState.name),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                val zone = ZoneId.systemDefault()
                val start = Instant.ofEpochMilli(event.startMillis).atZone(zone)
                val timed = event.type.isTimed && event.durationMillis > 0
                // Owner tweak (persona round 2): long entries show the END time — a 12h
                // overnight sleep as bare start + duration forces midnight math. Threshold:
                // >= 60 min, OR the entry crosses local midnight (date math nobody does at
                // 3am). Short entries keep the bare start — the end is one mental step.
                val crossesMidnight = timed && start.toLocalDate() != Instant.ofEpochMilli(event.endMillis).atZone(zone).toLocalDate()
                val showRange = timed && (event.durationMillis >= 3_600_000 || crossesMidnight)
                if (showRange) {
                    val end = Instant.ofEpochMilli(event.endMillis).atZone(zone)
                    Text(
                        timeOfDayFormat.format(start) + "–" + timeOfDayFormat.format(end),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(
                        timeOfDayFormat.format(start),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (timed) {
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

/**
 * Day groups are DEVICE-LOCAL calendar days (P1-2 fix): the old UTC-epoch-day grouping split a
 * single local day into duplicate headers whenever the rows straddled UTC midnight.
 */
private fun needsHeader(events: List<Event>, event: Event): Boolean {
    val zone = ZoneId.systemDefault()
    val index = events.indexOf(event)
    val prev = if (index == 0) null else events[index - 1]
    return TimelineDayGroups.needsNewLocalDay(prev?.startMillis, event.startMillis, zone)
}

fun dayLabel(epochMillis: Long): String =
    TimelineDayGroups.label(epochMillis, ZoneId.systemDefault(), java.time.LocalDate.now(ZoneId.systemDefault()))
