package app.dandle.ui.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.dandle.R
import app.dandle.domain.ElapsedTimeFormatter
import app.dandle.domain.FeedingOptions
import app.dandle.domain.StopGuard
import app.dandle.domain.model.EventType
import app.dandle.domain.model.RunningTimer
import app.dandle.ui.TestTags
import app.dandle.ui.dialogTestTags
import app.dandle.ui.theme.LocalReduceMotion

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
                    // Emoji icons (v5 mocks): device-font rendered, multicolor — no tint.
                    Text(type.emoji, fontSize = 26.sp)
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
    fun onNoteChange(text: String)
    fun onMilestoneChange(text: String)
    fun onTextChange(text: String)

    /** T2: "started X min ago" adjust — [minutesAgoDelta] > 0 backdates the start. */
    fun onStartAdjustStepped(minutesAgoDelta: Long)
    fun onStartTimePicked(millis: Long)
}

/**
 * The running-timer card, design framework v2.3 (spec §2.3/§8): a themed SCENE card — one
 * motif per activity (night/milk/sun/bottle, [sceneFor]) — with the pinned anatomy: icon chip ·
 * name · HH:MM:SS counter · icon-only ✓ save · the type's fields · "Started … · Adjust".
 * Decorations (moon/stars/drops) render z-below the content and gate on reduce-motion; every
 * text/surface pair clears WCAG AA (pinned by TimerSceneTest). No ✕, no "Save"/"Running"
 * words, no synced indicator (rule 7 — the happy path is silent). Stopping happens ON the
 * card (rule 3): re-tap of the running type's button raises the in-card stop band
 * ([showStopForm]) — >10s shows it, ≤10s discards silently before the band exists.
 */
@Composable
fun RunningTimerCard(
    panel: OpenPanel,
    nowMillis: Long,
    saveEnabled: Boolean,
    callbacks: QuickActionCallbacks,
    showStopForm: Boolean = false,
    onStopSave: () -> Unit = {},
    onStopKeep: () -> Unit = {},
    onStopDiscard: () -> Unit = {},
) {
    val type = panel.type
    val timer = panel.timer ?: return
    val scene = sceneFor(type)
    val typeColors = type.typeColorPair()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .shadow(6.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .then(
                if (scene != null) {
                    Modifier.background(scene.backgroundBrush())
                } else {
                    Modifier.background(MaterialTheme.colorScheme.surface)
                },
            )
            .testTag(TestTags.panel(type.name)),
    ) {
        if (scene != null) {
            SceneBackdrop(scene, Modifier.matchParentSize())
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 14.dp, bottom = 12.dp),
        ) {
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
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(cardPalette(scene, typeColors).chip),
                ) {
                    Text(
                        if (type == EventType.FEEDING) feedingEmoji(panel.draft.feedingMethod) else type.emoji,
                        fontSize = 19.sp,
                    )
                }
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    Text(
                        type.label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = cardPalette(scene, typeColors).content,
                    )
                    Text(
                        ElapsedTimeFormatter.timerCounter(
                            (nowMillis - timer.startedAtMillis).coerceAtLeast(0),
                        ),
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontFeatureSettings = "tnum",
                        ),
                        fontWeight = FontWeight.SemiBold,
                        color = cardPalette(scene, typeColors).content,
                    )
                }
                Spacer(Modifier.weight(1f))
                // Icon-only save lives in the HEADER (always on screen): the feeding
                // card's fields push a bottom-corner save below the fold (UX round 2 P0).
                Surface(
                    onClick = callbacks::onSavePanel,
                    enabled = saveEnabled,
                    shape = RoundedCornerShape(11.dp),
                    color = cardPalette(scene, typeColors).saveBg,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(40.dp)
                        .testTag(TestTags.timerSave(type.name)),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painterResource(R.drawable.ic_bb_check),
                            contentDescription = "Save",
                            modifier = Modifier.size(22.dp),
                            tint = if (saveEnabled) {
                                cardPalette(scene, typeColors).saveIcon
                            } else {
                                cardPalette(scene, typeColors).saveIcon.copy(alpha = 0.38f)
                            },
                        )
                    }
                }
            }

            when (type) {
                EventType.FEEDING -> FeedingCardFields(panel, scene, typeColors, callbacks)
                EventType.SLEEP -> NoteField(
                    value = panel.draft.noteText,
                    onValueChange = callbacks::onNoteChange,
                    label = "Note",
                    scene = scene,
                    typeColors = typeColors,
                )
                EventType.PUMPING -> {
                    AmountField(
                        value = panel.draft.amountText,
                        onValueChange = callbacks::onAmountChange,
                        modifier = Modifier.padding(top = 10.dp),
                        scene = scene,
                        typeColors = typeColors,
                    )
                    NoteField(
                        value = panel.draft.noteText,
                        onValueChange = callbacks::onNoteChange,
                        label = "Note",
                        scene = scene,
                        typeColors = typeColors,
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
                        colors = fieldColors(scene, typeColors),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                            .testTag(TestTags.PANEL_MILESTONE),
                    )
                    NoteField(
                        value = panel.draft.noteText,
                        onValueChange = callbacks::onNoteChange,
                        label = "Note",
                        scene = scene,
                        typeColors = typeColors,
                    )
                }
                else -> Unit
            }

            // Framework rule 3: stopping happens on the card. The >10s stop guard renders as
            // an in-card band (same Save-emphasized / subdued-Discard layout as the old
            // dialog; ≤10s discards silently and this band never appears).
            if (showStopForm) {
                StopFormBand(
                    type = type,
                    elapsedMillis = (nowMillis - timer.startedAtMillis).coerceAtLeast(0),
                    scene = scene,
                    typeColors = typeColors,
                    onSave = onStopSave,
                    onKeep = onStopKeep,
                    onDiscard = onStopDiscard,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }

            StartedAgoRow(panel, nowMillis, scene, typeColors, callbacks)
        }
    }
}

