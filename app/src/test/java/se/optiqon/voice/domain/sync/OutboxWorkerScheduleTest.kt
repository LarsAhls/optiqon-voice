package se.optiqon.voice.domain.sync

import android.content.Context
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A removal queued while a run is already under way must get a run of its own afterwards.
 *
 * The run in flight may have taken its last look at the queue just before the removal landed.
 * If the schedule that follows the removal were dropped because a run already exists, the
 * tombstone would sit on the device until some unrelated piece of work started the queue again.
 *
 * The worker here is a stand-in that stays running until the test lets it finish: what is
 * under test is how [OutboxWorker.enqueue] asks WorkManager for a run, not the run itself.
 */
@RunWith(AndroidJUnit4::class)
class OutboxWorkerScheduleTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val running = mutableListOf<CallbackToFutureAdapter.Completer<ListenableWorker.Result>>()

    private inner class HeldWorker(context: Context, params: WorkerParameters) : ListenableWorker(context, params) {
        override fun startWork(): ListenableFuture<Result> =
            CallbackToFutureAdapter.getFuture { completer -> running += completer; "held" }
    }

    @Before
    fun setUp() {
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                HeldWorker(appContext, workerParameters)
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(factory).build()
        )
    }

    private fun runs(): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork("outbox").get()

    @Test
    fun `a schedule during a running pass is kept and runs after it`() {
        val driver = WorkManagerTestInitHelper.getTestDriver(context)!!
        OutboxWorker.enqueue(context)
        val first = runs().single()
        driver.setAllConstraintsMet(first.id)
        assertEquals(WorkInfo.State.RUNNING, runs().single().state)

        OutboxWorker.enqueue(context) // the removal's schedule, while the pass is inside send()

        val chain = runs()
        assertEquals("the second request is kept, not collapsed into the running one", 2, chain.size)
        val second = chain.single { it.id != first.id }
        assertEquals(WorkInfo.State.BLOCKED, second.state)

        running.single().set(ListenableWorker.Result.success())
        driver.setAllConstraintsMet(second.id)

        assertEquals(WorkInfo.State.SUCCEEDED, runs().single { it.id == first.id }.state)
        assertEquals(WorkInfo.State.RUNNING, runs().single { it.id == second.id }.state)
        assertEquals(2, running.size)
    }

    @Test
    fun `a failed chain does not swallow the next schedule`() {
        val driver = WorkManagerTestInitHelper.getTestDriver(context)!!
        OutboxWorker.enqueue(context)
        val first = runs().single()
        driver.setAllConstraintsMet(first.id)
        running.single().set(ListenableWorker.Result.failure())

        OutboxWorker.enqueue(context)

        val next = runs().single { it.id != first.id }
        driver.setAllConstraintsMet(next.id)
        assertEquals(WorkInfo.State.RUNNING, runs().single { it.id == next.id }.state)
    }
}
