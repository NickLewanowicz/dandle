package net.babybuddy.next.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * The widgets' live tick (spec §7.2 freshness): while ANY timer runs, a self-chaining ~60s
 * one-time job re-renders both widgets so the elapsed counters ("12m elapsed") stay within a
 * minute of the truth. The chain schedules itself again from [WidgetRefresher.refreshAll] —
 * which disarms it the moment no timer is running — so the 60s cadence exists ONLY while a
 * timer runs.
 *
 * Battery model (reviewer note): at most ONE pending chain exists at any time (unique work,
 * REPLACE). Each run is a Room snapshot read plus two RemoteViews applies (tens of ms) — no
 * network on this path. NOT expedited on purpose: WorkManager forbids delayed expedited
 * requests ("Expedited jobs cannot be delayed" — verified on work-runtime 2.12), and an
 * undelayed expedited tick would busy-chain while holding the quota. A regular one-time job
 * may itself be deferred by the OS under deep doze; the pre-existing 15-minute
 * [WidgetPeriodicWorker] bounds that staleness, and the app's own write paths re-render
 * immediately regardless. When no timer runs the chain is cancelled: no scheduled work, no
 * cost.
 */
class WidgetTickerWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // refreshAll re-arms the chain while a timer runs and cancels it when none does.
        WidgetRefresher.refreshAll(applicationContext)
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "widget-live-tick"

        /** Tick cadence: elapsed counters may trail by up to this long (spec §7.2: ≤1 minute). */
        private const val TICK_SECONDS = 60L

        /**
         * (Re-)arm the live tick. Idempotent; REPLACE coalesces bursts of refreshes into one
         * pending tick. Called from [WidgetRefresher.refreshAll] on every state change.
         */
        fun arm(context: Context) {
            val request = OneTimeWorkRequestBuilder<WidgetTickerWorker>()
                .setInitialDelay(TICK_SECONDS, TimeUnit.SECONDS)
                .build()
            try {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    UNIQUE_NAME,
                    ExistingWorkPolicy.REPLACE,
                    request,
                )
            } catch (e: IllegalStateException) {
                // WorkManager not initialized (Robolectric app boots): nothing to schedule.
                logArmFailure(e)
            } catch (e: IllegalArgumentException) {
                // Request rejected — this is a code bug (e.g. a future expedited+delay combo);
                // the 15-min safety net still bounds staleness, but make it loud in debug logs.
                logArmFailure(e)
            }
        }

        /** Stop the live tick (no timer running — the widgets are static until the next write). */
        fun disarm(context: Context) {
            try {
                WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
            } catch (_: IllegalStateException) {
                // WorkManager not initialized (Robolectric app boots): nothing to cancel.
            }
        }

        private fun logArmFailure(e: Exception) {
            net.babybuddy.next.log.BbLog.d(
                net.babybuddy.next.log.BbLog.TAG_SYNC,
                "widget live tick not scheduled: ${e.javaClass.simpleName}: ${e.message}",
            )
        }
    }
}