/**
 * The >10s stop confirmation as an in-card band (framework v2.3 rule 3): "Stop timer?" + the
 * spelled duration, Save as the emphasized full-width action, Keep and Discard subdued on a
 * second row — the destructive action never sits next to Save (thumb-slip = erased nap).
 * Reuses the historical stop-dialog test tags so QA scripts and capture canaries are stable.
 */
@Composable
private fun StopFormBand(
    type: EventType,
    elapsedMillis: Long,
    scene: TimerSceneSpec?,
    typeColors: app.dandle.ui.theme.TypeColorPair,
    onSave: () -> Unit,
    onKeep: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = cardPalette(scene, typeColors)
    val strong = saveStrongColors(scene, typeColors)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(p.band)
            .padding(12.dp)
            .testTag(TestTags.STOP_DIALOG),
    ) {
        Text(
            StopGuard.DIALOG_TITLE,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = p.content,
        )
        Text(
            StopGuard.dialogBody(type.label, elapsedMillis),
            style = MaterialTheme.typography.bodySmall,
            color = p.contentDim,
            modifier = Modifier.padding(top = 2.dp),
        )
        Button(
            onClick = onSave,
            colors = ButtonDefaults.buttonColors(
                containerColor = strong.container,
                contentColor = strong.onContainer,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .height(44.dp)
                .testTag(TestTags.STOP_DIALOG_SAVE),
        ) { Text("Save", fontWeight = FontWeight.SemiBold) }
        Row(modifier = Modifier.padding(top = 2.dp)) {
            TextButton(
                onClick = onKeep,
                colors = buttonTextColor(p.accent),
                modifier = Modifier.testTag(TestTags.STOP_DIALOG_KEEP),
            ) { Text("Keep running") }
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = onDiscard,
                colors = buttonTextColor(p.destructive),
                modifier = Modifier.testTag(TestTags.STOP_DIALOG_DISCARD),
            ) { Text("Discard") }
        }
    }
}

/**
 * The feeding card's cascade (framework v2.3 rule 5): type gates methods
 * ([FeedingOptions.methodsFor]); a complete choice collapses to a one-line summary and only
 * Amount + Note remain. Amount is unit-agnostic — the label is just "Amount" (rule 10).
 */
@Composable
private fun FeedingCardFields(
    panel: OpenPanel,
    scene: TimerSceneSpec?,
    typeColors: app.dandle.ui.theme.TypeColorPair,
    callbacks: QuickActionCallbacks,
) {
    var expanded by remember { mutableStateOf(false) }
    val cascade = feedingCascade(panel.draft.feedingType, panel.draft.feedingMethod, expanded)
    Column(modifier = Modifier.padding(top = 6.dp)) {
        FeedingCascadeRows(
            typeWire = panel.draft.feedingType,
            methodWire = panel.draft.feedingMethod,
            expanded = expanded,
            onTypeChange = callbacks::onFeedingTypeChange,
            onMethodChange = {
                callbacks.onFeedingMethodChange(it)
                expanded = false // a fresh complete choice collapses again (rule 5)
            },
            onReopen = { expanded = true },
            scene = scene,
            typeColors = typeColors,
        )
        if (cascade.isCollapsed) {
            AmountField(
                value = panel.draft.amountText,
                onValueChange = callbacks::onAmountChange,
                modifier = Modifier.padding(top = 10.dp),
                scene = scene,
                typeColors = typeColors,
            )
            NoteField(
                value = panel.draft.noteText,
                onValueChange = callbacks::onNoteChange,
                label = "Note",
                scene = scene,
                typeColors = typeColors,
            )
        }
    }
}

