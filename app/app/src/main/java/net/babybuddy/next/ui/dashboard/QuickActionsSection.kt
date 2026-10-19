package net.babybuddy.next.ui.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import net.babybuddy.next.R
import net.babybuddy.next.domain.ElapsedTimeFormatter
import net.babybuddy.next.domain.FeedingOptions
import net.babybuddy.next.domain.model.EventType
import net.babybuddy.next.domain.model.RunningTimer
import net.babybuddy.next.ui.TestTags
import net.babybuddy.next.ui.dashboard.OpenPanel
import net.babybuddy.next.ui.dialogTestTags
import net.babybuddy.next.ui.edit.DateTimeRow

/**
 * The six quick-action buttons (spec §2.3, UX spec §2): canonical order Feeding, Diaper, Sleep,
 * Notes, Pumping, Tummy time — 3×2 grid of 64dp tonal circles with labels. Owner rework U2/U11:
 * a running (or open) type highlights in its SEMANTIC type color, not generic primary; the dot
 * marks running. Tap semantics live in the ViewModel (U3: tap a running type → stop guard).
 */
@Composable
fun QuickActionsGrid(
    childPresent: Boolean,
    runningKinds: Set<EventType>,
    openType: EventType?,
    runningElapsed: Map<EventType, String>,
    enabled: Boolean,
    onTap: (EventType) -> Unit,
) {
    val order = listOf(
        EventType.FEEDING, EventType.CHANGE, EventType.SLEEP,
        EventType.NOTE, EventType.PUMPING, EventType.TUMMY_TIME,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .testTag(TestTags.DASHBOARD),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        order.chunked(3).forEach { rowTypes ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                rowTypes.forEach { type ->
                    QuickActionButton(
                        type = type,
                        running = type in runningKinds,
                        open = openType == type,
                        elapsedLabel = runningElapsed[type],
                        enabled = enabled,
                        onTap = { onTap(type) },
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickActionButton(
    type: EventType,
    running: Boolean,
    open: Boolean,
    elapsedLabel: String?,
    enabled: Boolean,
    onTap: () -> Unit,
) {
    val active = running || open
    val typeColors = type.typeColorPair()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            Surface(
                onClick = onTap,
                enabled = enabled,
                shape = CircleShape,
                color = if (active) typeColors.container else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .size(64.dp)
                    .testTag(TestTags.quickAction(type.name)),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painterResource(type.iconRes()),
                        contentDescription = type.label,
                        modifier = Modifier.size(28.dp),
                        tint = if (active) typeColors.onContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (running) {
                // Static running dot (UX spec §2 — no pulsing). 8dp with a surface halo,
                // inside the circle bounds: the old half-clipped dot read as an artifact.
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = (-4).dp, y = 4.dp)
                        .size(10.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                        .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape),
                )
            }
        }
        Text(
            // A running type's label becomes live status ("Sleep · 12m") — the only trace
            // of a running timer once the timeline is scrolled (Jordan, persona round 2).
            if (running && elapsedLabel != null) "${type.label} · $elapsedLabel" else type.label,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** Contract between the cards/sheets and the ViewModel (implemented by DashboardViewModel). */
interface QuickActionCallbacks {
    fun onClosePanel(type: EventType)
    fun onSavePanel()
    fun onFeedingTypeChange(wire: String)
    fun onFeedingMethodChange(wire: String)
    fun onAmountChange(text: String)
    fun onWetToggle()
    fun onSolidToggle()
    fun onColorChange(wire: String)
    fun onExtraOptionsToggle()
    fun onNoteChange(text: String)
    fun onMilestoneChange(text: String)
    fun onTextChange(text: String)

    /** U4: the create sheet's TIME row (diaper/note) — absolute entry time. */
    fun onCreateTimePicked(millis: Long)

    /** T2: "started X min ago" adjust — [minutesAgoDelta] > 0 backdates the start. */
    fun onStartAdjustStepped(minutesAgoDelta: Long)
    fun onStartTimePicked(millis: Long)
}

/**
 * The running-timer card (owner rework U2): the original app's "expanded top element in the
 * list" look, rendered below the buttons — type-colored icon tile beside the label + live
 * `H:MM:SS` counter, the type's fields at full width below (chips need the whole row), and an
 * icon-only check save bottom-right. No ✕ and no "Save"/"Running" words anywhere (U3):
 * discarding happens by tapping the type button again (StopGuard), which is exactly the
 * original app's tap-to-stop.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunningTimerCard(
    panel: OpenPanel,
    nowMillis: Long,
    saveEnabled: Boolean,
    callbacks: QuickActionCallbacks,
) {
    val type = panel.type
    val timer = panel.timer ?: return
    val typeColors = type.typeColorPair()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .testTag(TestTags.panel(type.name)),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header band: type tile + label + counter (the card's scan anchor).
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .width(56.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(12.dp))
                        .background(typeColors.container),
                ) {
                    Icon(
                        painterResource(type.iconRes()),
                        contentDescription = type.label,
                        modifier = Modifier.size(26.dp),
                        tint = typeColors.onContainer,
                    )
                }
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text(
                        type.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        ElapsedTimeFormatter.timerCounter((nowMillis - timer.startedAtMillis).coerceAtLeast(0)),
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontFeatureSettings = "tnum",
                        ),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                // Icon-only save lives in the HEADER (always on screen): the feeding
                // card's fields push a bottom-corner save below the fold (UX round 2 P0).
                Surface(
                    onClick = callbacks::onSavePanel,
                    enabled = saveEnabled,
                    shape = RoundedCornerShape(12.dp),
                    color = typeColors.container,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(44.dp)
                        .testTag(TestTags.timerSave(type.name)),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painterResource(R.drawable.ic_bb_check),
                            contentDescription = "Save",
                            modifier = Modifier.size(22.dp),
                            tint = if (saveEnabled) typeColors.onContainer
                            else typeColors.onContainer.copy(alpha = 0.38f),
                        )
                    }
                }
            }

            when (type) {
                EventType.FEEDING -> FeedingFields(panel, callbacks)
                EventType.SLEEP -> NoteField(
                    value = panel.draft.noteText,
                    onValueChange = callbacks::onNoteChange,
                    label = "Note",
                )
                EventType.PUMPING -> {
                    AmountField(
                        value = panel.draft.amountText,
                        onValueChange = callbacks::onAmountChange,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    NoteField(
                        value = panel.draft.noteText,
                        onValueChange = callbacks::onNoteChange,
                        label = "Note",
                    )
                }
                EventType.TUMMY_TIME -> {
                    OutlinedTextField(
                        value = panel.draft.milestone,
                        onValueChange = callbacks::onMilestoneChange,
                        label = { Text("Milestone") },
                        singleLine = true,
                        keyboardOptions = imeDoneOptions,
                        keyboardActions = doneKeyboardActions(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .testTag(TestTags.PANEL_MILESTONE),
                    )
                    NoteField(
                        value = panel.draft.noteText,
                        onValueChange = callbacks::onNoteChange,
                        label = "Note",
                    )
                }
                else -> Unit
            }

            StartedAgoRow(panel, nowMillis, callbacks)
        }
    }
}

/**
 * The create sheet for the edit-like quick actions (owner rework U4): diaper and note open a
 * modal bottom sheet with the SAME field composables the Quick Edit sheet uses, plus a TIME row
 * (native pickers, defaults to now) — because logging a change that happened 20 minutes ago is
 * an everyday correction. Save runs the duplicate guard; ✕/scrim keeps the draft.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateEntrySheet(
    panel: OpenPanel,
    entryMillis: Long,
    saveEnabled: Boolean,
    callbacks: QuickActionCallbacks,
) {
    val type = panel.type
    val typeColors = type.typeColorPair()
    ModalBottomSheet(
        onDismissRequest = { callbacks.onClosePanel(type) },
        modifier = Modifier
            .dialogTestTags()
            .testTag(TestTags.panel(type.name)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(10.dp), color = typeColors.container) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(36.dp)) {
                        Icon(
                            painterResource(type.iconRes()),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = typeColors.onContainer,
                        )
                    }
                }
                Text(
                    if (type == EventType.CHANGE) "New diaper" else "New note",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 10.dp),
                )
                Box(modifier = Modifier.weight(1f))
                TextButton(
                    onClick = { callbacks.onClosePanel(type) },
                    modifier = Modifier.testTag(TestTags.PANEL_CLOSE),
                ) { Text("✕") }
            }

            DateTimeRow(
                label = "TIME",
                millis = entryMillis,
                onPicked = callbacks::onCreateTimePicked,
                valueTag = TestTags.PANEL_TIME,
                modifier = Modifier.padding(top = 12.dp),
            )

            when (type) {
                EventType.CHANGE -> DiaperFields(panel, callbacks)
                EventType.NOTE -> OutlinedTextField(
                    value = panel.draft.text,
                    onValueChange = callbacks::onTextChange,
                    label = { Text("Note") },
                    minLines = 2,
                    keyboardOptions = imeDoneOptions,
                    keyboardActions = doneKeyboardActions(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .testTag(TestTags.PANEL_TEXT),
                )
                else -> Unit
            }

            if (!saveEnabled) {
                Text(
                    if (type == EventType.CHANGE) "Choose at least one of Wet or Solid." else "Write something first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Button(
                onClick = callbacks::onSavePanel,
                enabled = saveEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .height(52.dp)
                    .testTag(TestTags.PANEL_SAVE),
            ) { Text("Save") }
        }
    }
}

// -- Per-type fields (shared with QuickEditSheet) ----------------------------------

/**
 * T2 (spec §5 panel time-adjust, Jordan/Elena): the timed-type cards start their timer at tap;
 * reality often started earlier. One compact line — "Started 20m ago · Adjust" — expands to
 * −15m/−5m/+5m/+15m nudges plus a plain time picker. The adjust backdates the START only;
 * duration rules are unchanged (the created entry uses the adjusted start).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StartedAgoRow(
    panel: OpenPanel,
    nowMillis: Long,
    callbacks: QuickActionCallbacks,
) {
    var expanded by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                startedLabel(panel, nowMillis),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .weight(1f)
                    .testTag(TestTags.PANEL_STARTED_AGO),
            )
            TextButton(
                onClick = { expanded = !expanded },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                modifier = Modifier.testTag(TestTags.PANEL_STARTED_ADJUST),
            ) {
                Text(if (expanded) "Hide" else "Adjust", style = MaterialTheme.typography.labelMedium)
            }
        }
        if (expanded) {
            Row {
                listOf(
                    "−15m" to 15L,
                    "−5m" to 5L,
                    "+5m" to -5L,
                    "+15m" to -15L,
                ).forEach { (label, delta) ->
                    TextButton(
                        onClick = { callbacks.onStartAdjustStepped(delta) },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.testTag(TestTags.startedAgoStep(label)),
                    ) { Text(label, style = MaterialTheme.typography.labelMedium) }
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { showPicker = true },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.testTag(TestTags.PANEL_STARTED_PICK),
                ) { Text("Pick time", style = MaterialTheme.typography.labelMedium) }
            }
        }
    }

    if (showPicker) {
        val zone = java.time.ZoneId.systemDefault()
        val initial = java.time.Instant.ofEpochMilli(nowMillis).atZone(zone)
        val timeState = androidx.compose.material3.rememberTimePickerState(
            initialHour = initial.hour,
            initialMinute = initial.minute,
        )
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("When did it start?") },
            text = { androidx.compose.material3.TimePicker(state = timeState) },
            confirmButton = {
                TextButton(onClick = {
                    showPicker = false
                    val picked = initial.toLocalDate()
                        .atTime(timeState.hour, timeState.minute)
                        .atZone(zone)
                        .toInstant()
                        .toEpochMilli()
                    callbacks.onStartTimePicked(picked)
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * "Started 02:12 · 12m ago" — ABSOLUTE first (the number the pediatrician asks for;
 * Jordan+Elena, persona round 2), relative in parentheses for drift sense.
 */
internal fun startedLabel(panel: OpenPanel, nowMillis: Long): String {
    val minutesAgo = panel.draft.startedMinutesAgo
    val clock = java.time.Instant.ofEpochMilli(nowMillis - minutesAgo * 60_000L)
        .atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
    return when {
        minutesAgo <= 0 -> "Started $clock · just now"
        minutesAgo < 60 -> "Started $clock · ${minutesAgo}m ago"
        else -> {
            val h = minutesAgo / 60
            val m = minutesAgo % 60
            val rel = if (m == 0) "${h}h ago" else "${h}h ${m}m ago"
            "Started $clock · $rel"
        }
    }
}

@Composable
private fun FeedingFields(panel: OpenPanel, callbacks: QuickActionCallbacks) {
    Column {
        Text("Type", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        ChoiceChipRow(
            options = FeedingOptions.TYPES.map { it.wire to it.label },
            selected = panel.draft.feedingType,
            tagPrefix = { TestTags.feedingTypeChip(it) },
            onSelect = callbacks::onFeedingTypeChange,
        )
        Text("Method", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        ChoiceChipRow(
            options = FeedingOptions.methodsFor(panel.draft.feedingType).map { it.wire to it.label },
            selected = panel.draft.feedingMethod,
            tagPrefix = { TestTags.feedingMethodChip(it) },
            onSelect = callbacks::onFeedingMethodChange,
        )
        AmountField(
            value = panel.draft.amountText,
            onValueChange = callbacks::onAmountChange,
            supportingText = feedingAmountHint(panel.draft.feedingMethod),
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun DiaperFields(panel: OpenPanel, callbacks: QuickActionCallbacks) {
    Column(modifier = Modifier.padding(top = 12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ToggleCard(
                label = "Wet",
                selected = panel.draft.wet,
                onClick = callbacks::onWetToggle,
                modifier = Modifier
                    .weight(1f)
                    .testTag(TestTags.PANEL_WET),
            )
            ToggleCard(
                label = "Solid",
                selected = panel.draft.solid,
                onClick = callbacks::onSolidToggle,
                modifier = Modifier
                    .weight(1f)
                    .testTag(TestTags.PANEL_SOLID),
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .clickable { callbacks.onExtraOptionsToggle() }
                .testTag(TestTags.PANEL_EXTRA_OPTIONS),
        ) {
            Text(
                if (panel.draft.extraOpen) "Hide extra options" else "Extra options",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                if (panel.draft.extraOpen) "  ▲" else "  ▼",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        AnimatedVisibility(
            visible = panel.draft.extraOpen,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column {
                Text("Color", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                ChoiceChipRow(
                    options = DiaperSwatches.ALL.map { it.wire to it.label },
                    selected = panel.draft.color,
                    tagPrefix = { TestTags.diaperColorChip(it) },
                    onSelect = callbacks::onColorChange,
                    leadingSwatch = { wire -> DiaperSwatches.colorOf(wire) },
                )
                AmountField(
                    value = panel.draft.amountText,
                    onValueChange = callbacks::onAmountChange,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/** Method-aware amount hint: for a bottle the amount is the PRIMARY datum (Elena). */
internal fun feedingAmountHint(method: String?): String =
    if (method == null || method.contains("breast")) "mL — optional for nursing" else "mL in the bottle"

@Composable
private fun AmountField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("Amount") },
        supportingText = supportingText?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = imeDoneOptions,
        keyboardActions = doneKeyboardActions(),
        modifier = modifier
            .fillMaxWidth()
            .testTag(TestTags.PANEL_AMOUNT),
    )
}

@Composable
private fun NoteField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = imeDoneOptions,
        keyboardActions = doneKeyboardActions(),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .testTag(TestTags.PANEL_NOTE),
    )
}

@Composable
fun ChoiceChipRow(
    options: List<Pair<String, String>>,
    selected: String?,
    tagPrefix: (String) -> String,
    onSelect: (String) -> Unit,
    leadingSwatch: ((String) -> androidx.compose.ui.graphics.Color)? = null,
) {
    // Chunked rows keep the height bounded — safe inside LazyColumn items (no nested scroll).
    Column {
        options.chunked(3).forEach { rowOptions ->
            Row {
                rowOptions.forEach { (wire, label) ->
                    FilterChip(
                        selected = selected == wire,
                        onClick = { onSelect(wire) },
                        label = { Text(label) },
                        leadingIcon = if (leadingSwatch != null) {
                            { ColorSwatchDot(leadingSwatch(wire)) }
                        } else {
                            null
                        },
                        modifier = Modifier
                            .padding(end = 8.dp, top = 4.dp)
                            .testTag(tagPrefix(wire)),
                    )
                }
            }
        }
    }
}

@Composable
private fun ToggleCard(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.height(56.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            // Independent toggles that can BOTH be on (wet AND solid) — the old
            // either/or read silently mislogged half of all diapers (Jordan, round 2).
            if (selected) {
                Icon(
                    painterResource(R.drawable.ic_bb_check),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.width(6.dp))
            }
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
