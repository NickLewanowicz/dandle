package app.dandle.ui.dashboard

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.dandle.data.AppContainer
import app.dandle.domain.ElapsedTimeFormatter
import app.dandle.domain.StopGuard
import app.dandle.domain.model.EventType
import app.dandle.domain.model.RunningTimer
import app.dandle.ui.TestTags
import app.dandle.ui.dialogTestTags
import app.dandle.ui.edit.QuickEditSheet


/**
 * Dashboard (spec §2, owner rework U1): header (child name + chevron, sync chip, overflow), the
 * six quick actions PINNED directly under the header — nothing dynamic ever renders above them —
 * then the running-timer card (U2) when a timer runs, then the day-grouped timeline. Diaper and
 * note creation open a modal sheet (U4). Local-first saves; the timeline insert animation is the
 * save confirmation (UX spec §3/§5).
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

    // BB/START: first non-loading uiState — the dashboard's real content is about to render.
    LaunchedEffect(state.loading) {
        if (!state.loading) {
            app.dandle.log.BbLog.d(
                app.dandle.log.BbLog.TAG_START,
                "dashboard content ready",
            )
        }
    }

    // One shared per-second tick feeds every live counter (wall-clock based, spec §10).
    var tick by remember { mutableLongStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            tick += 1
        }
    }
    val nowMillis = remember(tick) { container.serverClock.nowMillis() }

    var stopDialog by remember { mutableStateOf<RunningTimer?>(null) }

    // P2-4/U3: guard requests from a re-tap of a running type's button (the <=10s branch never
    // emits — the VM discards silently there).
    LaunchedEffect(Unit) {
        viewModel.stopGuardRequests.collect { timer ->
            if (timer != null) {
                stopDialog = timer
                viewModel.consumeStopGuardRequest()
            }
        }
    }

    var showSignOutDialog by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var viewLastEntryUuid by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        viewModel.refresh()
        viewModel.events.collect { event ->
            when (event) {
                DashboardEvent.SignedOut -> onSignedOut()
                is DashboardEvent.EntryCreated -> viewLastEntryUuid = event.localUuid
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
                    syncChip = syncChipState(state.isOnline, state.syncing, state.showAllSynced),
                    onSyncChipTap = viewModel::syncNow,
                )

                if (state.sessionExpired) {
                    SessionExpiredBanner(
                        unsentCount = state.unsentCount,
                        onSignIn = onSignIn,
                    )
                }

                // U1: the grid is the first thing under the header, always at the same place.
                QuickActionsGrid(
                    childPresent = state.activeChild != null,
                    runningKinds = state.runningTimers.map { it.kind }.toSet(),
                    openType = state.openPanel?.type,
                    runningElapsed = state.runningTimers.associate {
                        it.kind to ElapsedTimeFormatter.durationShort(nowMillis - it.startedAtMillis)
                    },
                    enabled = state.activeChild != null,
                    onTap = viewModel::onQuickActionTap,
                )

                // U2: a running timer renders as the expanded TOP ELEMENT of the list — it is the
                // timeline's first item, so it scrolls with it and the keyboard can never hide
                // the field being typed into.
                val runningPanel = state.openPanel?.takeIf { it.type.isTimed && it.timer != null }
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
                    onLoadOlder = { viewModel.loadOlder() },
                    scrollToUuid = viewLastEntryUuid,
                    modifier = Modifier.weight(1f),
                    leadingItem = runningPanel?.let { panel ->
                        {
                            RunningTimerCard(
                                panel = panel,
                                nowMillis = nowMillis,
                                saveEnabled = viewModel.isPanelSaveEnabled(panel),
                                callbacks = viewModel, // DashboardViewModel implements QuickActionCallbacks
                            )
                        }
                    },
                )
            }
        }
    }

    // U4: diaper / note creation is a sheet — the same UI family as editing a finished entry.
    state.openPanel?.takeIf { !it.type.isTimed }?.let { panel ->
        CreateEntrySheet(
            panel = panel,
            entryMillis = panel.entryTimeMillis ?: nowMillis,
            saveEnabled = viewModel.isPanelSaveEnabled(panel),
            callbacks = viewModel,
        )
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
        app.dandle.ui.about.HelpDialog(onDismiss = { showHelpDialog = false })
    }
    if (showAboutDialog) {
        app.dandle.ui.about.AboutDialog(onDismiss = { showAboutDialog = false })
    }
    stopDialog?.let { timer ->
        val elapsed = (nowMillis - timer.startedAtMillis).coerceAtLeast(0)
        AlertDialog(
            onDismissRequest = { stopDialog = null },
            title = { Text(StopGuard.DIALOG_TITLE) },
            confirmButton = {},
            dismissButton = {},
            text = {
                Column {
                    Text(StopGuard.dialogBody(timer.kind.label, elapsed))
                    // Save fills its own row; Discard is moved to a subdued SECOND line
                    // far from Save — a thumb-slip on an adjacent destructive button must
                    // not erase a tracked nap (Jordan, persona round 2).
                    Button(
                        onClick = {
                            stopDialog = null
                            viewModel.saveRunningTimer(timer)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .testTag(TestTags.STOP_DIALOG_SAVE),
                    ) { Text("Save") }
                    Row(modifier = Modifier.padding(top = 4.dp)) {
                        TextButton(
                            onClick = { stopDialog = null },
                            modifier = Modifier.testTag(TestTags.STOP_DIALOG_KEEP),
                        ) { Text("Keep running") }
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            onClick = {
                                stopDialog = null
                                viewModel.discardTimer(timer)
                            },
                            modifier = Modifier.testTag(TestTags.STOP_DIALOG_DISCARD),
                        ) { Text("Discard", color = MaterialTheme.colorScheme.error) }
                    }
                }
            },
            modifier = Modifier
                .dialogTestTags()
                .testTag(TestTags.STOP_DIALOG),
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
            modifier = Modifier
                .dialogTestTags()
                .testTag(TestTags.DUPLICATE_DIALOG),
        )
    }

    // Quick Edit sheet (spec §5): opens from a row tap or the failure dialog's Edit.
    state.editState?.let { edit ->
        QuickEditSheet(
            event = edit.event,
            draft = edit.draft,
            validationReason = viewModel.editValidationReason(edit),
            failureReason = edit.failureReason,
            onDraftChange = viewModel::onEditDraftChange,
            onStartTimeSet = viewModel::onEditStartTimeSet,
            onEndTimeSet = viewModel::onEditEndTimeSet,
            onSave = viewModel::saveEdit,
            onRetry = { viewModel.retryEvent(edit.event.localUuid) },
            onDelete = { viewModel.deleteEvent(edit.event.localUuid) },
            onDismiss = viewModel::closeEdit,
        )
    }

    // Failure dialog (spec §6.2): tap a FAILED row → parent-language reason.
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

/**
 * Header (owner rework U9, original-app parity): the CHILD's first name sits where a title
 * would — plain text plus a chevron when there is more than one child (tap → full-name
 * dropdown); the app has no name in the chrome. Sync chip inline (only when it has something
 * to say), overflow on the right. One row — no second line to push the buttons down.
 */
