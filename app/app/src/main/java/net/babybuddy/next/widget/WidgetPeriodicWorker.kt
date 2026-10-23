package net.babybuddy.next.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * 15-minute periodic widget refresh (spec §7.2 — the platform minimum): the SAFETY NET behind
 * the fast paths. While a timer runs, the ~60s [WidgetTickerWorker] chain keeps elapsed counters
 * live; this job catches everything the fast paths can miss (process restart with a timer still
 * running, a WorkManager tick lost to quota, server timers adopted/dropped elsewhere). Its
 * doWork goes through [WidgetRefresher.refreshAll], so a run with a live timer also re-arms the
 * fast chain. App writes, drains, and trampoline actions refresh sooner via [WidgetRefresher].
 */
class WidgetPeriodicWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        WidgetRefresher.refreshAll(applicationContext)
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "widget-refresh-15m"

        /** Idempotent; called from App.onCreate. No-op when WorkManager isn't initialized (tests). */
        fun schedule(context: Context) {
            try {
                check(androidx.work.WorkManager.getInstance(context) != null)
            } catch (_: IllegalStateException) {
                return // WorkManager not initialized (Robolectric app boots): nothing to schedule
            }
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<WidgetPeriodicWorker>(15, TimeUnit.MINUTES).build(),
            )
        }
    }
}
