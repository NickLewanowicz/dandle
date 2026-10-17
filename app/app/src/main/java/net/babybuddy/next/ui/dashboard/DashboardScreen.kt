package net.babybuddy.next.ui.dashboard

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch
import net.babybuddy.next.data.AppContainer
import net.babybuddy.next.domain.DuplicateGuard
import net.babybuddy.next.domain.StopGuard
import net.babybuddy.next.domain.model.EventType
import net.babybuddy.next.domain.model.RunningTimer
import net.babybuddy.next.ui.TestTags
import net.babybuddy.next.ui.edit.QuickEditSheet

/**
 * Dashboard (spec §2): header (child switcher + overflow + sync chip), active-timers strip,
 * six quick actions with inline panels, and the day-grouped timeline. Local-first saves; the
 * timeline insert animation is the save confirmation (UX spec §3/§5).
 */

@Composable
fun DashboardScreen(
    container: AppContainer,
    onSignedOut: () -> Unit,
    onSignIn: () -> Unit = {},
) {
    val context = LocalContext.current
    val viewModel = viewModel { DashboardViewModel(container, context) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // One shared per-second tick feeds every live counter (wall-clock based, spec §10).
    var tick by remember { mutableLongStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            tick += 1
        }
    }
    val nowMillis = remember(tick) { container.serverClock.nowMillis() }

    var showSignOutDialog by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var stopDialog by remember { mutableStateOf<RunningTimer?>(null) }
    var viewLastEntryUuid by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        viewModel.refresh()
        viewModel.events.collect { event ->
            when (event) {
                DashboardEvent.SignedOut -> onSignedOut()
            }
        }
    }

    LaunchedEffect(state.showLongPressHint) {
        if (state.showLongPressHint) {
            snackbar.showSnackbar("Long-press an entry to open it in the Baby Buddy web app.")
            viewModel.onLongPressHintShown()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag(TestTags.DASHBOARD),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
            ) {
                HeaderRow(
                    children = state.children,
                    activeChild = state.activeChild,
                    onSelectChild = viewModel::selectChild,
                    onSignOut = { showSignOutDialog = true },
                    onHelp = { showHelpDialog = true },
                    onAbout = { showAboutDialog = true },
                    syncChip = syncChipState(state.isOnline, state.syncing, state.failedCount),
                    onSyncChipTap = viewModel::syncNow,
                )

                if (state.sessionExpired) {
                    SessionExpiredBanner(
                        unsentCount = state.unsentCount,
                        onSignIn = onSignIn,
                    )
                }

                ActiveTimersStrip(
                    timers = state.runningTimers,
                    nowMillis = nowMillis,
                    onSave = viewModel::saveRunningTimer,
                    onDiscard = { timer ->
                        // Strip ✕ runs the stop guard (UX spec §2).
                        val elapsed = nowMillis - timer.startedAtMillis
                        if (StopGuard.shouldConfirm(elapsed)) stopDialog = timer else viewModel.discardTimer(timer)
                    },
                    onOpenPanel = viewModel::onQuickActionTap,
                )

                QuickActionsGrid(
                    childPresent = state.activeChild != null,
                    runningKinds = state.runningTimers.map { it.kind }.toSet(),
                    openType = state.openPanel?.type,
                    enabled = state.activeChild != null,
                    onTap = viewModel::onQuickActionTap,
                )

                state.openPanel?.let { panel ->
                    QuickActionPanel(
                        panel = panel,
                        nowMillis = nowMillis,
                        saveEnabled = viewModel.isPanelSaveEnabled(panel),
                        callbacks = viewModel, // DashboardViewModel implements QuickActionCallbacks
                    )
                }

                TimelineSection(
                    events = state.timeline,
                    totalCount = state.totalTimelineCount,
                    loadingOlder = state.loadingOlder,
                    onTapRow = viewModel::onTimelineRowTapped, // → Quick Edit sheet (spec §4.2)
                    onLongPressRow = { event ->
                        val url = viewModel.webUrlFor(event)
                        if (url == null) {
                            scope.launch { snackbar.showSnackbar("This entry hasn't synced yet.") }
                        } else {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            }
                        }
                    },
                    onFailureBadge = viewModel::onFailureBadgeTapped,
                    onLoadOlder = viewModel::loadOlder,
                    scrollToUuid = viewLastEntryUuid,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

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
    if (showHelpDialog) {
        net.babybuddy.next.ui.about.HelpDialog(onDismiss = { showHelpDialog = false })
    }
    if (showAboutDialog) {
        net.babybuddy.next.ui.about.AboutDialog(onDismiss = { showAboutDialog = false })
    }
    stopDialog?.let { timer ->
        val elapsed = (nowMillis - timer.startedAtMillis).coerceAtLeast(0)
        AlertDialog(
            onDismissRequest = { stopDialog = null },
            title = { Text(StopGuard.DIALOG_TITLE) },
            text = { Text(StopGuard.dialogBody(timer.kind.label, elapsed)) },
            confirmButton = {
                // Save is the emphasized action (spec v0.2) — most likely intent.
                Button(
                    onClick = {
                        stopDialog = null
                        viewModel.saveRunningTimer(timer)
                    },
                    modifier = Modifier.testTag(TestTags.STOP_DIALOG_SAVE),
                ) { Text("Save") }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            stopDialog = null
                            viewModel.discardTimer(timer)
                        },
                        modifier = Modifier.testTag(TestTags.STOP_DIALOG_DISCARD),
                    ) { Text("Discard", color = MaterialTheme.colorScheme.error) }
                    TextButton(
                        onClick = { stopDialog = null },
                        modifier = Modifier.testTag(TestTags.STOP_DIALOG_KEEP),
                    ) { Text("Keep") }
                }
            },
            modifier = Modifier.testTag(TestTags.STOP_DIALOG),
        )
    }
    state.duplicatePrompt?.let { prompt ->
        AlertDialog(
            onDismissRequest = viewModel::onDuplicateDismiss,
            title = { Text("Already logged?") },
            text = { Text(prompt.message) },
            confirmButton = {
                Button(
                    onClick = viewModel::onDuplicateAdd,
                    modifier = Modifier.testTag(TestTags.DUPLICATE_ADD),
                ) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = {
                    // "View last entry": jump to the row the dialog named.
                    viewLastEntryUuid = prompt.lastEventLocalUuid
                    viewModel.onDuplicateDismiss()
                }) { Text("View last entry") }
            },
            modifier = Modifier.testTag(TestTags.DUPLICATE_DIALOG),
        )
    }

    // Quick Edit sheet (spec §5): opens from a row tap or the failure dialog's Edit.
    state.editState?.let { edit ->
        QuickEditSheet(
            event = edit.event,
            draft = edit.draft,
            validationReason = viewModel.editValidationReason(edit),
            onDraftChange = viewModel::onEditDraftChange,
            onStartStepped = viewModel::onEditStartStepped,
            onEndStepped = viewModel::onEditEndStepped,
            onStartTimeSet = viewModel::onEditStartTimeSet,
            onEndTimeSet = viewModel::onEditEndTimeSet,
            onSave = viewModel::saveEdit,
            onRetry = { viewModel.retryEvent(edit.event.localUuid) },
            onDelete = { viewModel.deleteEvent(edit.event.localUuid) },
            onDismiss = viewModel::closeEdit,
        )
    }

    // Failure dialog (spec §6.2): tap a FAILED badge/row → parent-language reason.
    state.failureDialog?.let { failure ->
        FailureDialog(
            state = failure,
            onEdit = viewModel::editFromFailureDialog,
            onRetry = { viewModel.retryEvent(failure.event.localUuid) },
            onDelete = { viewModel.deleteEvent(failure.event.localUuid) },
            onShowClash = { clashUuid ->
                viewLastEntryUuid = clashUuid
            },
            onDismiss = viewModel::dismissFailureDialog,
        )
    }
}

