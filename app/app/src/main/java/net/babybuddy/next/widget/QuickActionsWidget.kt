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
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
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



/**
 * QuickActions widget (spec §7.1, UX spec §6): four configurable slots, default Sleep toggle,
 * Bottle, Diaper, Pump. 2×2 with labels; renders as a 4×1 icon-only row when the host sizes it
 * wide (2×1 home cells). Running slots get the tonal accent + minute-granular elapsed — no
 * per-second ticking (UX spec §6: minute granularity, staleness documented).
 *
 * Tap semantics mirror the app (spec §3/§7.1):
 *  - idle timer slot (Sleep per default) → starts the timer via [WidgetSleepStartAction];
 *  - RUNNING slot → opens the app (timers strip) — widgets cannot host the >10s stop-guard
 *    dialog, and the decision renders from state so there is no launch-time race;
 *  - Bottle / Diaper / Pump slots → deep-link into the app with that panel pre-opened.
 *
 * Config (child + four slots, spec §7.3) lives in this widget's Preferences state, written by
 * [QuickActionsConfigActivity]; one-tap add (no config yet) uses the active child.
 */
class QuickActionsWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(SIZE_NARROW, SIZE_WIDE))

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
                QuickActionsContent(slots, wide = LocalSize.current.width >= SIZE_WIDE.width)
            }
        }
    }

    companion object {
        private val SIZE_NARROW = DpSize(150.dp, 110.dp)
        private val SIZE_WIDE = DpSize(240.dp, 110.dp)

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
private fun QuickActionsContent(slots: List<WidgetStateMapper.QuickActionSlot>, wide: Boolean) {
    Box(
        modifier = GlanceModifier.fillMaxSize().padding(2.dp),
        contentAlignment = Alignment.Center,
    ) {
        val context = LocalContext.current
        if (wide) {
            Row(modifier = GlanceModifier.fillMaxSize()) {
                slots.forEachIndexed { index, slot ->
                    Box(modifier = GlanceModifier.defaultWeight()) {
                        SlotCell(context, slot, compact = true)
                    }
                }
            }
        } else {
            Column(modifier = GlanceModifier.fillMaxSize()) {
                slots.chunked(2).forEach { rowSlots ->
                    Row(modifier = GlanceModifier.defaultWeight()) {
                        rowSlots.forEach { slot ->
                            Box(modifier = GlanceModifier.defaultWeight()) {
                                SlotCell(context, slot, compact = false)
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
    compact: Boolean,
) {
    val colors = GlanceTheme.colors
    val modifier = when {
        slot.running -> GlanceModifier
            .background(colors.secondaryContainer)
            .then(WidgetActions.openAppModifier())
        else -> GlanceModifier.clickable(slotAction(context, slot.type))
    }
    Box(
        modifier = GlanceModifier.fillMaxSize().padding(3.dp).then(modifier),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                provider = ImageProvider(slot.type.iconRes()),
                contentDescription = slot.label,
                modifier = GlanceModifier.size(26.dp),
                colorFilter = ColorFilter.tint(colors.onSurface),
            )
            if (!compact) {
                Text(
                    slot.label,
                    style = TextStyle(
                        color = colors.onSurface,
                        fontSize = 11.sp,
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
