package net.babybuddy.next.widget

import android.content.Context
import androidx.glance.appwidget.updateAll

/**
 * Single refresh entry point for both widgets (spec §7.2): called after in-app writes, after
 * outbox drains (SyncWorker), after timer trampoline actions, and from the 15-min periodic
 * WorkManager job scheduled in App.onCreate.
 */
object WidgetRefresher {
    suspend fun refreshAll(context: Context) {
        runCatching {
            QuickActionsWidget().updateAll(context)
            CareDashboardWidget().updateAll(context)
        }
    }
}
