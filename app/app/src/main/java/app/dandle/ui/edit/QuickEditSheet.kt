package app.dandle.ui.edit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.dandle.R
import app.dandle.domain.FeedingOptions
import app.dandle.domain.TimeEditLogic
import app.dandle.domain.model.Event
import app.dandle.domain.model.EventDraft
import app.dandle.domain.model.EventType
import app.dandle.domain.model.EventHeadline
import app.dandle.domain.model.SyncState
import app.dandle.ui.TestTags
import app.dandle.ui.dialogTestTags
import app.dandle.ui.dashboard.ChoiceChipRow
import app.dandle.ui.dashboard.DiaperSwatches
import app.dandle.ui.dashboard.dayLabel
import app.dandle.ui.dashboard.feedingAmountHint
import app.dandle.ui.dashboard.iconRes
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
private val compactDateFormat = DateTimeFormatter.ofPattern("MMM d")

/**
 * Quick Edit sheet (functional spec §5, UX spec §4): type fields mirror the create card, each
 * time is a standard native-picker row ([DateTimeRow], owner rework U6 — no steppers), start
 * moves preserve duration (caller applies TimeEditLogic), inline validation disables Save with
 * a reason, FAILED entries get a failure banner + Retry, Delete is confirmed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickEditSheet(
    event: Event,
    draft: EventDraft,
    validationReason: String?,
    failureReason: String? = null,
    onDraftChange: ((EventDraft) -> EventDraft) -> Unit,
    onStartTimeSet: (Long) -> Unit,
    onEndTimeSet: (Long) -> Unit,
    onSave: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val isFailed = event.syncState == SyncState.FAILED

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .dialogTestTags()
            .testTag(TestTags.EDIT_SHEET),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            // Title row: type icon + live headline + close.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (isFailed) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                ) {
                    Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                        Icon(
                            painterResource(event.type.iconRes()),
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (isFailed) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            },
                        )
                        Text(
                            " " + headlineFor(event, draft),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isFailed) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            },
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("✕") }
            }

            if (isFailed) {
                // Failure banner (UX spec §4, T6): the same parent-language translation the
                // failure dialog shows — never the raw server line (robot-speak, and its times
                // are rendered in the SERVER's timezone, not the user's).
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(8.dp),
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_bb_warning),
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            failureReason ?: "The server rejected this entry.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
            }

            TypeFields(event.type, draft, onDraftChange)

            if (event.type.isTimed) {
                DateTimeRow(
                    label = "START",
                    millis = draft.startMillis,
                    onPicked = onStartTimeSet,
                    valueTag = "${TestTags.EDIT_SHEET}/time_start",
                    modifier = Modifier.padding(top = 16.dp),
                )
                DateTimeRow(
                    label = "END",
                    millis = draft.endMillis,
                    onPicked = onEndTimeSet,
                    valueTag = "${TestTags.EDIT_SHEET}/time_end",
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                DateTimeRow(
                    label = "TIME",
                    millis = draft.startMillis,
                    onPicked = onStartTimeSet,
                    valueTag = "${TestTags.EDIT_SHEET}/time_time",
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            if (validationReason != null) {
                Text(
                    validationReason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .testTag(TestTags.EDIT_VALIDATION),
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // Action row: Save (filled) · Retry (FAILED only) · Delete (destructive).
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onSave,
                    enabled = validationReason == null,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .testTag(TestTags.EDIT_SAVE),
                ) { Text("Save") }
                if (isFailed) {
                    TextButton(
                        onClick = onRetry,
                        modifier = Modifier.testTag(TestTags.EDIT_RETRY),
                    ) { Text("Retry") }
                }
                TextButton(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier.testTag(TestTags.EDIT_DELETE),
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            modifier = Modifier.dialogTestTags().testTag(TestTags.EDIT_DELETE_CONFIRM),
            title = { Text("Delete entry?") },
            text = {
                Text(
                    if (event.serverId == null) {
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

/** Headline mirror of the timeline row for the sheet's title (same format, live draft). */
private fun headlineFor(event: Event, draft: EventDraft): String =
    EventHeadline.headline(
        event.copy(
            feedingType = draft.feedingType,
            feedingMethod = draft.feedingMethod,
            amount = draft.amount,
            wet = draft.wet,
            solid = draft.solid,
            color = draft.color,
            noteText = draft.noteText,
            milestone = draft.milestone,
        ),
    )

// -- Type fields (mirror §3 exactly) --------------------------------------------------

