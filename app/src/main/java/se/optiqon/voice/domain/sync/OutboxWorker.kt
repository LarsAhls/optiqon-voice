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
import se.optiqon.voice.data.db.dao.OutboxDao
import se.optiqon.voice.data.db.entity.OutboxState
import se.optiqon.voice.domain.access.AuthGateway

/**
 * Sends whatever the signed-in account has queued, and leaves everything else alone.
 *
 * A run that finds no eligible rows is a success, not a failure: rows belonging to a
 * signed-out account are dormant, and dormant is the correct resting state. The worker never
 * deletes a row and never touches the file or draft a row refers to.
 */
class OutboxWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun outboxDao(): OutboxDao
        fun authGateway(): AuthGateway
        fun sender(): OutboxSender
    }

    override suspend fun doWork(): Result {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Dependencies::class.java)
        val uid = deps.authGateway().currentUid ?: return Result.success()

        var retry = false
        for (entry in OutboxPolicy.flushable(deps.outboxDao().pendingFor(uid), uid)) {
            // The uid can change mid-run if the user signs out while we work; stop rather than
            // send the rest of the queue under whoever is signed in now.
            if (deps.authGateway().currentUid != uid) return Result.success()

            when (val failure = deps.sender().send(entry)) {
                null -> deps.outboxDao()
                    .updateState(entry.id, OutboxState.SENT, entry.attempts + 1, null)
                else -> {
                    val updated = OutboxPolicy.afterFailure(entry, failure)
                    deps.outboxDao()
                        .updateState(entry.id, updated.state, updated.attempts, updated.lastError)
                    retry = retry || OutboxPolicy.shouldRetry(failure)
                }
            }
        }
        return if (retry) Result.retry() else Result.success()
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
