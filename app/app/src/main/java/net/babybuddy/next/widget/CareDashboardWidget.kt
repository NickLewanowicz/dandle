package net.babybuddy.next.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
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
import kotlinx.coroutines.flow.first
import net.babybuddy.next.App
import net.babybuddy.next.domain.model.EventType
import net.babybuddy.next.ui.dashboard.iconRes



// W11: both buckets share the 4-cell width; the height selects the layout density.
// LocalSize inside the composition is the selected option.
private val SIZE_COMPACT = DpSize(250.dp, 110.dp) // 4×2
private val SIZE_RELAXED = DpSize(250.dp, 180.dp) // 4×3 breathing room

/**
 * CareDashboard widget (spec §7.2, UX spec §6): 4×2 (resizable 4×3) with a child-name header
 * (UX-review W8) and rows Last feed / Last diaper / Last nap / Last pump (minute-granular,
 * staleness documented) plus a running-timer row when one is active. Two size buckets (W11):
 * compact (4×2) and relaxed (4×3 — breathing room, larger type). Data is a Room snapshot;
 * refresh on app writes, drain completion, trampoline actions, and the 15-min periodic job
 * (see [WidgetRefresher]).
 *
 * Taps: nap row → starts the sleep timer via broadcast; feed / diaper / pump rows → deep-link
 * into the app with that panel pre-opened; running-timer row (T5) → deep-links into the app's
 * stop-guard dialog for that timer — the label says "tap to stop" and the tap now does it.
 */
class CareDashboardWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Responsive(setOf(SIZE_COMPACT, SIZE_RELAXED))

    @Suppress("UNCHECKED_CAST")
    override val stateDefinition: GlanceStateDefinition<Preferences> =
        PreferencesGlanceStateDefinition as GlanceStateDefinition<Preferences>

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = (context.applicationContext as App).container
        val prefs = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
        val childId = container.quickActionActions.activeChildId()
        val state = if (childId == null) {
            WidgetStateMapper.CareDashboardState(rows = emptyList(), running = null)
        } else {
            // W8: resolve the child name for the header from the Room snapshot.
            val childName = container.childRepository.children
                .first()
                .firstOrNull { it.id == childId }
                ?.firstName
            WidgetStateMapper.careDashboard(
                events = container.eventRepository
                    .observeTimeline(childId)
                    .first(),
                timers = container.timerRepository.snapshotRunning(),
                childId = childId,
                nowMillis = container.serverClock.nowMillis(),
                visible = visibleRows(prefs),
                childName = childName,
            )
        }

        provideContent {
            val context = LocalContext.current
            GlanceTheme {
                CareDashboardContent(context, state)
            }
        }
    }

    companion object {
        val KEY_CHILD_ID = longPreferencesKey("child_id")
        private fun rowKey(type: EventType) = booleanPreferencesKey("row_${type.name}")

        /** Default: all four canonical rows (spec §7.2). */
        fun visibleRows(prefs: Preferences): Set<EventType> =
            listOf(EventType.FEEDING, EventType.CHANGE, EventType.SLEEP, EventType.PUMPING)
                .filter { prefs[rowKey(it)] ?: true }
                .toSet()

        suspend fun saveConfig(context: Context, id: GlanceId, childId: Long, visible: Set<EventType>) {
            updateAppWidgetState(context, id) { prefs ->
                prefs[KEY_CHILD_ID] = childId
                EventType.entries.forEach { prefs[rowKey(it)] = it in visible }
            }
            CareDashboardWidget().update(context, id)
        }
    }
}

/** Manifest receiver (appwidget-provider meta-data → care_dashboard_widget_info). */
class CareDashboardWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CareDashboardWidget()
}

@Composable
private fun CareDashboardContent(context: Context, state: WidgetStateMapper.CareDashboardState) {
    val colors = GlanceTheme.colors
    // W11: the relaxed bucket gets breathing room and larger type; compact keeps 4×2 metrics.
    val relaxed = LocalSize.current.height >= SIZE_RELAXED.height
    // W5: paint the widget surface (launcher's Material-3 widget token, day/night) so rows
    // never float on the wallpaper.
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(colors.widgetBackground)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        state.childName?.let { name ->
            Text(
                name,
                style = TextStyle(
                    color = colors.onSurfaceVariant,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 1,
                modifier = GlanceModifier.padding(bottom = 2.dp),
            )
        }
        state.rows.forEach { row ->
            // P2-5: the nap row STARTS the sleep timer via broadcast — the launcher stays
            // foreground (matching the QuickActions sleep cell). When a sleep timer already
            // runs, the broadcast opens the app (timers strip) so the guard is reachable.
            val action = if (row.type == EventType.SLEEP) {
                GlanceModifier.clickable(actionRunCallback<WidgetSleepStartAction>())
            } else {
                WidgetActions.openPanelModifier(context, row.type)
            }
            CareRow(
                icon = row.type.iconRes(),
                label = row.label,
                detail = listOfNotNull(row.sinceText, row.detailText).joinToString(" · "),
                accent = false,
                action = action,
                relaxed = relaxed,
            )
        }
        state.running?.let { running ->
            CareRow(
                icon = running.kind.iconRes(),
                label = "Running: ${WidgetStateMapper.slotLabel(running.kind)}",
                detail = "${running.elapsedText} — tap to stop",
                accent = true,
                // T5: the row says "tap to stop" — the tap now deep-links straight into the
                // app's stop-guard dialog for this timer (widgets cannot host the dialog).
                action = WidgetActions.openStopTimerModifier(context, running.kind),
                relaxed = relaxed,
            )
        }
    }
}

@Composable
private fun CareRow(
    icon: Int,
    label: String,
    detail: String,
    accent: Boolean,
    action: GlanceModifier,
    relaxed: Boolean,
) {
    val colors = GlanceTheme.colors
    // W6: the accent is a rounded primaryContainer chip painted BEFORE the padding (so it is
    // not a full-bleed square bar) and the content insets horizontally. 24dp glyph + 8dp
    // vertical padding keeps the running row at the ≥40dp tap target.
    val chip = if (accent) {
        GlanceModifier
            .background(colors.primaryContainer)
            .cornerRadius(12.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    } else {
        GlanceModifier.padding(vertical = if (relaxed) 6.dp else 2.dp)
    }
    val content = if (accent) colors.onPrimaryContainer else colors.onSurface
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .then(chip)
            .then(action),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            provider = ImageProvider(icon),
            contentDescription = label,
            modifier = GlanceModifier.size(24.dp),
            colorFilter = ColorFilter.tint(content),
        )
        Text(
            label,
            style = TextStyle(
                color = content,
                fontSize = if (relaxed) 14.sp else 13.sp,
                fontWeight = FontWeight.Medium,
            ),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight().padding(start = 6.dp),
        )
        Text(
            detail,
            style = TextStyle(color = content, fontSize = if (relaxed) 13.sp else 12.sp),
            maxLines = 1,
        )
    }
}
