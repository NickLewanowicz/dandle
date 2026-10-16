package net.babybuddy.next.ui.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import net.babybuddy.next.domain.ElapsedTimeFormatter
import net.babybuddy.next.domain.FeedingOptions
import net.babybuddy.next.domain.model.EventType
import net.babybuddy.next.domain.model.RunningTimer
import net.babybuddy.next.ui.TestTags
import net.babybuddy.next.ui.dashboard.OpenPanel

/**
 * The six quick-action buttons (spec §2.3, UX spec §2): canonical order Feeding, Diaper,
 * Sleep, Notes, Pumping, Tummy time — 3×2 grid of 64dp tonal circles with labels. Timer types
 * show the running state: primaryContainer circle + a small static dot; tap re-opens the
 * running panel, never a duplicate (spec §3).
 */
@Composable
fun QuickActionsGrid(
    childPresent: Boolean,
    runningKinds: Set<EventType>,
    openType: EventType?,
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
    enabled: Boolean,
    onTap: () -> Unit,
) {
    val active = running || open
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            Surface(
                onClick = onTap,
                enabled = enabled,
                shape = CircleShape,
                color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .size(64.dp)
                    .testTag(TestTags.quickAction(type.name)),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painterResource(type.iconRes()),
                        contentDescription = type.label,
                        modifier = Modifier.size(28.dp),
                        tint = if (active) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            if (running) {
                // Static running dot (UX spec §2 — no pulsing).
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = (-6).dp, y = 6.dp)
                        .size(9.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                )
            }
        }
        Text(
            type.label,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** Contract between the panels and the ViewModel (implemented by DashboardViewModel). */
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
}

/**
 * The inline logging panel (UX spec §3): a card directly below the grid pushing the timeline
 * down. Timer types show the live counter; Save is the only validation surface.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickActionPanel(
    panel: OpenPanel,
    nowMillis: Long,
    saveEnabled: Boolean,
    callbacks: QuickActionCallbacks,
) {
    val type = panel.type
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .testTag(TestTags.panel(type.name)),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painterResource(type.iconRes()),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                Text(
                    type.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 12.dp),
                )
                Box(modifier = Modifier.weight(1f))
                if (type.isTimed && panel.timer != null) {
                    Text(
                        ElapsedTimeFormatter.timerCounter(nowMillis - panel.timer.startedAtMillis),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                IconButton(onClick = { callbacks.onClosePanel(type) }, modifier = Modifier.testTag(TestTags.PANEL_CLOSE)) {
                    Text("✕", style = MaterialTheme.typography.titleMedium)
                }
            }

            when (type) {
                EventType.FEEDING -> FeedingFields(panel, callbacks)
                EventType.CHANGE -> DiaperFields(panel, callbacks)
                EventType.SLEEP -> NoteField(
                    value = panel.draft.noteText,
                    onValueChange = callbacks::onNoteChange,
                    label = "Note",
                )
                EventType.PUMPING -> {
                    AmountField(
                        value = panel.draft.amountText,
                        onValueChange = callbacks::onAmountChange,
                        modifier = Modifier.padding(top = 12.dp),
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .testTag(TestTags.PANEL_MILESTONE),
                    )
                    NoteField(
                        value = panel.draft.noteText,
                        onValueChange = callbacks::onNoteChange,
                        label = "Note",
                    )
                }
                EventType.NOTE -> OutlinedTextField(
                    value = panel.draft.text,
                    onValueChange = callbacks::onTextChange,
                    label = { Text("Note") },
                    minLines = 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .testTag(TestTags.PANEL_TEXT),
                )
            }

            Button(
                onClick = callbacks::onSavePanel,
                enabled = saveEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .height(56.dp)
                    .testTag(TestTags.PANEL_SAVE),
            ) {
                Text("Save")
            }
        }
    }
}

// -- Per-type fields -------------------------------------------------------------

@Composable
private fun FeedingFields(panel: OpenPanel, callbacks: QuickActionCallbacks) {
    Column {
        Text("Type", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
        ChoiceChipRow(
            options = FeedingOptions.TYPES.map { it.wire to it.label },
            selected = panel.draft.feedingType,
            tagPrefix = { TestTags.feedingTypeChip(it) },
            onSelect = callbacks::onFeedingTypeChange,
        )
        Text("Method", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
        ChoiceChipRow(
            options = FeedingOptions.methodsFor(panel.draft.feedingType).map { it.wire to it.label },
            selected = panel.draft.feedingMethod,
            tagPrefix = { TestTags.feedingMethodChip(it) },
            onSelect = callbacks::onFeedingMethodChange,
        )
        AmountField(
            value = panel.draft.amountText,
            onValueChange = callbacks::onAmountChange,
            supportingText = "mL — optional for nursing",
            modifier = Modifier.padding(top = 12.dp),
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
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
