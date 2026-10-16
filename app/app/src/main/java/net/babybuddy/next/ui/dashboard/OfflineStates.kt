package net.babybuddy.next.ui.dashboard

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import net.babybuddy.next.R
import net.babybuddy.next.domain.FailureMessage
import net.babybuddy.next.ui.TestTags

/**
 * Failure dialog for FAILED entries (spec §6.2, UX spec §5): the server's reason in parent
 * language, with Edit (the common fix for overlaps), Retry, and Delete locally. Never
 * auto-deletes; never blocks other entries' sync.
 */
@Composable
fun FailureDialog(
    state: FailureDialogState,
    onEdit: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onShowClash: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Couldn't sync this entry") },
        text = { Text(state.reason) },
        confirmButton = {
            Button(
                onClick = onEdit,
                modifier = Modifier.testTag("${TestTags.FAILURE_DIALOG}/edit"),
            ) { Text("Edit") }
        },
        dismissButton = {
            Row {
                if (state.clashLocalUuid != null) {
                    TextButton(onClick = {
                        onDismiss()
                        onShowClash(state.clashLocalUuid)
                    }) { Text("Show entry") }
                }
                TextButton(
                    onClick = onRetry,
                    modifier = Modifier.testTag("${TestTags.FAILURE_DIALOG}/retry"),
                ) { Text("Retry") }
                TextButton(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier.testTag("${TestTags.FAILURE_DIALOG}/delete"),
                ) { Text("Delete locally", color = MaterialTheme.colorScheme.error) }
            }
        },
        modifier = Modifier.testTag(TestTags.FAILURE_DIALOG),
    )

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete entry?") },
            text = {
                Text(
                    if (state.event.serverId == null) {
                        "This removes it from this device. It was never synced."
                    } else {
                        "This removes it from the app and the server."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDelete()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * Session-expired banner (spec §6.3.6, UX spec §5): reassurance first — unsent entries are
 * safe — then the way out. Sign in returns to the login screen; the queue resumes on success.
 */
@Composable
fun SessionExpiredBanner(
    unsentCount: Int,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.small,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .testTag("dashboard/session_expired"),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_bb_warning),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                FailureMessage.sessionExpired(unsentCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )
            TextButton(onClick = onSignIn) {
                Text("Sign in", color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}
