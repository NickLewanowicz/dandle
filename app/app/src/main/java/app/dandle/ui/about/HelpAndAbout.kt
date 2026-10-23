package app.dandle.ui.about

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import app.dandle.BuildConfig
import app.dandle.domain.model.EventType
import app.dandle.ui.dashboard.iconRes

/**
 * Help slideshow (spec §9, UX spec §7 spirit): short, original content — what the six buttons
 * do, timer semantics, offline/pending/failed, widgets. No animation beyond the pager swipe.
 */
@Composable
fun HelpDialog(onDismiss: () -> Unit) {
    val pages = listOf(
        HelpPage(
            title = "Six buttons",
            icon = EventType.FEEDING.iconRes(),
            body = "Feeding, Diaper, Sleep, Notes, Pumping, and Tummy time log events for the " +
                "selected child. Feeding, Sleep, Pumping, and Tummy time run a timer; Diaper and " +
                "Notes save instantly.",
        ),
        HelpPage(
            title = "Timers",
            icon = EventType.SLEEP.iconRes(),
            body = "Tap a timer button to start, then tap it again (or use the timer card) to " +
                "stop. Under 10 seconds the tap is treated as a mis-tap and discards quietly; " +
                "longer timers ask \"Stop timer?\" with Save, Discard, and Keep. Timers survive " +
                "restarts.",
        ),
        HelpPage(
            title = "Offline first",
            icon = EventType.CHANGE.iconRes(),
            body = "Every save lands on this phone immediately and syncs to your server when " +
                "reachable. \"Will sync\" marks entries that are queued. \"Sync failed\" needs " +
                "you: tap it to edit, retry, or delete — nothing is ever dropped silently. " +
                "Long-press any entry to open it in your server's web UI.",
        ),
        HelpPage(
            title = "Widgets",
            icon = EventType.PUMPING.iconRes(),
            body = "The Quick actions widget puts four buttons (sleep timer, bottle, diaper, " +
                "pump) on your home screen; the Care dashboard shows the last feed, diaper, nap, " +
                "and pump. Both update right after saves and syncs, and at least every 15 " +
                "minutes. Long-press a widget to configure it.",
        ),
    )
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val pagerScope = androidx.compose.runtime.rememberCoroutineScope()

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                HorizontalPager(state = pagerState, modifier = Modifier.height(220.dp)) { page ->
                    HelpPageContent(pages[page])
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        repeat(pages.size) { index ->
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (index == pagerState.currentPage) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        }
                                    ),
                            )
                        }
                    }
                    Box(modifier = Modifier.weight(1f))
                    if (pagerState.currentPage < pages.lastIndex) {
                        TextButton(onClick = {
                            pagerScope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        }) {
                            Text("Next")
                        }
                    } else {
                        TextButton(onClick = onDismiss) { Text("Done") }
                    }
                }
            }
        }
    }
}

private data class HelpPage(val title: String, val icon: Int, val body: String)

@Composable
private fun HelpPageContent(page: HelpPage) {
    Column {
        androidx.compose.material3.Icon(
            painter = painterResource(page.icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(32.dp),
        )
        Text(
            page.title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            page.body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * About (spec §9): version, our license note, and credit + links for both upstream projects
 * this app builds on.
 */
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("About") },
        text = {
            Column {
                Text(
                    "Baby Buddy Next ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "A companion app for self-hosted Baby Buddy servers. All data lives on " +
                        "your own server and this phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    "Upstream",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 14.dp),
                )
                Text(
                    "Baby Buddy server — BSD-2-Clause — github.com/babybuddy/babybuddy",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    "Baby Buddy for Android — MIT, © 2022 Paul Konstantin Gerke — " +
                        "github.com/babybuddy/babybuddy-for-android",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    "This app is an independent client with original artwork and code; no " +
                        "assets are copied from either upstream project.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("OK") }
        },
    )
}
