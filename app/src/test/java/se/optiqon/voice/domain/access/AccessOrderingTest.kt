package se.optiqon.voice.domain.access

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.optiqon.voice.testing.AccessFixture

/**
 * The ordering contract: no answer that was overtaken may take effect.
 *
 * `Source.SERVER` stops a cached read from passing as a server verdict, but it says nothing
 * about *when* an answer comes back. A refresh started while an account was approved can land
 * after that account signed out, after somebody else signed in, or after the server said
 * revoked — and without these rules it would quietly reinstate approval and stamp a fresh pair
 * of timestamps while doing it, which is the same grace renewal by another route.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AccessOrderingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun TestScope.fixture() = AccessFixture(context, backgroundScope)

    @Test
    fun `an answer that lands after sign-out is not written`() = runTest {
        val f = fixture()
        f.signIn("uid-a")
        val epoch = f.activeIdentity.current.value!!

        f.signOut()

        assertFalse(f.repository.record(epoch, f.activeIdentity.nextSeq(), AccountStatus.APPROVED))
        assertNull(f.store.snapshot("uid-a").first())
    }

    @Test
    fun `an answer that lands after an account switch is not written`() = runTest {
        val f = fixture()
        f.signIn("uid-a")
        val epoch = f.activeIdentity.current.value!!

        f.signOut()
        f.signIn("uid-b")

        assertFalse(f.repository.record(epoch, f.activeIdentity.nextSeq(), AccountStatus.APPROVED))
        assertNull(f.store.snapshot("uid-a").first())
        assertNull(f.store.snapshot("uid-b").first())
    }

    @Test
    fun `signing back in as the same person still invalidates the older answer`() = runTest {
        val f = fixture()
        f.signIn("uid-a")
        val epoch = f.activeIdentity.current.value!!

        f.signOut()
        f.signIn("uid-a")

        assertNotEquals(epoch, f.activeIdentity.current.value)
        assertFalse(f.repository.record(epoch, f.activeIdentity.nextSeq(), AccountStatus.APPROVED))
    }

    @Test
    fun `an older answer cannot resurrect approval over a stored revocation`() = runTest {
        val f = fixture()
        f.signIn("uid-a")
        val epoch = f.activeIdentity.current.value!!

        // The approval was asked for first; the revocation was asked for afterwards and came
        // back first, which is exactly the order that makes this dangerous.
        val approvalSeq = f.activeIdentity.nextSeq()
        val revocationSeq = f.activeIdentity.nextSeq()

        assertTrue(f.repository.record(epoch, revocationSeq, AccountStatus.REVOKED))
        assertFalse(f.repository.record(epoch, approvalSeq, AccountStatus.APPROVED))

        assertEquals(AccountStatus.REVOKED, f.store.snapshot("uid-a").first()?.status)
    }

    @Test
    fun `a genuinely newer approval does replace a revocation`() = runTest {
        val f = fixture()
        f.signIn("uid-a")
        val epoch = f.activeIdentity.current.value!!

        assertTrue(f.repository.record(epoch, f.activeIdentity.nextSeq(), AccountStatus.REVOKED))
        assertTrue(f.repository.record(epoch, f.activeIdentity.nextSeq(), AccountStatus.APPROVED))

        assertEquals(AccountStatus.APPROVED, f.store.snapshot("uid-a").first()?.status)
    }

    @Test
    fun `two identities never share one server read`() = runTest(UnconfinedTestDispatcher()) {
        val f = fixture()
        val held = CompletableDeferred<Unit>()
        f.server.answer = {
            held.await()
            RefreshOutcome.Confirmed(AccountStatus.APPROVED)
        }

        f.signIn("uid-a")
        val first = async { f.refresher.refresh(RefreshTrigger.MANUAL) }

        f.signOut()
        f.signIn("uid-b")
        val second = async { f.refresher.refresh(RefreshTrigger.MANUAL) }

        held.complete(Unit)
        first.await()
        second.await()

        assertEquals("B must not be handed the answer to A's question", 2, f.server.reads)
        // A's answer landed after A had gone, so nothing was written for either account: B's
        // own read is the only one entitled to write, and it wrote B's verdict.
        assertNull(f.store.snapshot("uid-a").first())
        assertEquals(AccountStatus.APPROVED, f.store.snapshot("uid-b").first()?.status)
    }
}
