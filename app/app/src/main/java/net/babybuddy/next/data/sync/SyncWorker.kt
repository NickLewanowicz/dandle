package net.babybuddy.next.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.CoroutineWorker
import net.babybuddy.next.App
import net.babybuddy.next.data.sync.OutboxDrainer.Outcome
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
        return when (container.drainer.drain()) {
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
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
