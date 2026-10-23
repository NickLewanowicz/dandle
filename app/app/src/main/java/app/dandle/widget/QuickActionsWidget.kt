package app.dandle.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.dandle.App
import app.dandle.R
import app.dandle.domain.model.EventType
import app.dandle.ui.dashboard.iconRes



// Responsive options bucketed on both axes (W2). LocalSize inside the composition is the
// selected option, so branching on it picks the branch per placed size. 110dp fits 2 launcher
// cells with margin (minWidth in quick_actions_widget_info.xml must stay at or below this —
// W1), 250dp covers 3-4 cells across, 220dp covers 2-3 rows tall.
private val SIZE_GRID = DpSize(110.dp, 110.dp) // 2×2 grid
private val SIZE_ROW = DpSize(250.dp, 110.dp) // 4×1 / 3×1 chip row
private val SIZE_TALL = DpSize(110.dp, 220.dp) // 2×3+ grid, capped cells

/**
 * QuickActions widget (spec §7.1, UX spec §6): four configurable slots, default Sleep toggle,
 * Bottle, Diaper, Pump. Layout buckets on BOTH axes (UX-review W2):
 *
 *  - narrow (default 2×2 placement): the spec'd 2×2 grid — per-cell rounded chips with
 *    24dp glyph + 12sp label (W12/W13);
 *  - wide-short (4×1/3×1): one 4-across chip row, capped to content height and vertically
 *    centered — never stretched to the frame height (W2);
 *  - narrow-tall (2×3+): the grid, centered, cells capped to chip content.
 *
 * Idle cells read as surface buttons (surfaceVariant chips, W5); a running timer slot swaps to
 * the primaryContainer accent chip with the minute-granular elapsed replacing the label (W3/W4)
 * — no per-second ticking (UX spec §6: minute granularity, staleness documented).
 *
 * Tap semantics mirror the app (spec §3/§7.1):
 *  - idle timer slots that need no choices (Sleep / Pump / Tummy) → start the timer in place via
 *    [WidgetTimerStartAction] — one tap, no app launch;
 *  - RUNNING slot → deep-links straight into the app's >10s stop-guard dialog for that timer
 *    (T5: the label reads "tap to stop" and now does; widgets cannot host the dialog themselves,
 *    and the decision renders from state so there is no launch-time race);
 *  - Bottle / Diaper / Note slots → deep-link into the app with that panel pre-opened (those
 *    need choices — type/method, wet/solid — the widget can't guess them).
 *
 * Config (child + four slots, spec §7.3) lives in this widget's Preferences state, written by
 * [QuickActionsConfigActivity]; one-tap add (no config yet) uses the active child.
 */
class QuickActionsWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(SIZE_GRID, SIZE_ROW, SIZE_TALL))

    @Suppress("UNCHECKED_CAST")
    override val stateDefinition: GlanceStateDefinition<Preferences> =
        PreferencesGlanceStateDefinition as GlanceStateDefinition<Preferences>

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = (context.applicationContext as App).container
        val prefs = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
        val childId = container.quickActionActions.activeChildId()
        val slots = if (childId == null) {
            emptyList()
        } else {
            val configured = configuredSlots(prefs)
            val nowMillis = container.serverClock.nowMillis()
            // U10: each slot whispers when its type last happened — one small query per slot.
            val latest = configured.associateWith { type ->
                container.eventRepository.latestByType(childId, type)
            }.filterValues { it != null }.mapValues { it.value!! }
            WidgetStateMapper.quickActionSlots(
                configured = configured,
                timers = container.timerRepository.snapshotRunning(),
                childId = childId,
                nowMillis = nowMillis,
                latest = latest,
            )
        }

        provideContent {
            GlanceTheme {
                QuickActionsContent(slots)
            }
        }
    }

    companion object {
        val KEY_CHILD_ID = longPreferencesKey("child_id")
        private fun slotKey(index: Int) = stringPreferencesKey("slot$index")

        val DEFAULT_SLOTS = listOf(EventType.SLEEP, EventType.FEEDING, EventType.CHANGE, EventType.PUMPING)

        fun configuredSlots(prefs: Preferences): List<EventType> =
            (0 until 4).map { index ->
                runCatching { EventType.valueOf(prefs[slotKey(index)] ?: "") }.getOrNull()
                    ?: DEFAULT_SLOTS[index]
            }

        fun configuredChildId(prefs: Preferences): Long? =
            prefs[KEY_CHILD_ID]

        suspend fun saveConfig(context: Context, id: GlanceId, childId: Long, slots: List<EventType>) {
            updateAppWidgetState(context, id) { prefs ->
                prefs[KEY_CHILD_ID] = childId
                slots.forEachIndexed { index, type -> prefs[slotKey(index)] = type.name }
            }
            QuickActionsWidget().update(context, id)
        }

    }
}

/** Manifest receiver (appwidget-provider meta-data → quick_actions_widget_info). */
class QuickActionsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickActionsWidget()
}