/**
 * Shared cascade rows (running card with a scene, Quick Edit sheet with MaterialTheme):
 * renders the collapsed summary OR the type row (+ hint) and the type-filtered method row.
 */
@Composable
fun FeedingCascadeRows(
    typeWire: String?,
    methodWire: String?,
    expanded: Boolean,
    onTypeChange: (String) -> Unit,
    onMethodChange: (String) -> Unit,
    onReopen: () -> Unit,
    scene: TimerSceneSpec?,
    typeColors: app.dandle.ui.theme.TypeColorPair,
) {
    val cascade = feedingCascade(typeWire, methodWire, expanded)
    val p = cardPalette(scene, typeColors)
    if (cascade.showSummary) {
        Surface(
            onClick = onReopen,
            shape = RoundedCornerShape(12.dp),
            color = p.summaryBg,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(TestTags.PANEL_FEEDING_SUMMARY),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(
                    cascade.summaryText.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = p.content,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "change ✎",
                    style = MaterialTheme.typography.labelMedium,
                    color = p.accent,
                )
            }
        }
    }
    if (cascade.showTypeRow) {
        Text(
            if (typeWire == null) "Type — pick one" else "Type",
            style = MaterialTheme.typography.labelLarge,
            color = p.contentDim,
            modifier = Modifier.padding(top = if (cascade.showSummary) 10.dp else 4.dp),
        )
        SceneChipRow(
            options = FeedingOptions.TYPES.map { it.wire to it.label },
            selected = typeWire,
            tagPrefix = { TestTags.feedingTypeChip(it) },
            onSelect = onTypeChange,
            scene = scene,
            typeColors = typeColors,
        )
        if (typeWire == null) {
            Text(
                "Methods appear once type is picked",
                style = MaterialTheme.typography.labelSmall,
                color = p.fieldHint,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
    if (cascade.showMethodRow) {
        Text(
            "Method — filtered by type",
            style = MaterialTheme.typography.labelLarge,
            color = p.contentDim,
            modifier = Modifier.padding(top = 8.dp),
        )
        SceneChipRow(
            options = FeedingOptions.methodsFor(typeWire).map { it.wire to it.label },
            selected = methodWire,
            tagPrefix = { TestTags.feedingMethodChip(it) },
            onSelect = onMethodChange,
            scene = scene,
            typeColors = typeColors,
        )
    }
}

/**
 * The create sheet for the edit-like quick actions (owner rework U4, redesigned v-dandle):
 * diaper and note open a modal bottom sheet. The TIME row is GONE (framework v2.3 — time is
 * the save time; corrections go through the edit flow, see docs/issues-pending/
 * gitea-issue-backdating.md). Diaper form: Wet/Solid toggles, a single-select COLOR slider
 * (server enum is one string, never multiple), Amount, Note. Save runs the duplicate guard;
 * ✕/scrim keeps the draft.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateEntrySheet(
    panel: OpenPanel,
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
                        Text(type.emoji, fontSize = 17.sp)
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
    scene: TimerSceneSpec?,
    typeColors: app.dandle.ui.theme.TypeColorPair,
    callbacks: QuickActionCallbacks,
) {
    var expanded by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    val p = cardPalette(scene, typeColors)

    Column(modifier = Modifier.padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                startedLabel(panel, nowMillis),
                style = MaterialTheme.typography.labelMedium,
                color = p.contentDim,
                modifier = Modifier
                    .weight(1f)
                    .testTag(TestTags.PANEL_STARTED_AGO),
            )
            TextButton(
                onClick = { expanded = !expanded },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                colors = buttonTextColor(p.accent),
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
                        colors = buttonTextColor(p.accent),
                        modifier = Modifier.testTag(TestTags.startedAgoStep(label)),
                    ) { Text(label, style = MaterialTheme.typography.labelMedium) }
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { showPicker = true },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                    colors = buttonTextColor(p.accent),
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
    // An adopted/restored timer (app relaunch while running) must drive the age from its
    // ABSOLUTE start — the draft's relative minutesAgo is 0 for a restored panel, which
    // claimed "just now" on a hours-old timer (QA 2026-10-19). The Adjust backdate adds on
    // top, mirroring the save path (TimerRepository.stopAndSave: start = timerStart −
    // adjust), so the row and the saved start can't disagree.
    val minutesAgo = when (val timer = panel.timer) {
        null -> panel.draft.startedMinutesAgo
        else -> ((nowMillis - timer.startedAtMillis + 30_000L) / 60_000L).toInt()
            .coerceAtLeast(0) + panel.draft.startedMinutesAgo
    }
    val startMillis = (panel.timer?.startedAtMillis ?: nowMillis) -
        panel.draft.startedMinutesAgo * 60_000L
    val clock = java.time.Instant.ofEpochMilli(startMillis)
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

/** Diaper sheet body (v-dandle redesign): toggles + color slider + amount + note. */
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

        Text(
            "Color",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 12.dp),
        )
        DiaperColorSlider(
            selected = panel.draft.color,
            onSelect = callbacks::onColorChange,
            modifier = Modifier.padding(top = 4.dp),
        )
        AmountField(
            value = panel.draft.amountText,
            onValueChange = callbacks::onAmountChange,
            modifier = Modifier.padding(top = 8.dp),
            scene = null,
            typeColors = EventType.CHANGE.typeColorPair(),
        )
        NoteField(
            value = panel.draft.noteText,
            onValueChange = callbacks::onNoteChange,
            label = "Note",
            scene = null,
            typeColors = EventType.CHANGE.typeColorPair(),
        )
    }
}