@Composable
private fun TypeFields(
    type: EventType,
    draft: EventDraft,
    onDraftChange: ((EventDraft) -> EventDraft) -> Unit,
) {
    when (type) {
        EventType.FEEDING -> {
            EditAmount(draft.amount, feedingAmountHint(draft.feedingMethod)) { value ->
                onDraftChange { it.copy(amount = value) }
            }
            Text("Type", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            ChoiceChipRow(
                options = FeedingOptions.TYPES.map { it.wire to it.label },
                selected = draft.feedingType,
                tagPrefix = { TestTags.feedingTypeChip(it) },
                onSelect = { wire ->
                    onDraftChange {
                        it.copy(
                            feedingType = wire,
                            feedingMethod = FeedingOptions.resetMethod(wire, it.feedingMethod),
                        )
                    }
                },
            )
            Text("Method", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            ChoiceChipRow(
                options = FeedingOptions.methodsFor(draft.feedingType).map { it.wire to it.label },
                selected = draft.feedingMethod,
                tagPrefix = { TestTags.feedingMethodChip(it) },
                onSelect = { wire -> onDraftChange { it.copy(feedingMethod = wire) } },
            )
        }

        EventType.CHANGE -> {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                EditToggle("Wet", draft.wet == true, Modifier.weight(1f)) { on ->
                    onDraftChange { it.copy(wet = on) }
                }
                EditToggle("Solid", draft.solid == true, Modifier.weight(1f)) { on ->
                    onDraftChange { it.copy(solid = on) }
                }
            }
            Text("Color", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            ChoiceChipRow(
                options = DiaperSwatches.ALL.map { it.wire to it.label },
                selected = draft.color,
                tagPrefix = { TestTags.diaperColorChip(it) },
                onSelect = { wire ->
                    onDraftChange { it.copy(color = if (it.color == wire) null else wire) }
                },
                leadingSwatch = { wire -> DiaperSwatches.colorOf(wire) },
            )
            EditAmount(draft.amount, null) { value ->
                onDraftChange { it.copy(amount = value) }
            }
        }

        EventType.SLEEP -> EditNote(draft.noteText.orEmpty()) { newText ->
            onDraftChange { it.copy(noteText = newText) }
        }

        EventType.NOTE -> OutlinedTextField(
            value = draft.noteText.orEmpty(),
            onValueChange = { text -> onDraftChange { it.copy(noteText = text) } },
            label = { Text("Note") },
            minLines = 2,
            keyboardOptions = app.dandle.ui.dashboard.imeDoneOptions,
            keyboardActions = app.dandle.ui.dashboard.doneKeyboardActions(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .testTag(TestTags.PANEL_TEXT),
        )

        EventType.PUMPING -> {
            // Server-required amount (research doc §4): the sheet always carries a number.
            EditAmount(draft.amount ?: 0.0, "mL") { value ->
                onDraftChange { it.copy(amount = value) }
            }
            EditNote(draft.noteText.orEmpty()) { newText ->
                onDraftChange { it.copy(noteText = newText) }
            }
        }

        EventType.TUMMY_TIME -> {
            OutlinedTextField(
                value = draft.milestone.orEmpty(),
                onValueChange = { text -> onDraftChange { it.copy(milestone = text) } },
                label = { Text("Milestone") },
                singleLine = true,
                keyboardOptions = app.dandle.ui.dashboard.imeDoneOptions,
                keyboardActions = app.dandle.ui.dashboard.doneKeyboardActions(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .testTag(TestTags.PANEL_MILESTONE),
            )
            EditNote(draft.noteText.orEmpty()) { newText ->
                onDraftChange { it.copy(noteText = newText) }
            }
        }
    }
}

/** Numeric field; null result = "none" (feeding/diaper allow absent amounts, pumping doesn't). */
@Composable
private fun EditAmount(value: Double?, supportingText: String?, onChange: (Double?) -> Unit) {
    var text by remember(value) { mutableStateOf(value?.let(::trimAmount).orEmpty()) }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            val cleaned = input.filter { it.isDigit() || it == '.' || it == ',' }
            text = cleaned
            onChange(cleaned.trim().replace(',', '.').toDoubleOrNull()?.coerceAtLeast(0.0))
        },
        label = { Text("Amount") },
        supportingText = supportingText?.let { { Text(it) } },
        suffix = { Text("mL") },
        singleLine = true,
        keyboardOptions = app.dandle.ui.dashboard.imeDoneOptions,
        keyboardActions = app.dandle.ui.dashboard.doneKeyboardActions(),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .testTag(TestTags.PANEL_AMOUNT),
    )
}

private fun trimAmount(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

@Composable
private fun EditNote(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text("Note") },
        singleLine = true,
        keyboardOptions = app.dandle.ui.dashboard.imeDoneOptions,
        keyboardActions = app.dandle.ui.dashboard.doneKeyboardActions(),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .testTag(TestTags.PANEL_NOTE),
    )
}

@Composable
private fun EditToggle(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onChange: (Boolean) -> Unit,
) {
    Surface(
        onClick = { onChange(!selected) },
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.height(48.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                color = if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}
