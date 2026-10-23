package net.babybuddy.next.widget

import android.content.Context
import android.content.Intent
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.updateAll
import androidx.glance.GlanceModifier
import net.babybuddy.next.App
import net.babybuddy.next.MainActivity
import net.babybuddy.next.data.AppContainer
import net.babybuddy.next.domain.model.EventType

/**
 * Shared widget tap plumbing (spec §7.1/§7.2). Two mechanisms, chosen deliberately:
 *
 * - `actionStartActivity` (PendingIntent, created at render time): opens [MainActivity] — with
 *   an optional `EXTRA_OPEN_PANEL` so the dashboard comes up with a quick-action panel
 *   pre-opened, or `EXTRA_STOP_TIMER` so a tap on a RUNNING timer lands straight in the
 *   >10s stop-guard dialog (T5: the row says "tap to stop" — the tap does what it says; the
 *   ≤10s branch discards silently, exactly the in-app stop semantics). Pending work around
 *   background-activity-start restrictions never applies to PendingIntents, so deep links are
 *   reliable from the launcher.
 * - `actionRunCallback` (broadcast): UI-free work only — starting a timer for a type that needs
 *   no choices (Sleep, Pumping, Tummy time — spec §7.2 one-tap starts) — followed by a widget
 *   refresh. Widgets CANNOT host the >10s stop-guard dialog themselves, so the running slot
 *   deep-links into the app's guard instead of stopping in place.
 */
object WidgetActions {

    /** MainActivity extra: EventType.name of the quick-action panel to pre-open. */
    const val EXTRA_OPEN_PANEL = "net.babybuddy.next.extra.OPEN_PANEL"

    /** MainActivity extra: EventType.name of the running timer the tap asks to stop (T5). */
    const val EXTRA_STOP_TIMER = "net.babybuddy.next.extra.STOP_TIMER"

    /** Deep link: open the app with this quick-action panel pre-opened. */
    fun openPanelAction(context: Context, type: EventType): androidx.glance.action.Action {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_OPEN_PANEL, type.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return androidx.glance.appwidget.action.actionStartActivity(
            intent,
            androidx.glance.action.actionParametersOf(),
        )
    }

    fun openPanelModifier(context: Context, type: EventType): GlanceModifier =
        GlanceModifier.clickable(openPanelAction(context, type))

    /** T5: open the app straight into the stop guard for this running timer kind. */
    fun openStopTimerAction(context: Context, type: EventType): androidx.glance.action.Action {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_STOP_TIMER, type.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return androidx.glance.appwidget.action.actionStartActivity(
            intent,
            androidx.glance.action.actionParametersOf(),
        )
    }

    fun openStopTimerModifier(context: Context, type: EventType): GlanceModifier =
        GlanceModifier.clickable(openStopTimerAction(context, type))

    /** Open the plain app (timers strip shows the running timer). */
    fun openAppModifier(): GlanceModifier =
        GlanceModifier.clickable(
            androidx.glance.action.actionStartActivity<MainActivity>()
        )

    fun openPanelIntent(context: Context, type: EventType): Intent =
        Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_OPEN_PANEL, type.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}

/**
 * Broadcast callback backing the widget's one-tap timer starts (spec §7.1/§7.2): starts the
 * timer for the type named in [KEY_TYPE] (Sleep, Pumping, Tummy time — the timer types that
 * need no field choices) for the active child and refreshes the widgets. Never navigates;
 * Feeding and Diaper need choices (type/method, wet/solid) and keep deep-linking into the app.
 * The running branch is never rendered as a broadcast — running slots deep-link to the stop
 * guard — but a state race (double-tap, timer started elsewhere) is still guarded: the start
 * is a no-op when that timer already runs ([net.babybuddy.next.data.repo.TimerRepository.start]
 * returns the existing row).
 */
class WidgetTimerStartAction : ActionCallback {

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val type = parameters[KEY_TYPE]
            ?.let { name -> EventType.entries.firstOrNull { it.name == name } }
            ?: return
        runStart(context, type)
    }

    suspend fun runStart(context: Context, type: EventType) {
        val container = (context.applicationContext as App).container
        val childId = container.quickActionActions.activeChildId() ?: return
        if (type.isTimed && container.quickActionActions.runningTimer(type, childId) == null) {
            container.quickActionActions.startTimer(type, childId)
        }
        WidgetRefresher.refreshAll(context)
    }

    companion object {
        /** ActionParameters key carrying the [EventType].name this tap asks to start. */
        val KEY_TYPE = ActionParameters.Key<String>("timer_type")
    }
}
