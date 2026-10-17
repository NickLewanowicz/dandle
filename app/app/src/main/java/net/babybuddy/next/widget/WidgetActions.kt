package net.babybuddy.next.widget

import android.content.Context
import android.content.Intent
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
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
 *   pre-opened — or the plain app (timers strip). Pending work around background-activity-start
 *   restrictions never applies to PendingIntents, so deep links are reliable from the launcher.
 * - `actionRunCallback` (broadcast): UI-free work only — starting a sleep timer — followed by a
 *   widget refresh. Widgets CANNOT host the >10s stop-guard dialog, so a tap on a RUNNING
 *   timer slot/row renders as a plain app open (timers strip) instead; that decision is made at
 *   RENDER time from the running state (see docs/flows/new-app/widgets.md for QA).
 */
object WidgetActions {

    /** MainActivity extra: EventType.name of the quick-action panel to pre-open. */
    const val EXTRA_OPEN_PANEL = "net.babybuddy.next.extra.OPEN_PANEL"

    /** Trampoline extra: which widget action to run (see WidgetTrampolineActivity.Action). */
    const val EXTRA_ACTION = "net.babybuddy.next.extra.WIDGET_ACTION"

    const val ACTION_SLEEP_TOGGLE = "SLEEP_TOGGLE"

    fun openPanelParameters(type: EventType): ActionParameters =
        actionParametersOf(PARAM_TYPE to type.name)

    val PARAM_TYPE = ActionParameters.Key<String>("type")

    /** Deep link: open the app with this quick-action panel pre-opened. */
    fun openPanelModifier(type: EventType): GlanceModifier =
        GlanceModifier.clickable(
            actionStartActivity<MainActivity>(openPanelParameters(type))
        )

    /** Open the plain app (timers strip shows the running timer). */
    fun openAppModifier(): GlanceModifier =
        GlanceModifier.clickable(actionStartActivity<MainActivity>())

    fun openPanelIntent(context: Context, type: EventType): Intent =
        Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_OPEN_PANEL, type.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}

/**
 * Broadcast callback backing idle sleep slots: starts the sleep timer for the active child and
 * refreshes the widgets. Never navigates; the running branch is rendered as an app open.
 */
class WidgetSleepStartAction : ActionCallback {

    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        runStart(context)
    }

    suspend fun runStart(context: Context) {
        val container = (context.applicationContext as App).container
        val childId = container.quickActionActions.activeChildId() ?: return
        if (container.quickActionActions.runningTimer(EventType.SLEEP, childId) == null) {
            container.quickActionActions.startTimer(EventType.SLEEP, childId)
        }
        WidgetRefresher.refreshAll(context)
    }
}
