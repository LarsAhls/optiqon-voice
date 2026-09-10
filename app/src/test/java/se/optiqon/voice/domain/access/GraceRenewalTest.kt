package se.optiqon.voice.domain.access

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.optiqon.voice.testing.AccessFixture

/**
 * The most serious of the six findings: a device that never reaches a server must not be able
 * to keep renewing its own approval.
 *
 * Before `Source.SERVER`, an offline `get()` was answered out of Firestore's local cache — no
 * error, the previous status, and then recorded with a *fresh* pair of timestamps. Grace could
 * therefore be extended indefinitely without a server ever being involved, which makes "the
 * server said so" unprovable for every stored verdict in the app. What these tests pin is the
 * consequence rather than the mechanism: nothing but a confirmed answer may move the clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class GraceRenewalTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val graceMs = 72L * 60L * 60L * 1000L

    private fun TestScope.approvedFixture(): AccessFixture {
        val f = AccessFixture(context, backgroundScope, graceMs)
        f.signIn("uid-a")
        return f
    }

    @Test
    fun `repeated offline refreshes never advance the verification timestamps`() = runTest {
        val f = approvedFixture()
        f.server.answer = { RefreshOutcome.Confirmed(AccountStatus.APPROVED) }
        f.refresher.refresh(RefreshTrigger.MANUAL)
        val original = f.store.snapshot("uid-a").first()!!

        f.server.answer = { RefreshOutcome.NoNetwork }
        repeat(5) {
            f.clock.advance(6L * 60L * 60L * 1000L)
            assertEquals(RefreshOutcome.NoNetwork, f.refresher.refresh(RefreshTrigger.MANUAL))
        }

        val after = f.store.snapshot("uid-a").first()!!
        assertEquals(original.verifiedAtWallMs, after.verifiedAtWallMs)
        assertEquals(original.verifiedAtElapsedMs, after.verifiedAtElapsedMs)
    }

    @Test
    fun `a backend failure does not advance them either`() = runTest {
        val f = approvedFixture()
        f.server.answer = { RefreshOutcome.Confirmed(AccountStatus.APPROVED) }
        f.refresher.refresh(RefreshTrigger.MANUAL)
        val original = f.store.snapshot("uid-a").first()!!

        f.server.answer = { RefreshOutcome.Failed(IllegalStateException("backend")) }
        f.clock.advance(60L * 60L * 1000L)
        f.refresher.refresh(RefreshTrigger.MANUAL)

        assertEquals(original, f.store.snapshot("uid-a").first())
    }

    @Test
    fun `an account that never reaches the server runs out of grace`() = runTest {
        val f = approvedFixture()
        f.server.answer = { RefreshOutcome.Confirmed(AccountStatus.APPROVED) }
        f.refresher.refresh(RefreshTrigger.MANUAL)
        assertEquals(AccessDecision.Allowed, f.repository.currentDecision())

        f.server.answer = { RefreshOutcome.NoNetwork }
        repeat(20) {
            f.clock.advance(4L * 60L * 60L * 1000L)
            f.refresher.refresh(RefreshTrigger.MANUAL)
        }

        assertEquals(
            AccessDecision.Blocked(BlockReason.GRACE_EXPIRED),
            f.repository.currentDecision()
        )
    }

    @Test
    fun `a wall clock pushed backwards does not buy extra grace`() = runTest {
        val f = approvedFixture()
        f.server.answer = { RefreshOutcome.Confirmed(AccountStatus.APPROVED) }
        f.refresher.refresh(RefreshTrigger.MANUAL)

        // Elapsed realtime cannot be set by the user; the wall clock can. Taking the larger of
        // the two ages is what stops "set the date back" from being a way to stay approved.
        f.clock.elapsed += graceMs + 1
        f.clock.wall -= graceMs

        assertEquals(
            AccessDecision.Blocked(BlockReason.GRACE_EXPIRED),
            f.repository.currentDecision()
        )
    }

    @Test
    fun `a confirmed answer does renew the window`() = runTest {
        val f = approvedFixture()
        f.server.answer = { RefreshOutcome.Confirmed(AccountStatus.APPROVED) }
        f.refresher.refresh(RefreshTrigger.MANUAL)
        val original = f.store.snapshot("uid-a").first()!!

        f.clock.advance(60L * 60L * 1000L)
        f.refresher.refresh(RefreshTrigger.MANUAL)

        val after = f.store.snapshot("uid-a").first()!!
        assertTrue(after.verifiedAtWallMs > original.verifiedAtWallMs)
        assertTrue(after.verifiedAtElapsedMs > original.verifiedAtElapsedMs)
    }
}
