package se.optiqon.voice.domain.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Sends whatever the signed-in account has queued, and leaves everything else alone.
 *
 * A run that finds no eligible rows is a success, not a failure: rows belonging to a
 * signed-out account are dormant, and dormant is the correct resting state. The worker never
 * deletes a row. The one file it removes is a screenshot copy the app itself prepared, and only
 * once the bucket is confirmed to hold it. The rules of a pass live in [OutboxFlush].
 */
class OutboxWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun flush(): OutboxFlush
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java)
        return when (deps.flush().run()) {
            OutboxFlush.Result.RETRY -> Result.retry()
            OutboxFlush.Result.DONE -> Result.success()
        }
    }

    companion object {
        private const val WORK_NAME = "outbox"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<OutboxWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
