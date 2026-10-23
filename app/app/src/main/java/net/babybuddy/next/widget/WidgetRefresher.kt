package net.babybuddy.next.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import net.babybuddy.next.App

/**
 * Single refresh entry point for both widgets (spec §7.2). Called after EVERY local state change
 * that the widgets render — timer start/stop/save/discard, entry create/edit/delete, child
 * switch, sign-out — after outbox drains (SyncWorker), after timer reconciliation, and from the
 * periodic jobs.
 *
 * Freshness guarantees, in order of latency:
 * - user-visible writes re-render within the same interaction (Room write → refreshAll →
 *   RemoteViews apply; well under a second);
 * - while ANY timer runs, [WidgetTickerWorker] re-renders ~every 60s so elapsed counters tick
 *   (armed here, cancelled when the last timer stops — see the battery note there);
 * - the 15-minute [WidgetPeriodicWorker] remains as a safety net (process restarts, missed
 *   ticks, adopted/dropped server timers) and re-arms the fast chain through this same path.
 *
 * Render is Room-only: [QuickActionsWidget.provideGlance] and [CareDashboardWidget.provideGlance]
 * read Room/DataStore snapshots and never touch the network; sync lands via SyncWorker, which
 * calls back into this object when the drain completes.
 */
object WidgetRefresher {
    suspend fun refreshAll(context: Context) {
        runCatching {
            QuickActionsWidget().updateAll(context)
            CareDashboardWidget().updateAll(context)
        }
        updateLiveTick(context)
    }

    /**
     * Keep the ~60s live tick exactly as alive as the running timers: armed while any timer
     * runs (each refresh during a run resets the fuse, coalescing bursts), cancelled when none
     * does. The Room read is one tiny query; failures leave the 15-min safety net in charge.
     */
    private suspend fun updateLiveTick(context: Context) {
        runCatching {
            val container = (context.applicationContext as? App)?.container ?: return
            val anyRunning = container.timerRepository.snapshotRunning().isNotEmpty()
            if (anyRunning) WidgetTickerWorker.arm(context) else WidgetTickerWorker.disarm(context)
        }
    }
}
