package se.optiqon.voice.domain.access

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.optiqon.voice.data.storage.DeviceDataOwner
import se.optiqon.voice.data.storage.ProcessRestarter
import se.optiqon.voice.data.storage.StorageOwnership
import se.optiqon.voice.domain.transcription.NetworkMonitor
import se.optiqon.voice.testing.AccessFixture
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stopping work, as a mechanism rather than a promise.
 *
 * Refusing an effect at the boundary is the last line; it only works if something still asks.
 * A job that has already passed its last check and is waiting on a socket asks nobody. So each
 * identity owns a scope, and an identity change cancels it: a cancelled coroutine cannot reach
 * its next suspension point, which means it cannot reach its next network call either.
 *
 * The delicate part is that cancellation must be surgical. It has to take the departing
 * account's work and nothing else — least of all the machinery that would notice the *next*
 * identity, which is why the scope is a supervised child rather than the application scope.
 * Both halves are asserted here, and every "nothing happened" has a control showing the same
 * fixture can make it happen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class IdentityScopeCancellationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Records a restart instead of ending the JVM the test is running in. */
    private class RecordingRestarter : ProcessRestarter {
        var restarts = 0
            private set

        override fun restart() {
            restarts++
        }
    }

    private class Harness(
        val access: AccessFixture,
        val session: AccessSession,
        val restarter: RecordingRestarter
    )

    private fun TestScope.harness(): Harness {
        val access = AccessFixture(context, backgroundScope)
        val owner = DeviceDataOwner(context)
        val restarter = RecordingRestarter()
        val session = AccessSession(
            authGateway = access.auth,
            accessRepository = access.repository,
            refresher = access.refresher,
            activeIdentity = access.activeIdentity,
            networkMonitor = NetworkMonitor(context),
            deviceDataOwner = owner,
            processRestarter = restarter,
            storageOwnership = StorageOwnership(owner) { null },
            scope = backgroundScope
        )
        session.start()
        settle()
        return Harness(access, session, restarter)
    }

    /**
     * Lets the collectors run. Real time is yielded as well as virtual, because the access
     * layer's persistence is DataStore, whose file I/O is not on the test dispatcher and
     * therefore cannot be advanced — only waited for.
     */
    private fun TestScope.settle(rounds: Int = 20) {
        repeat(rounds) {
            runCurrent()
            @Suppress("BlockingMethodInNonBlockingContext")
            Thread.sleep(1L)
        }
        runCurrent()
    }

    /**
     * Work of the shape this is all about: it has passed every check it will ever pass, and is
     * now suspended with one external effect still ahead of it.
     */
    private fun launchPendingEffect(scope: CoroutineScope, effects: AtomicInteger) {
        scope.launch {
            delay(60_000L)
            effects.incrementAndGet()
        }
    }

    @Test
    fun `an identity change cancels work that has not had its effect yet`() = runTest {
        val h = harness()
        h.access.auth.signIn("uid-a", "a@example.test")
        settle()
        val effects = AtomicInteger()
        val scopeBefore = h.session.identityScope.value
        launchPendingEffect(scopeBefore, effects)
        runCurrent()

        h.access.auth.signIn("uid-b", "b@example.test")
        settle()
        // Far past the moment the effect would have happened, had anything survived.
        testScheduler.advanceTimeBy(120_000L)
        runCurrent()

        assertFalse("the departing account's scope is gone", scopeBefore.isActive)
        assertEquals("and its remaining work never reached its effect", 0, effects.get())
    }

    @Test
    fun `the same work does reach its effect when the identity holds`() = runTest {
        // The control. Without it, the test above would pass just as well against a scope that
        // never runs anything at all.
        val h = harness()
        h.access.auth.signIn("uid-a", "a@example.test")
        settle()
        val effects = AtomicInteger()
        launchPendingEffect(h.session.identityScope.value, effects)
        runCurrent()

        testScheduler.advanceTimeBy(120_000L)
        runCurrent()

        assertEquals(1, effects.get())
    }

    @Test
    fun `signing out cancels just as an account switch does`() = runTest {
        val h = harness()
        h.access.auth.signIn("uid-a", "a@example.test")
        settle()
        val effects = AtomicInteger()
        val scopeBefore = h.session.identityScope.value
        launchPendingEffect(scopeBefore, effects)
        runCurrent()

        h.access.auth.clear()
        settle()
        testScheduler.advanceTimeBy(120_000L)
        runCurrent()

        assertFalse(scopeBefore.isActive)
        assertEquals("nothing resumes on behalf of somebody who left", 0, effects.get())
    }

    @Test
    fun `the replacement scope is a new one, and it is alive`() = runTest {
        val h = harness()
        h.access.auth.signIn("uid-a", "a@example.test")
        settle()
        val before = h.session.identityScope.value

        h.access.auth.signIn("uid-b", "b@example.test")
        settle()
        val after = h.session.identityScope.value

        assertNotSame("a cancelled scope cannot be reused; it must be replaced", before, after)
        assertTrue("the arriving account has somewhere to work", after.isActive)

        // And it actually runs: a scope that is merely `isActive` but never scheduled would
        // fail the app in exactly the way this replaces.
        val effects = AtomicInteger()
        launchPendingEffect(after, effects)
        runCurrent()
        testScheduler.advanceTimeBy(120_000L)
        runCurrent()
        assertEquals(1, effects.get())
    }

    @Test
    fun `re-reporting the same account is not an identity change`() = runTest {
        val h = harness()
        h.access.auth.signIn("uid-a", "a@example.test")
        settle()
        val effects = AtomicInteger()
        val scope = h.session.identityScope.value
        launchPendingEffect(scope, effects)
        runCurrent()

        // Firebase's auth-state listener re-emits for reasons of its own — a token refresh, a
        // reload. Treating that as a switch would cancel work that is perfectly valid.
        h.access.auth.signIn("uid-a", "a@example.test")
        settle()
        testScheduler.advanceTimeBy(120_000L)
        runCurrent()

        assertTrue(scope.isActive)
        assertEquals(1, effects.get())
    }

    @Test
    fun `cancelling an identity does not cancel the machinery that watches for the next one`() =
        runTest {
            val h = harness()
            h.access.auth.signIn("uid-a", "a@example.test")
            settle()
            h.access.auth.signIn("uid-b", "b@example.test")
            settle()

            // The scope being cancelled is a supervised child. Had it been the session's own
            // scope, this second switch would go unnoticed — and an unnoticed switch is an
            // account working under its predecessor's files.
            h.access.auth.signIn("uid-c", "c@example.test")
            settle()

            assertEquals("uid-c", h.access.activeIdentity.current.value?.uid)
            assertTrue(h.session.identityScope.value.isActive)
        }

    @Test
    fun `a lease from the departing account stops being valid the moment it leaves`() = runTest {
        val h = harness()
        h.access.auth.signIn("uid-a", "a@example.test")
        settle()
        h.access.recordServerVerdict(AccountStatus.APPROVED)
        awaitAllowed(h)

        val grant = h.access.guard.authorize()
        assertTrue("the control: uid-a really is allowed to dictate", grant is AccessGrant.Granted)
        val lease = (grant as AccessGrant.Granted).lease
        assertTrue(lease.isValid())

        h.access.auth.signIn("uid-b", "b@example.test")
        settle()

        // Cancellation stops the work that is still ours to stop. The lease covers the rest:
        // anything already past a suspension point still has to ask before it has an effect.
        assertFalse("a lease belongs to an identity, not to a device", lease.isValid())
    }

    private suspend fun TestScope.awaitAllowed(h: Harness) {
        repeat(200) {
            runCurrent()
            if (h.access.repository.decision.value is AccessDecision.Allowed) return
            withContext(Dispatchers.IO) { delay(5L) }
        }
        error("uid-a never became allowed; the fixture, not the contract, is broken")
    }
}
