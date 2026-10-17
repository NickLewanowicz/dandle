package net.babybuddy.next.widget

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
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import net.babybuddy.next.App
import net.babybuddy.next.R
import net.babybuddy.next.domain.model.EventType
import net.babybuddy.next.ui.dashboard.iconRes



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
 * Tap semantics mirror the app (spec §3/§7.1) — unchanged by the UX pass (QA-pinned):
 *  - idle timer slot (Sleep per default) → starts the timer via [WidgetSleepStartAction];
 *  - RUNNING slot → opens the app (timers strip) — widgets cannot host the >10s stop-guard
 *    dialog, and the decision renders from state so there is no launch-time race;
 *  - Bottle / Diaper / Pump slots → deep-link into the app with that panel pre-opened.
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
            WidgetStateMapper.quickActionSlots(
                configured = configuredSlots(prefs),
                timers = container.timerRepository.snapshotRunning(),
                childId = childId,
                nowMillis = container.serverClock.nowMillis(),
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
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .padding(2.dp),
        contentAlignment = Alignment.Center,
    ) {
        val context = LocalContext.current
        if (LocalSize.current.width >= SIZE_ROW.width) {
            // Wide-short (W2): one chip row capped to content height, vertically centered.
            // Width-only fill: weighted cells split the width evenly; height wraps the chips.
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                slots.forEach { slot ->
                    Box(
                        modifier = GlanceModifier.defaultWeight(),
                        contentAlignment = Alignment.Center,
                    ) {
                        SlotCell(context, slot)
                    }
                }
            }
        } else {
            // Grid (2×2 or tall): content-sized cells, centered — never stretched to the frame.
            // fillMaxWidth (not fillMaxSize) keeps cell height capped to chip content (W2).
            Column(
                modifier = GlanceModifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                slots.chunked(2).forEach { rowSlots ->
                    Row {
                        rowSlots.forEach { slot ->
                            Box(
                                modifier = GlanceModifier.defaultWeight(),
                                contentAlignment = Alignment.Center,
                            ) {
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
        WidgetActions.openAppModifier()
    } else {
        GlanceModifier.clickable(slotAction(context, slot.type))
    }
    val fill = if (running) colors.primaryContainer else colors.surfaceVariant
    val content = if (running) colors.onPrimaryContainer else colors.onSurface
    // 2dp gap between chips; the chip itself is a rounded state surface (W3/W5), sized to its
    // content (24dp glyph + 12sp label, W12/W13) so cells never stretch to the frame.
    Box(modifier = GlanceModifier.padding(2.dp)) {
        Box(
            modifier = GlanceModifier
                .then(action)
                .background(fill)
                .cornerRadius(12.dp)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Image(
                    provider = ImageProvider(slot.type.iconRes()),
                    contentDescription = slot.label,
                    modifier = GlanceModifier.size(24.dp),
                    colorFilter = ColorFilter.tint(content),
                )
                // W4: the running slot renders the elapsed time; if the state race leaves it
                // null, fall back to the label — never an empty chip.
                Text(
                    if (running) slot.elapsedText ?: slot.label else slot.label,
                    style = TextStyle(
                        color = content,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}

/** Idle timer slots start in place; everything else deep-links into the app. */
private fun slotAction(context: Context, type: EventType): androidx.glance.action.Action = when (type) {
    EventType.SLEEP -> actionRunCallback<WidgetSleepStartAction>()
    else -> WidgetActions.openPanelAction(context, type)
}