@Composable
private fun QuickActionsContent(slots: List<WidgetStateMapper.QuickActionSlot>) {
    // W5: paint the widget surface so text/icons never float on the wallpaper. widgetBackground
    // (not background) is the launcher's Material-3 widget surface token — system dynamic color
    // on S+ (system_accent2_50/800 day/night), resource-resolved at apply time by the host.
    //
    // Layout uses only the Glance constructs that translate reliably to RemoteViews (verified on
    // device): a fillMaxSize Column whose children weight the leftover space, full-width rows of
    // defaultWeight cells, and fixed-dp row heights. Chips fill their weighted cell; the W2
    // height caps live in the row heights, never in wrap-content chains.
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .padding(2.dp),
    ) {
        val context = LocalContext.current
        val size = LocalSize.current
        when {
            // Wide-short (W2): one 4-across chip row capped to content height (~56dp = 24dp
            // glyph + 12sp label + padding), vertically centered — never stretched to the frame.
            size.width >= SIZE_ROW.width -> {
                Box(modifier = GlanceModifier.defaultWeight()) {}
                Row(modifier = GlanceModifier.fillMaxWidth().height(56.dp)) {
                    slots.forEach { slot ->
                        Box(modifier = GlanceModifier.defaultWeight()) {
                            SlotCell(context, slot)
                        }
                    }
                }
                Box(modifier = GlanceModifier.defaultWeight()) {}
            }
            // Tall (W2): the grid, vertically centered, cell rows capped at ~72dp.
            size.height >= SIZE_TALL.height -> {
                Box(modifier = GlanceModifier.defaultWeight()) {}
                slots.chunked(2).forEach { rowSlots ->
                    Row(modifier = GlanceModifier.fillMaxWidth().height(72.dp)) {
                        rowSlots.forEach { slot ->
                            Box(modifier = GlanceModifier.defaultWeight()) {
                                SlotCell(context, slot)
                            }
                        }
                        repeat(2 - rowSlots.size) {
                            Box(modifier = GlanceModifier.defaultWeight()) {}
                        }
                    }
                }
                Box(modifier = GlanceModifier.defaultWeight()) {}
            }
            // Default 2×2 (W1): the spec grid — cells expand to their quadrants, chips fill
            // them with 4dp gaps (target sketch, review §Target look). Every row carries an
            // explicit width: on API 33+ Glance only applies layout params from modifiers, and
            // an unspecified row width wraps, collapsing the weighted cells (owner P1).
            else -> {
                slots.chunked(2).forEach { rowSlots ->
                    Row(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                        rowSlots.forEach { slot ->
                            Box(modifier = GlanceModifier.defaultWeight()) {
                                SlotCell(context, slot)
                            }
                        }
                        repeat(2 - rowSlots.size) {
                            Box(modifier = GlanceModifier.defaultWeight()) {}
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SlotCell(
    context: Context,
    slot: WidgetStateMapper.QuickActionSlot,
) {
    val colors = GlanceTheme.colors
    val running = slot.running
    val action = if (running) {
        // T5/U3: the running slot's tap deep-links straight into the app's stop-guard dialog
        // for this timer (≤10s discards silently, in-app semantics).
        WidgetActions.openStopTimerModifier(context, slot.type)
    } else {
        GlanceModifier.clickable(slotAction(context, slot.type))
    }
    // U10/U11: a running slot takes its TYPE's color (not generic primary) so "the nap slot is
    // blue and lit up" reads at a glance; idle slots stay surface buttons.
    val dark = (LocalContext.current.resources.configuration.uiMode and
        android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
    val (containerArgb, onContainerArgb) = app.dandle.ui.theme.TypeColors
        .argbPair(slot.type, dark)
    val fill = if (running) ColorProvider(androidx.compose.ui.graphics.Color(containerArgb)) else colors.surfaceVariant
    val content = if (running) {
        ColorProvider(androidx.compose.ui.graphics.Color(onContainerArgb))
    } else {
        colors.onSurface
    }
    // 2dp inset per cell = 4dp gaps between chips and ≤4dp outer breathing room; the chip fills
    // the rest of its weighted cell so the grid fills the widget (owner padding fix) and 12sp
    // labels get the cell's full width (wrap-content chips collapsed to icon width and
    // ellipsized "Bott…" — never do content-sized chips in a weighted cell).
    Box(modifier = GlanceModifier.fillMaxSize().padding(2.dp)) {
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .then(action)
                .background(fill)
                .cornerRadius(12.dp)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    provider = ImageProvider(slot.type.iconRes()),
                    contentDescription = slot.label,
                    modifier = GlanceModifier.size(24.dp),
                    colorFilter = ColorFilter.tint(content),
                )
                // W4/U10: a running slot renders the elapsed counter (fall back to the label —
                // never an empty chip); idle slots keep the label plus the subdued "last" line.
                Text(
                    if (running) slot.elapsedText ?: slot.label else slot.label,
                    style = TextStyle(
                        color = content,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    maxLines = 1,
                )
                if (!running && slot.lastText != null) {
                    Text(
                        slot.lastText,
                        style = TextStyle(
                            color = ColorProvider(colors.onSurface.getColor(context).copy(alpha = 0.55f)),
                            fontSize = 10.sp,
                        ),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Idle timer slots that need no choices start in place; everything else deep-links into the app. */
private fun slotAction(context: Context, type: EventType): androidx.glance.action.Action = when (type) {
    EventType.SLEEP, EventType.PUMPING, EventType.TUMMY_TIME ->
        actionRunCallback<WidgetTimerStartAction>(
            actionParametersOf(WidgetTimerStartAction.KEY_TYPE to type.name),
        )
    else -> WidgetActions.openPanelAction(context, type)
}
