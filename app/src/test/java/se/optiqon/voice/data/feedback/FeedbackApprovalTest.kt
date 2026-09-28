package se.optiqon.voice.data.feedback

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.optiqon.voice.di.FeedbackStorageModule
import se.optiqon.voice.domain.access.AccountStatus
import se.optiqon.voice.domain.feedback.CaseComposer
import se.optiqon.voice.domain.feedback.ComposeOutcome
import se.optiqon.voice.domain.feedback.FeedbackBuildInfo
import se.optiqon.voice.testing.AccessFixture
import se.optiqon.voice.testing.MemoryOutboxDao

/**
 * The approval check the app wires, over the real access repository: only an account whose
 * verdict is APPROVED may put feedback on the queue.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class FeedbackApprovalTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `approved may queue, pending, rejected and revoked may not`() = runTest {
        val f = AccessFixture(context, backgroundScope)
        val check = FeedbackStorageModule.provideApprovalCheck(f.repository)
        f.signIn("uid-a")

        f.recordServerVerdict(AccountStatus.APPROVED)
        assertTrue(check.isApproved())

        for (status in listOf(AccountStatus.PENDING, AccountStatus.REJECTED, AccountStatus.REVOKED)) {
            f.recordServerVerdict(status)
            assertFalse("$status must not queue", check.isApproved())
        }
    }

    @Test
    fun `a revoked account's composer leaves the queue empty`() = runTest {
        val f = AccessFixture(context, backgroundScope)
        f.signIn("uid-a")
        f.recordServerVerdict(AccountStatus.REVOKED)
        val outbox = MemoryOutboxDao()
        val composer = CaseComposer(
            outbox, f.auth, FeedbackStorageModule.provideApprovalCheck(f.repository),
            {}, FeedbackBuildInfo("1", 34, "x")
        )

        assertEquals(ComposeOutcome.NotApproved, composer.createCase("hej", emptyList()))
        assertTrue(outbox.rows.isEmpty())
    }

    @Test
    fun `nobody signed in is not approved`() = runTest {
        val f = AccessFixture(context, backgroundScope)
        assertFalse(FeedbackStorageModule.provideApprovalCheck(f.repository).isApproved())
    }
}
