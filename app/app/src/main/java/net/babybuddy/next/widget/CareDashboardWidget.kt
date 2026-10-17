package net.babybuddy.next.widget

import android.content.Context
import androidx.compose.runtime.Composable
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
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
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



/**
 * CareDashboard widget (spec §7.2, UX spec §6): 4×2 (resizable 4×3) with rows Last feed /
 * Last diaper / Last nap / Last pump (minute-granular, staleness documented) plus a
 * running-timer row when one is active. Data is a Room snapshot; refresh on app writes, drain
 * completion, trampoline actions, and the 15-min periodic job (see [WidgetRefresher]).
 *
 * Taps: nap row → [WidgetTrampolineActivity] (starts the sleep timer, spec §7.2);
 * feed / diaper / pump rows → deep-link into the app with that panel pre-opened;
 * running-timer row → opens the plain app (timers strip).
 */
class CareDashboardWidget : GlanceAppWidget() {

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
            WidgetStateMapper.careDashboard(
                events = container.eventRepository
                    .observeTimeline(childId)
                    .first(),
                timers = container.timerRepository.snapshotRunning(),
                childId = childId,
                nowMillis = container.serverClock.nowMillis(),
                visible = visibleRows(prefs),
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
    Column(
        modifier = GlanceModifier.fillMaxSize().padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        state.rows.forEach { row ->
            CareRow(
                icon = row.type.iconRes(),
                label = row.label,
                detail = listOfNotNull(row.sinceText, row.detailText).joinToString(" · "),
                accent = false,
                action = WidgetActions.openPanelModifier(context, row.type),
            )
        }
        state.running?.let { running ->
            CareRow(
                icon = running.kind.iconRes(),
                label = "Running: ${WidgetStateMapper.slotLabel(running.kind)}",
                detail = "${running.elapsedText} — tap to stop",
                accent = true,
                action = WidgetActions.openAppModifier(),
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
) {
    val colors = GlanceTheme.colors
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .then(if (accent) GlanceModifier.background(colors.secondaryContainer) else GlanceModifier)
            .then(action),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            provider = ImageProvider(icon),
            contentDescription = label,
            modifier = GlanceModifier.size(18.dp).padding(end = 2.dp),
            colorFilter = ColorFilter.tint(colors.onSurface),
        )
        Text(
            label,
            style = TextStyle(color = colors.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
        )
        Text(
            detail,
            style = TextStyle(color = colors.onSurface, fontSize = 12.sp),
            maxLines = 1,
        )
    }
}