/**
 * The diaper COLOR slider (framework v2.3): a single-select swipeable strip — the server enum
 * is ONE string (`color`, research doc §4: black/brown/gray/green/orange/red/white/yellow),
 * never multiple. Selected dot gets a ring + scale (static under reduce-motion); every dot is
 * tappable (accessibility parity with the old chips, whose per-wire test tags are preserved).
 */
@Composable
fun DiaperColorSlider(
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalReduceMotion.current
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 6.dp, horizontal = 2.dp)
                .testTag(TestTags.PANEL_COLOR_SLIDER),
        ) {
            DiaperSwatches.ALL.forEach { swatch ->
                val isSelected = selected == swatch.wire
                val scale = if (reduceMotion) {
                    if (isSelected) 1.12f else 1f
                } else {
                    androidx.compose.animation.core.animateFloatAsState(
                        targetValue = if (isSelected) 1.12f else 1f,
                        label = "color-scale",
                    ).value
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(36.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                            }
                            .border(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) {
                                    swatch.color.darken()
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                },
                                shape = CircleShape,
                            )
                            .background(swatch.color, CircleShape)
                            // Single-select: the VM's onColorChange toggles the same wire off.
                            .clickable { onSelect(swatch.wire) }
                            .semantics {
                                this.role = androidx.compose.ui.semantics.Role.RadioButton
                                this.selected = isSelected
                                this.contentDescription = swatch.label
                            }
                            .testTag(TestTags.diaperColorChip(swatch.wire)),
                    )
                }
            }
        }
        Text(
            selected?.let { DiaperSwatches.labelOf(it) } ?: "Optional",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 2.dp),
        )
    }
}

/** Slightly darker edge for a selected swatch's ring (white/yellow need a visible ring). */
private fun Color.darken(): Color = Color(
    red = red * 0.72f,
    green = green * 0.72f,
    blue = blue * 0.72f,
    alpha = 1f,
)

/** Amount is unit-agnostic (framework v2.3 rule 10): the label is just "Amount", never mL. */
@Composable
private fun AmountField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    scene: TimerSceneSpec?,
    typeColors: app.dandle.ui.theme.TypeColorPair,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("Amount") },
        singleLine = true,
        keyboardOptions = imeDoneOptions,
        keyboardActions = doneKeyboardActions(),
        colors = fieldColors(scene, typeColors),
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
    scene: TimerSceneSpec?,
    typeColors: app.dandle.ui.theme.TypeColorPair,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = imeDoneOptions,
        keyboardActions = doneKeyboardActions(),
        colors = fieldColors(scene, typeColors),
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
    leadingSwatch: ((String) -> Color)? = null,
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

/**
 * Scene-aware pill chips (framework v2.3 rule 4 — the feeding cascade lives ON the scene
 * card): translucent unselected pills, near-solid selected pill with the scene's content
 * color (mock `.chips span[.on]`). With scene == null this defers to M3 FilterChip styling.
 */