@Composable
private fun HeaderRow(
    children: List<net.babybuddy.next.domain.model.Child>,
    activeChild: net.babybuddy.next.domain.model.Child?,
    onSelectChild: (Long) -> Unit,
    onSignOut: () -> Unit,
    onHelp: () -> Unit,
    onAbout: () -> Unit,
    syncChip: SyncChipState,
    onSyncChipTap: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Baby Buddy",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Box(modifier = Modifier.weight(1f))
            Box {
                Surface(
                    onClick = { if (children.size > 1) menuOpen = true },
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.testTag(TestTags.DASHBOARD_CHILD_SWITCHER),
                ) {
                    Text(
                        (activeChild?.firstName ?: "No child") + if (children.size > 1) " ▾" else "",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    children.forEach { child ->
                        DropdownMenuItem(
                            // Full names in the dropdown (original-app parity); chip stays short.
                            text = { Text(listOfNotNull(child.firstName, child.lastName).joinToString(" ")) },
                            onClick = {
                                onSelectChild(child.id)
                                menuOpen = false
                            },
                        )
                    }
                }
            }
            Box {
                TextButton(
                    onClick = { overflowOpen = true },
                    modifier = Modifier.testTag(TestTags.DASHBOARD_OVERFLOW),
                ) { Text("⋮") }
                DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                    DropdownMenuItem(text = { Text("Sign out") }, onClick = {
                        overflowOpen = false
                        onSignOut()
                    })
                    DropdownMenuItem(text = { Text("Help") }, onClick = {
                        overflowOpen = false
                        onHelp()
                    })
                    DropdownMenuItem(text = { Text("About") }, onClick = {
                        overflowOpen = false
                        onAbout()
                    })
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            SyncStatusChip(state = syncChip, onTap = onSyncChipTap)
        }
    }
}

@Composable
private fun ActiveTimersStrip(
    timers: List<RunningTimer>,
    nowMillis: Long,
    onSave: (RunningTimer) -> Unit,
    onDiscard: (RunningTimer) -> Unit,
    onOpenPanel: (EventType) -> Unit,
) {
    if (timers.isEmpty()) return
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.testTag(TestTags.DASHBOARD_TIMER_STRIP),
    ) {
        timers.forEach { timer ->
            Surface(
                onClick = { onOpenPanel(timer.kind) }, // focus the running panel (spec §3)
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(TestTags.timerCard(timer.kind.name)),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = androidx.compose.foundation.shape.CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(8.dp),
                        ) {
                            androidx.compose.material3.Icon(
                                androidx.compose.ui.res.painterResource(timer.kind.iconRes()),
                                contentDescription = timer.kind.label,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text(timer.kind.label, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Running",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box(modifier = Modifier.weight(1f))
                    Text(
                        net.babybuddy.next.domain.ElapsedTimeFormatter.timerCounter(
                            (nowMillis - timer.startedAtMillis).coerceAtLeast(0),
                        ),
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontFeatureSettings = "tnum",
                        ),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Button(
                        onClick = { onSave(timer) },
                        modifier = Modifier
                            .height(48.dp)
                            .testTag(TestTags.timerSave(timer.kind.name)),
                    ) { Text("Save") }
                    TextButton(
                        onClick = { onDiscard(timer) },
                        modifier = Modifier.testTag(TestTags.timerDiscard(timer.kind.name)),
                    ) { Text("✕") }
                }
            }
        }
    }
}
