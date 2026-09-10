package se.optiqon.voice.domain.access

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
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
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

/**
 * Leaving a screen is not the server failing.
 *
 * `runCatching` catches `Throwable`, and `CancellationException` is a `Throwable`. A read that
 * was abandoned because the caller walked away therefore came back as a failure — and in the
 * refresher a failure is not only rendered as one, it raises `consecutiveFailures`, which drives
 * the backoff that ends in `grace_expired`. That is the state that stops dictation. Navigating
 * away from the account screen a few times is not a reason for an app to lock itself.
 *
 * The distinction the app needs is narrower than "ignore cancellation": if *this* coroutine is
 * cancelled there is nobody left to hand a result to, so the cancellation is rethrown; if the
 * work we called died on its own while we are still very much alive, that is a failure and has
 * to be reported as one. Both halves are asserted, because a fix that rethrows indiscriminately
 * would turn a dead read into a silently cancelled caller.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CancellationPropagationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `a cancelled caller is not handed a failure it could act on`() = runTest {
        val forever = CompletableDeferred<Unit>()
        var result: Result<Unit>? = null
        var rethrown = false

        val job = launch {
            try {
                result = runCatchingCancellable { forever.await() }
            } catch (cancellation: CancellationException) {
                rethrown = true
                throw cancellation
            }
        }
        runCurrent()
        job.cancelAndJoin()

        assertTrue("cancelling the caller must stop it, not produce a value", rethrown)
        assertNull("a cancelled caller must never see a Result at all", result)
    }

    @Test
    fun `work that dies on its own is a failure, not a cancellation of the caller`() = runTest {
        // The control for the test above. Firebase cancels its own tasks, and a scope that is
        // torn down takes its children with it; neither is a reason for the coroutine that
        // asked to disappear without reporting anything.
        val abandoned = CompletableDeferred<Unit>()
        abandoned.cancel()

        val result = runCatchingCancellable { abandoned.await() }

        assertTrue("a dead callee is a failure the caller must be able to render", result.isFailure)
        assertTrue("and the caller itself is still running", coroutineContext.isActive)
    }

    @Test
    fun `an ordinary answer and an ordinary failure are unchanged`() = runTest {
        assertEquals("kaffe", runCatchingCancellable { "kaffe" }.getOrNull())

        val failure = runCatchingCancellable { throw IOException("no network") }
        assertTrue(failure.isFailure)
        assertTrue(failure.exceptionOrNull() is IOException)
    }

    @Test
    fun `a check the user walked away from is not reported as a failure`() = runTest {
        val f = abandonedCheck()

        assertFalse(
            "leaving a screen must not put \"could not reach the server\" on it",
            f.refresher.lastOutcome.value is RefreshOutcome.Failed
        )
    }

    @Test
    fun `a check the user walked away from does not back the next one off`() = runTest {
        // This is the half that can lock the app: every counted failure lengthens the wait, and
        // a long enough run of them ends in grace_expired, which stops dictation.
        val f = abandonedCheck()

        assertNotEquals(
            "backoff is for a server that will not answer, not for a user who changed screens",
            RefreshOutcome.Throttled,
            f.refresher.refresh(RefreshTrigger.FOREGROUND)
        )
    }

    @Test
    fun `a check that really fails does count, and does back the next one off`() = runTest {
        // The control for the test above: the same fixture, the same trigger, a real failure.
        // Without this, a refresher that had simply stopped counting anything would pass.
        val f = AccessFixture(context, backgroundScope)
        f.signIn("uid-a")
        settle()
        f.server.answer = { RefreshOutcome.Failed(IOException("the backend is unwell")) }

        val first = f.refresher.refresh(RefreshTrigger.FOREGROUND)
        settle()

        assertTrue("the control: this really is a failure", first is RefreshOutcome.Failed)
        assertTrue(f.refresher.lastOutcome.value is RefreshOutcome.Failed)
        assertEquals(
            "a failure must hold the next trigger back",
            RefreshOutcome.Throttled,
            f.refresher.refresh(RefreshTrigger.FOREGROUND)
        )
    }

    @Test
    fun `the access layer does not reach for bare runCatching again`() {
        // Three of the four sites this finding was about are in FirebaseSignInClient, around
        // CredentialManager and FirebaseAuth — static factories and final classes, with no
        // mocking framework in this module, so there is no way to drive them from a unit test.
        // This is the coverage that is available for them: neither file has any business
        // catching Throwable by hand, and a reviewer who sees this fail is told what to use.
        for (path in GUARDED) {
            val source = sourceOf(path)
            assertFalse(
                "$path uses runCatching, which swallows CancellationException and turns a user " +
                    "leaving the screen into a server failure. Use runCatchingCancellable.",
                source.contains("runCatching {") || source.contains("runCatching(")
            )
        }
    }

    /** Starts a check, cancels the caller mid-read, and lets the read it began finish. */
    private suspend fun TestScope.abandonedCheck(): AccessFixture {
        val f = AccessFixture(context, backgroundScope)
        f.signIn("uid-a")
        settle()

        val held = CompletableDeferred<RefreshOutcome>()
        f.server.answer = { held.await() }

        val job = launch { f.refresher.refresh(RefreshTrigger.FOREGROUND) }
        settle()
        // The screen goes away while the read is still open — the commonest thing a user does.
        job.cancelAndJoin()
        settle()

        // Let the read the app had already started finish, so a later trigger starts a new one
        // rather than joining this one and proving nothing.
        held.complete(RefreshOutcome.NoNetwork)
        settle()
        return f
    }

    /** The working directory is either the module or the root of the checkout. */
    private fun sourceOf(path: String): String =
        listOf(File("app/src/main/java/$path"), File("src/main/java/$path"))
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error("$path not found from ${File(".").absolutePath}; this guard has gone stale")

    /**
     * Lets the collectors run. Real time is yielded as well as virtual, because the access
     * layer's persistence is DataStore, whose file I/O is not on the test dispatcher.
     */
    private fun TestScope.settle(rounds: Int = 20) {
        repeat(rounds) {
            runCurrent()
            @Suppress("BlockingMethodInNonBlockingContext")
            Thread.sleep(1L)
        }
        runCurrent()
    }

    private companion object {
        val GUARDED = listOf(
            "se/optiqon/voice/domain/access/AccessRefresher.kt",
            "se/optiqon/voice/data/access/FirebaseSignInClient.kt"
        )
    }
}