@Composable
private fun SceneChipRow(
    options: List<Pair<String, String>>,
    selected: String?,
    tagPrefix: (String) -> String,
    onSelect: (String) -> Unit,
    scene: TimerSceneSpec?,
    typeColors: app.dandle.ui.theme.TypeColorPair,
) {
    if (scene == null) {
        ChoiceChipRow(options, selected, tagPrefix, onSelect)
        return
    }
    val p = cardPalette(scene, typeColors)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
        options.forEach { (wire, label) ->
            val isSelected = selected == wire
            Surface(
                onClick = { onSelect(wire) },
                shape = RoundedCornerShape(16.dp),
                color = if (isSelected) p.chipSelected else p.chip,
                modifier = Modifier.testTag(tagPrefix(wire)),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    // Selected pill flips to the dark-on-light color on dark scenes.
                    color = if (isSelected) p.chipSelectedText else p.content,
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                )
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

// -- Scene/Material color resolution -----------------------------------------------

/** Everything on the card that takes a color, resolved from the scene or the M3 theme. */
private data class CardPalette(
    val content: Color,
    val contentDim: Color,
    val chip: Color,
    val saveBg: Color,
    val saveIcon: Color,
    val field: Color,
    val fieldText: Color,
    val fieldHint: Color,
    val accent: Color,
    val band: Color,
    val summaryBg: Color,
    val chipSelected: Color,
    val chipSelectedText: Color,
    val destructive: Color,
)

@Composable
private fun cardPalette(scene: TimerSceneSpec?, typeColors: app.dandle.ui.theme.TypeColorPair): CardPalette =
    if (scene != null) {
        CardPalette(
            content = Color(scene.content),
            contentDim = Color(scene.contentDim),
            chip = Color(scene.iconChip),
            saveBg = Color(scene.saveBg),
            saveIcon = Color(scene.saveIcon),
            field = Color(scene.fieldBg),
            fieldText = Color(scene.fieldText),
            fieldHint = Color(scene.fieldHint),
            accent = Color(scene.accent),
            band = Color(scene.bandBg),
            summaryBg = Color(scene.chipBg),
            chipSelected = Color(scene.chipSelectedBg),
            chipSelectedText = Color(scene.chipSelectedText),
            destructive = Color(scene.destructive),
        )
    } else {
        CardPalette(
            content = MaterialTheme.colorScheme.onSurface,
            contentDim = MaterialTheme.colorScheme.onSurfaceVariant,
            chip = typeColors.container,
            saveBg = typeColors.container,
            saveIcon = typeColors.onContainer,
            field = MaterialTheme.colorScheme.surface,
            fieldText = MaterialTheme.colorScheme.onSurface,
            fieldHint = MaterialTheme.colorScheme.onSurfaceVariant,
            accent = MaterialTheme.colorScheme.primary,
            band = MaterialTheme.colorScheme.surfaceVariant,
            summaryBg = MaterialTheme.colorScheme.surfaceVariant,
            chipSelected = MaterialTheme.colorScheme.secondaryContainer,
            chipSelectedText = MaterialTheme.colorScheme.onSecondaryContainer,
            destructive = MaterialTheme.colorScheme.error,
        )
    }

private data class StrongColors(val container: Color, val onContainer: Color)

@Composable
private fun saveStrongColors(
    scene: TimerSceneSpec?,
    typeColors: app.dandle.ui.theme.TypeColorPair,
): StrongColors = if (scene != null) {
    StrongColors(Color(scene.saveStrongBg), Color(scene.saveStrongText))
} else {
    StrongColors(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
}

/** TextButton with an explicit content color (scene accent / destructive on scenes). */
@Composable
private fun buttonTextColor(color: Color) = ButtonDefaults.textButtonColors(contentColor = color)

/**
 * OutlinedTextField colors on a scene: elevated field surface, scene text/hint, transparent
 * resting border and an accent focus border (focus must stay perceivable without outlines).
 */
@Composable
private fun fieldColors(
    scene: TimerSceneSpec?,
    typeColors: app.dandle.ui.theme.TypeColorPair,
) = if (scene != null) {
    val p = cardPalette(scene, typeColors)
    OutlinedTextFieldDefaults.colors(
        focusedContainerColor = p.field,
        unfocusedContainerColor = p.field,
        disabledContainerColor = p.field,
        focusedTextColor = p.fieldText,
        unfocusedTextColor = p.fieldText,
        cursorColor = p.accent,
        focusedBorderColor = p.accent,
        unfocusedBorderColor = Color.Transparent,
        disabledBorderColor = Color.Transparent,
        focusedLabelColor = p.fieldHint,
        unfocusedLabelColor = p.fieldHint,
    )
} else {
    OutlinedTextFieldDefaults.colors()
}
