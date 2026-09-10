package se.optiqon.voice.domain.access

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.optiqon.voice.testing.AccessFixture

/**
 * Grace runs out by the passage of time and by nothing else.
 *
 * That is a problem for a flow built out of stored state: nothing is written when a window
 * closes, so a screen driven by snapshots alone keeps saying "approved" until some unrelated
 * event happens to poke it. The fix is a deadline rather than a tick — the instant of expiry is
 * computable, so the flow waits exactly that long and emits again. A 60-second poll would cost
 * 4,320 wakeups per 72-hour grace to learn one fact that was knowable from the start.
 *
 * Three clocks meet in these tests and they are genuinely different things: the scheduler's
 * virtual time, which the `delay` waits on; the app's own movable clock, which the verdict is
 * computed from; and real time, on which DataStore does its file I/O. Only the first two are
 * advanced deliberately — see [awaitDecision] for why the third has to be waited for rather
 * than skipped.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AccessRepositoryTimeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val graceMs = 72L * 60L * 60L * 1000L

    /** Signed in, approved by the server as of now, and observed to have reached that state. */
    private suspend fun TestScope.approved(): AccessFixture {
        val f = AccessFixture(context, backgroundScope, graceMs)
        f.signIn("uid-a")
        f.recordServerVerdict(AccountStatus.APPROVED)
        awaitDecision(f, AccessDecision.Allowed)
        return f
    }

    /**
     * Waits for the published verdict to become [expected], driving the scheduler as it goes.
     *
     * `advanceUntilIdle` is deliberately not used. It would run the pending expiry `delay` as
     * well — leaping 72 virtual hours and consuming the very deadline these tests are about —
     * and it would still not wait for DataStore, whose file I/O happens on a real dispatcher
     * that virtual time cannot advance. So: run what is due now, then yield real time briefly,
     * and repeat.
     */
    private suspend fun TestScope.awaitDecision(f: AccessFixture, expected: AccessDecision) {
        repeat(ATTEMPTS) {
            runCurrent()
            if (f.repository.decision.value == expected) return
            withContext(Dispatchers.IO) { delay(REAL_STEP_MS) }
        }
        fail("expected $expected, published value stayed ${f.repository.decision.value}")
    }

    /** Moves time as the device experiences it: the app's clock and the scheduler together. */
    private fun TestScope.passTime(f: AccessFixture, ms: Long) {
        f.clock.advance(ms)
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun `the decision turns itself over when grace expires, with nothing else happening`() = runTest {
        val f = approved()

        // No refresh, no sign-in, no write. Only time.
        passTime(f, graceMs + 1L)

        awaitDecision(f, AccessDecision.Blocked(BlockReason.GRACE_EXPIRED))
    }

    @Test
    fun `nothing wakes up before the deadline`() = runTest {
        val f = approved()

        passTime(f, graceMs - 1_000L)

        assertEquals("one second early is still approved", AccessDecision.Allowed, f.repository.decision.value)
    }

    @Test
    fun `a fresh server answer moves the deadline instead of leaving the old one`() = runTest {
        val f = approved()
        passTime(f, graceMs - 1_000L)

        f.recordServerVerdict(AccountStatus.APPROVED)
        awaitDecision(f, AccessDecision.Allowed)
        // The old deadline falls here. It must have been replaced rather than merely
        // supplemented, or the screen blocks a user whose approval was just re-confirmed.
        passTime(f, 2_000L)

        assertEquals(AccessDecision.Allowed, f.repository.decision.value)
    }

    @Test
    fun `the live decision is authoritative even before the deadline has fired`() = runTest {
        val f = approved()

        // The app's clock moves but the scheduler does not, which is what Doze looks like from
        // in here: the pending `delay` has not run. Anything about to have an effect asks
        // currentDecision, and that reads the clocks rather than the last emission.
        f.clock.advance(graceMs + 1L)

        assertEquals(
            AccessDecision.Blocked(BlockReason.GRACE_EXPIRED),
            f.repository.currentDecision()
        )
        assertEquals("and the stale emission is exactly what it was", AccessDecision.Allowed, f.repository.decision.value)
    }

    @Test
    fun `a second account never inherits the first one's verdict`() = runTest {
        val f = approved()

        f.signOut()
        f.signIn("uid-b")

        // uid-b has no snapshot of its own. "Approved" would be inherited from somebody who
        // merely used the same device, so the conservative answer is the only safe one.
        awaitDecision(f, AccessDecision.Blocked(BlockReason.NOT_REGISTERED))
        assertEquals(
            AccessDecision.Blocked(BlockReason.NOT_REGISTERED),
            f.repository.currentDecision()
        )
    }

    @Test
    fun `signing out blocks immediately rather than waiting for a deadline`() = runTest {
        val f = approved()

        f.signOut()

        awaitDecision(f, AccessDecision.Blocked(BlockReason.NOT_REGISTERED))
    }

    @Test
    fun `the owner signing back in finds the verdict still there`() = runTest {
        val f = approved()
        f.signOut()
        awaitDecision(f, AccessDecision.Blocked(BlockReason.NOT_REGISTERED))

        f.signIn("uid-a")

        // Recovery at the level of the verdict: a sign-out is not a revocation, and making a
        // user wait for a round trip to use grace they hold would defeat its purpose.
        awaitDecision(f, AccessDecision.Allowed)
    }

    @Test
    fun `a wall clock pushed backwards does not extend the window`() = runTest {
        val f = approved()

        // The elapsed clock cannot be set by hand, which is the whole reason both are stored:
        // the larger of the two ages decides.
        f.clock.wall -= 24L * 60L * 60L * 1000L
        f.clock.elapsed += graceMs + 1L

        assertEquals(
            AccessDecision.Blocked(BlockReason.GRACE_EXPIRED),
            f.repository.currentDecision()
        )
    }

    private companion object {
        const val ATTEMPTS = 400
        const val REAL_STEP_MS = 5L
    }
}
