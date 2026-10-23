package app.dandle.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.CoroutineWorker
import app.dandle.App
import app.dandle.data.sync.OutboxDrainer.Outcome
import java.util.concurrent.TimeUnit

/**
 * WorkManager shell around [OutboxDrainer.drain] (spec §6.1): runs on app open, after writes,
 * and on connectivity regain; exponential backoff; NETWORK-connected constraint. The drain
 * engine itself holds all the guarantees (order, isolation, auth pause).
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as App).container
        val outcome = container.drainer.drain()
        // Drain completion is a widget refresh trigger (spec §7.2): new server events and
        // cleared PENDING badges should be visible on the home screen promptly.
        if (outcome == Outcome.Completed) {
            app.dandle.widget.WidgetRefresher.refreshAll(applicationContext)
        }
        return when (outcome) {
            Outcome.Completed, Outcome.PausedAuth -> Result.success()
            Outcome.NetworkDown -> Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_NAME = "outbox-drain"

        /** Fire-and-forget drain request; concurrent requests collapse (KEEP). */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .build()
            runCatching {
                WorkManager.getInstance(context)
                    .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
            }.onFailure {
                // WorkManager uninitialized (Robolectric app boots): the drain still runs via
                // the write-path one-shot; nothing to schedule in tests.
                android.util.Log.w("BB/SYNC", "WorkManager unavailable, drain not scheduled")
            }
        }
    }
}
