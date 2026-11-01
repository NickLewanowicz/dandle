package app.dandle.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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
import app.dandle.App
import app.dandle.domain.model.EventType
import app.dandle.ui.dashboard.iconRes



// W11: both buckets share the 4-cell width; the height selects the layout density.
// LocalSize inside the composition is the selected option.
private val SIZE_COMPACT = DpSize(250.dp, 110.dp) // 4×2
private val SIZE_RELAXED = DpSize(250.dp, 180.dp) // 4×3 breathing room

/**
 * CareDashboard widget (spec §7.2, UX spec §6): 4×2 (resizable 4×3) with a child-name header
 * (UX-review W8; T4 — bold, full-contrast, the widget's WHO) and rows Last feed / Last diaper /
 * Last nap / Last pump (minute-granular, staleness documented), one accented running row per
 * RUNNING timer — sleep + pumping + tummy time can run simultaneously and each gets its own
 * "… since HH:MM · Xm" row, longest-running first — and QUIET cross-child lines when timers run
 * for other children (T7: "Zoe's nap · 32m" — opens the app). Two size buckets (W11): compact
 * (4×2) and relaxed (4×3). Data is a Room-only snapshot (no network at render time); refresh on
 * app writes, drain completion, trampoline actions, the ~60s live tick while a timer runs, and
 * the 15-min periodic job (see [WidgetRefresher]).
 *
 * Taps (persona round 2 — timers are never started from the dashboard widget): all info rows
 * open the app; a running-timer row (T5) deep-links into the app's stop-guard dialog for that
 * timer — the label says "tap to stop" and the tap does it.
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
        // Room-only reads at render time (spec §7.2: the widget never blocks on the network;
        // sync happens in SyncWorker and refreshes the widget when it lands).
        val state = if (childId == null) {
            WidgetStateMapper.CareDashboardState()
        } else {
            val children = container.childRepository.children.first()
            // W8: resolve the child name for the header from the Room snapshot.
            val childName = children.firstOrNull { it.id == childId }?.firstName
            // T7: timers running for OTHER children surface as quiet lines (one per timer).
            val timers = container.timerRepository.snapshotRunning()
            val otherChildNames = timers.filter { it.childId != childId }
                .map { it.childId }
                .distinct()
                .associateWith { otherId -> children.firstOrNull { it.id == otherId }?.firstName }
            val crossChild = WidgetStateMapper.crossChildTimers(
                timers = timers,
                childId = childId,
                otherChildFirstNames = otherChildNames,
                nowMillis = container.serverClock.nowMillis(),
            )
            WidgetStateMapper.careDashboard(
                events = container.eventRepository
                    .observeTimeline(childId)
                    .first(),
                timers = timers,
                childId = childId,
                nowMillis = container.serverClock.nowMillis(),
                visible = visibleRows(prefs),
                childName = childName,
            ).copy(crossChild = crossChild)
        }

        provideContent {
            val context = LocalContext.current
            GlanceTheme {
                CareDashboardContent(context, state)
            }
        }
    }

    companion object {
        val KEY_CHILD_ID = stringPreferencesKey("child_id")
        private fun rowKey(type: EventType) = booleanPreferencesKey("row_${type.name}")

        /** Default: all four canonical rows (spec §7.2). */
        fun visibleRows(prefs: Preferences): Set<EventType> =
            listOf(EventType.FEEDING, EventType.CHANGE, EventType.SLEEP, EventType.PUMPING)
                .filter { prefs[rowKey(it)] ?: true }
                .toSet()

        /**
         * Type-safe legacy read (P1 QA 2026-10-31): the pre-refactor build saved "child_id"
         * with [androidx.datastore.preferences.core.longPreferencesKey], and Preferences keys
         * compare equal by NAME alone — a direct `prefs[KEY_CHILD_ID]` would
         * ClassCastException the stored Long into String at the call site. Read the raw map
         * so a legacy Long degrades to null; the next [saveConfig] write-heals to a String.
         */
        fun configuredChildId(prefs: Preferences): String? =
            prefs.asMap()[KEY_CHILD_ID] as? String

        suspend fun saveConfig(context: Context, id: GlanceId, childId: String, visible: Set<EventType>) {
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
        // T4 (Sam): the child name is the widget's WHO — it must be impossible to miss
        // (was 11sp muted variant text; now the header, bold, full contrast).
        state.childName?.let { name ->
            Text(
                name,
                style = TextStyle(
                    color = colors.onSurface,
                    fontSize = if (relaxed) 15.sp else 14.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
                modifier = GlanceModifier.padding(bottom = 4.dp),
            )
        }
        // Priya (persona round 2): "is she asleep RIGHT NOW" is the first check — the running
        // rows move to the TOP and read as STATE ("Asleep since 13:05 · 1h 10m"), not as a stop
        // button. Sleep + pumping + tummy time can run simultaneously; every running timer gets
        // its own accented row, longest-running first (spec §7.2). Tap still deep-links into the
        // stop guard (T5 semantics).
        state.runningSince.forEach { runningSince ->
            CareRow(
                icon = runningSince.kind.iconRes(),
                label = runningStateLabel(runningSince.kind, runningSince.sinceText),
                detail = runningSince.elapsedText,
                accent = true,
                action = WidgetActions.openStopTimerModifier(context, runningSince.kind),
                relaxed = relaxed,
            )
        }
        state.rows.forEach { row ->
            // Persona round 2 (Priya P2 fix): the info rows LOOK like information — a
            // mis-tap on "Last nap" silently STARTED a nap timer for an awake baby. Rows
            // now just open the app; starting/stopping lives on the tiles (lit state).
            CareRow(
                icon = if (row.type == EventType.FEEDING) {
                    app.dandle.ui.dashboard.feedingIconRes(row.feedingMethod)
                } else {
                    row.type.iconRes()
                },
                label = row.label,
                detail = listOfNotNull(row.timeText, row.detailText).joinToString(" · "),
                accent = false,
                action = WidgetActions.openAppModifier(),
                relaxed = relaxed,
            )
        }
        // T7 (Priya): timers running for OTHER children surface here as quiet lines —
        // "Zoe's nap · 32m" — opening the app (their stop guards live on that child's screen).
        state.crossChild.forEach { cross ->
            CareRow(
                icon = cross.kind.iconRes(),
                label = "${cross.childFirstName}'s ${WidgetStateMapper.friendlyNoun(cross.kind)}",
                detail = cross.elapsedText,
                accent = false,
                action = WidgetActions.openAppModifier(),
                relaxed = relaxed,
            )
        }
    }
}

/** The running row's STATE phrasing — "Asleep since 13:05" (spec §7.2 wording shape). */
private fun runningStateLabel(kind: EventType, sinceText: String): String {
    val verb = when (kind) {
        EventType.SLEEP -> "Asleep since"
        EventType.FEEDING -> "Feeding since"
        EventType.PUMPING -> "Pumping since"
        EventType.TUMMY_TIME -> "Tummy time since"
        else -> "Tracking since"
    }
    return "$verb $sinceText"
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
