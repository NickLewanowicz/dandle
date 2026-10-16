package net.babybuddy.next.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import net.babybuddy.next.data.AppContainer
import net.babybuddy.next.domain.Clock
import net.babybuddy.next.domain.ElapsedTimeFormatter
import net.babybuddy.next.domain.model.SyncState
import net.babybuddy.next.domain.model.Event
import net.babybuddy.next.domain.model.EventHeadline
import net.babybuddy.next.domain.model.RunningTimer
import net.babybuddy.next.ui.TestTags
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Dashboard shell (spec §2). Stage 1 scope: header + child switcher + sign-out overflow,
 * six quick-action placeholders (wired in Stage 2), active timers strip, merged timeline
 * with sync badges. Full visual polish is Brief 06.
 */

private val CANONICAL_QUICK_ACTIONS = listOf("Feeding", "Diaper", "Sleep", "Notes", "Pumping", "Tummy time")

private val timeOfDayFormat = DateTimeFormatter.ofPattern("HH:mm")
private val dayFormat = DateTimeFormatter.ofPattern("MMMM d", Locale.getDefault())

@Composable
fun DashboardScreen(
    container: AppContainer,
    onSignedOut: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel = viewModel { DashboardViewModel(container, context) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showSignOutDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.refresh()
        viewModel.events.collect { event ->
            when (event) {
                DashboardEvent.SignedOut -> onSignedOut()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag(TestTags.DASHBOARD),
    ) {
        HeaderRow(
            children = state.children.map { it.firstName },
            activeChild = state.activeChild?.firstName,
            onSelectChild = { name ->
                state.children.firstOrNull { it.firstName == name }?.let { viewModel.selectChild(it.id) }
            },
            onSignOut = { showSignOutDialog = true },
        )

        ActiveTimersStrip(timers = state.runningTimers)

        QuickActionsGrid()

        Timeline(state.timeline)

        if (showSignOutDialog) {
            AlertDialog(
                onDismissRequest = { showSignOutDialog = false },
                title = { Text("Sign out?") },
                text = { Text("This clears the stored key and local data on this device. Data on the server is untouched.") },
                confirmButton = {
                    TextButton(onClick = {
                        showSignOutDialog = false
                        viewModel.signOut()
                    }) { Text("Sign out", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { showSignOutDialog = false }) { Text("Cancel") }
                },
            )
        }
    }
}

@Composable
private fun HeaderRow(
    children: List<String>,
    activeChild: String?,
    onSelectChild: (String) -> Unit,
    onSignOut: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Baby Buddy", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            AssistChip(
                onClick = { menuOpen = true },
                label = { Text(activeChild ?: "No child") },
                modifier = Modifier.testTag(TestTags.DASHBOARD_CHILD_SWITCHER),
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                children.forEach { name ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            onSelectChild(name)
                            menuOpen = false
                        },
                    )
                }
            }
        }
        Box {
            TextButton(onClick = { overflowOpen = true }) { Text("⋮") }
            DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                DropdownMenuItem(text = { Text("Sign out") }, onClick = {
                    overflowOpen = false
                    onSignOut()
                })
            }
        }
    }
}

@Composable
private fun ActiveTimersStrip(timers: List<RunningTimer>) {
    if (timers.isEmpty()) return
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1_000); tick += 1 } }
    val now = remember(tick) { Clock.SYSTEM.nowMillis() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        timers.forEach { timer ->
            Card {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(timer.kind.name.lowercase().replaceFirstChar { it.uppercase() })
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                        Text(
                            ElapsedTimeFormatter.timerCounter(now - timer.startedAtMillis),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickActionsGrid() {
    // Placeholder buttons — Stage 2 wires the six create flows (spec §3). Canonical order.
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CANONICAL_QUICK_ACTIONS.chunked(3).forEach { rowActions ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                rowActions.forEach { action ->
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.padding(4.dp),
                    ) {
                        Text(
                            action,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }
    }
    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
private fun Timeline(events: List<Event>) {
    if (events.isEmpty()) {
        Text(
            "Log your first event with a button above.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 24.dp),
        )
        return
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag(TestTags.DASHBOARD_TIMELINE),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(events, key = { it.localUuid }) { event ->
            TimelineRow(event, showDayHeader = needsHeader(events, event))
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

@Composable
private fun TimelineRow(event: Event, showDayHeader: Boolean) {
    Column {
        if (showDayHeader) {
            Text(
                dayLabel(event.startMillis),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
            HorizontalDivider()
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .testTag("${TestTags.DASHBOARD_TIMELINE}/${event.localUuid}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(EventHeadline.headline(event), style = MaterialTheme.typography.bodyLarge)
                when (SyncState.valueOf(event.syncState.name)) {
                    SyncState.PENDING -> SyncBadge("Will sync")
                    SyncState.FAILED -> SyncBadge("Sync failed")
                    SyncState.SYNCED -> Unit
                }
            }
            Text(
                timeOfDayFormat.format(Instant.ofEpochMilli(event.startMillis).atZone(ZoneId.systemDefault())),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (event.type.isTimed && event.durationMillis > 0) {
                Text(
                    ElapsedTimeFormatter.durationShort(event.durationMillis),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun SyncBadge(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = if (label == "Sync failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun dayLabel(epochMillis: Long): String {
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
    val today = java.time.LocalDate.now(zone)
    return when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> "${date.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${date.dayOfMonth}"
    }
}
