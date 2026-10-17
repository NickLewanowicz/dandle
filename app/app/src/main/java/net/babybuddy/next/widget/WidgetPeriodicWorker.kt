package net.babybuddy.next.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * 15-minute periodic widget refresh (spec §7.2 — the platform minimum), so care rows stay
 * honest even when the app is never opened. App writes, drains, and trampoline actions
 * refresh sooner via [WidgetRefresher].
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