@Composable
private fun HeaderRow(
    children: List<app.dandle.domain.model.Child>,
    activeChild: app.dandle.domain.model.Child?,
    onSelectChild: (Long) -> Unit,
    onSignOut: () -> Unit,
    onHelp: () -> Unit,
    onAbout: () -> Unit,
    syncChip: SyncChipState,
    onSyncChipTap: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }
    val switchable = children.size > 1
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Surface(
                onClick = { if (switchable) menuOpen = true },
                enabled = switchable,
                shape = MaterialTheme.shapes.small,
                color = androidx.compose.ui.graphics.Color.Transparent,
                modifier = Modifier.testTag(TestTags.DASHBOARD_CHILD_SWITCHER),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                ) {
                    Text(
                        activeChild?.firstName ?: "No child",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (switchable) {
                        Text(
                            "▾",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 2.dp),
                        )
                    }
                }
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                children.forEach { child ->
                    DropdownMenuItem(
                        // Full names in the dropdown (original-app parity); header stays short.
                        text = { Text(listOfNotNull(child.firstName, child.lastName).joinToString(" ")) },
                        onClick = {
                            onSelectChild(child.id)
                            menuOpen = false
                        },
                    )
                }
            }
        }
        Box(modifier = Modifier.weight(1f))
        SyncStatusChip(state = syncChip, onTap = onSyncChipTap)
        Box {
            TextButton(
                onClick = { overflowOpen = true },
                modifier = Modifier.testTag(TestTags.DASHBOARD_OVERFLOW),
            ) { Text("⋮", style = MaterialTheme.typography.titleLarge) }
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
}
