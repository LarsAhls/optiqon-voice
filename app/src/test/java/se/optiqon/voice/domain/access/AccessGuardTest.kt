package se.optiqon.voice.domain.access

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.optiqon.voice.testing.AccessFixture

/**
 * What it costs to ask permission, and what happens when the answer is slow.
 *
 * The guard sits in front of every dictation, so its latency is paid on the common path where
 * nothing is wrong. Making each dictation wait for a network round trip to catch a revocation
 * that arrives once in a career would be a constant tax for an occasional benefit — so a fresh
 * verdict is used as it stands, a stale one is re-read under a short cap, and a cap that
 * expires is treated as having learned nothing rather than as a refusal.
 *
 * "Learned nothing" is the delicate part. It must leave a user inside a grace window they
 * legitimately hold free to work, without ever letting a slow answer soften a verdict that has
 * already arrived. Both halves are tested here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AccessGuardTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val graceMs = 72L * 60L * 60L * 1000L

    private fun TestScope.fixture(): AccessFixture =
        AccessFixture(context, backgroundScope, graceMs)

    /** Signed in with a server verdict recorded as of now, which is the healthy steady state. */
    private suspend fun TestScope.approved(): AccessFixture {
        val f = fixture()
        f.signIn("uid-a")
        f.recordServerVerdict(AccountStatus.APPROVED)
        f.server.answer = { RefreshOutcome.Confirmed(AccountStatus.APPROVED) }
        return f
    }

    private fun granted(grant: AccessGrant): AccessLease {
        assertTrue("expected a lease, got $grant", grant is AccessGrant.Granted)
        return (grant as AccessGrant.Granted).lease
    }

    @Test
    fun `a fresh verdict is used as it stands, with no server read`() = runTest {
        val f = approved()

        granted(f.guard.authorize())

        assertEquals("the common path must not pay for a round trip", 0, f.server.reads)
    }

    @Test
    fun `a stale verdict is re-read before it is relied on`() = runTest {
        val f = approved()
        f.clock.advance(16L * 60L * 1000L)

        granted(f.guard.authorize())

        assertEquals(1, f.server.reads)
    }

    @Test
    fun `triggers arriving together produce one read, not four`() = runTest {
        val f = approved()
        f.clock.advance(16L * 60L * 1000L)
        f.server.answer = {
            delay(200L)
            RefreshOutcome.Confirmed(AccountStatus.APPROVED)
        }

        // Connectivity coming back, the app being foregrounded and a dictation starting are
        // exactly the events that arrive in a bunch, and they do so when things are least well.
        val grants = listOf(
            async { f.guard.authorize(RefreshTrigger.CONNECTIVITY) },
            async { f.guard.authorize(RefreshTrigger.FOREGROUND) },
            async { f.guard.authorize(RefreshTrigger.POINT_OF_ACTION) }
        ).map { it.await() }

        grants.forEach { granted(it) }
        assertEquals(1, f.server.reads)
    }

    @Test
    fun `a server that does not answer in time still lets a user inside grace work`() = runTest {
        val f = approved()
        f.clock.advance(16L * 60L * 1000L)
        f.server.answer = {
            delay(30_000L)
            RefreshOutcome.Confirmed(AccountStatus.APPROVED)
        }

        val start = currentTimeMs()
        val grant = f.guard.authorize()

        granted(grant)
        // The cap is what makes the refusal-free path bearable; without it a single unhealthy
        // backend would stall every dictation on the device for as long as it stayed unhealthy.
        assertTrue("waited ${currentTimeMs() - start} ms", currentTimeMs() - start < 30_000L)
    }

    @Test
    fun `a slow answer cannot soften a verdict that has already arrived`() = runTest {
        val f = approved()
        f.recordServerVerdict(AccountStatus.REVOKED)
        f.clock.advance(16L * 60L * 1000L)
        f.server.answer = {
            delay(30_000L)
            RefreshOutcome.Confirmed(AccountStatus.APPROVED)
        }

        val grant = f.guard.authorize()

        assertEquals(AccessGrant.Denied(BlockReason.REVOKED), grant)
    }

    @Test
    fun `an expired grace is refused even though the account was once approved`() = runTest {
        val f = approved()
        f.server.answer = { RefreshOutcome.NoNetwork }
        f.clock.advance(graceMs + 1L)

        assertEquals(AccessGrant.Denied(BlockReason.GRACE_EXPIRED), f.guard.authorize())
    }

    @Test
    fun `nobody signed in is refused without asking anyone`() = runTest {
        val f = fixture()

        assertEquals(AccessGrant.Denied(BlockReason.NOT_REGISTERED), f.guard.authorize())
        assertEquals("there is no account to ask about", 0, f.server.reads)
    }

    @Test
    fun `signing out while the answer is in flight refuses the lease`() = runTest {
        val f = approved()
        f.clock.advance(16L * 60L * 1000L)
        f.server.answer = {
            delay(100L)
            f.signOut()
            RefreshOutcome.Confirmed(AccountStatus.APPROVED)
        }

        // The answer says approved and arrives inside the budget. It is still about somebody
        // who is no longer here, which is a different question from whether they were allowed.
        assertEquals(AccessGrant.Denied(BlockReason.NOT_REGISTERED), f.guard.authorize())
    }

    @Test
    fun `a lease stops being valid the moment the verdict turns`() = runTest {
        val f = approved()
        val lease = granted(f.guard.authorize())
        assertTrue("the control: it is valid to begin with", lease.isValid())

        f.recordServerVerdict(AccountStatus.REVOKED)

        assertTrue("held leases are re-asked, not trusted", !lease.isValid())
    }

    /** The virtual clock `runTest` advances, which is what the cap is measured against. */
    private fun TestScope.currentTimeMs(): Long = testScheduler.currentTime
}
