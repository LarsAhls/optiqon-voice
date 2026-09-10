package se.optiqon.voice.domain.transcription

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.MockResponse
import se.optiqon.voice.data.db.entity.Dictation
import se.optiqon.voice.domain.model.DictationStatus
import se.optiqon.voice.domain.access.AccessRevokedException
import se.optiqon.voice.domain.access.AccountStatus
import se.optiqon.voice.domain.access.RefreshOutcome
import se.optiqon.voice.testing.TranscriptionFixture

/**
 * Finding 1, pinned: the history retry reached transcription with no access check at all.
 *
 * `HomeScreen` → `HistoryViewModel.retry` → `HistoryRepository.retrySavedFailure` →
 * `TranscriptionManager.retry` was a complete route into ASR and injection that never asked
 * whether the account was allowed to dictate, while the bubble's own route did ask. That is the
 * failure mode the gate's placement is meant to make impossible: the check now lives in
 * `TranscriptionManager` itself, so a route cannot exist without it.
 *
 * Every assertion below is about the network, not about a return value. A refusal that still
 * uploads the audio would satisfy a `Result.failure` assertion and would still have sent the
 * user's voice to a third party.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TranscriptionGuardTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var fixture: TranscriptionFixture

    @After
    fun tearDown() {
        if (::fixture.isInitialized) fixture.shutdown()
    }

    private suspend fun blockedAs(status: AccountStatus): TranscriptionFixture {
        val f = TranscriptionFixture(context)
        fixture = f
        f.configureAsr()
        f.access.signIn("uid-a")
        f.access.recordServerVerdict(status)
        // The gate refreshes before granting; the server keeps saying the same thing.
        f.access.server.answer = { RefreshOutcome.Confirmed(status) }
        return f
    }

    /** A saved failure on disk, which is what the history retry button acts on. */
    private suspend fun TranscriptionFixture.savedFailure(): Long {
        val audio = audioFile()
        return database.dictationDao().insert(
            Dictation(
                text = "",
                rawText = "",
                wordCount = 0,
                durationMs = 1_000L,
                status = DictationStatus.FAILURE.name,
                errorMessage = "network error",
                audioPath = audio.absolutePath
            )
        )
    }

    @Test
    fun `a pending account cannot dictate`() = runTest {
        val f = blockedAs(AccountStatus.PENDING)

        val result = f.manager.transcribe(f.audioFile(), 1_000L, appContext = null)

        assertTrue(result.exceptionOrNull() is AccessRevokedException)
        assertEquals("no audio may leave the device", 0, f.tls.server.requestCount)
    }

    @Test
    fun `a rejected account cannot dictate`() = runTest {
        val f = blockedAs(AccountStatus.REJECTED)

        val result = f.manager.transcribe(f.audioFile(), 1_000L, appContext = null)

        assertTrue(result.exceptionOrNull() is AccessRevokedException)
        assertEquals(0, f.tls.server.requestCount)
    }

    @Test
    fun `a revoked account cannot dictate`() = runTest {
        val f = blockedAs(AccountStatus.REVOKED)

        val result = f.manager.transcribe(f.audioFile(), 1_000L, appContext = null)

        assertTrue(result.exceptionOrNull() is AccessRevokedException)
        assertEquals(0, f.tls.server.requestCount)
    }

    @Test
    fun `an approved account whose grace has run out cannot dictate`() = runTest {
        val f = blockedAs(AccountStatus.APPROVED)
        f.access.server.answer = { RefreshOutcome.NoNetwork }
        f.access.clock.advance(73L * 60L * 60L * 1000L)

        val result = f.manager.transcribe(f.audioFile(), 1_000L, appContext = null)

        assertTrue(result.exceptionOrNull() is AccessRevokedException)
        assertEquals(0, f.tls.server.requestCount)
    }

    @Test
    fun `the history retry refuses for a revoked account — the route that had no gate`() = runTest {
        val f = blockedAs(AccountStatus.REVOKED)
        val id = f.savedFailure()

        val result = f.manager.retry(id)

        assertTrue(result.exceptionOrNull() is AccessRevokedException)
        assertEquals("the saved audio must not be uploaded", 0, f.tls.server.requestCount)
    }

    @Test
    fun `the history retry refuses once grace has run out`() = runTest {
        val f = blockedAs(AccountStatus.APPROVED)
        val id = f.savedFailure()
        f.access.server.answer = { RefreshOutcome.NoNetwork }
        f.access.clock.advance(73L * 60L * 60L * 1000L)

        val result = f.manager.retry(id)

        assertTrue(result.exceptionOrNull() is AccessRevokedException)
        assertEquals(0, f.tls.server.requestCount)
    }

    @Test
    fun `a refusal writes no history row`() = runTest {
        val f = blockedAs(AccountStatus.REVOKED)

        f.manager.transcribe(f.audioFile(), 1_000L, appContext = null)

        // A blocked user must not accumulate failed dictations they could not have avoided —
        // and a retriable entry for work that must never be retried would be worse still.
        assertEquals(0, f.database.dictationDao().getRecent().first().size)
    }

    /**
     * The control the seven refusals above depend on.
     *
     * Every one of them asserts `requestCount == 0`, which is also what a fixture that could
     * never reach its provider would report — a broken graph would make them all pass while
     * proving nothing. This is the same fixture, the same call, and the only difference is the
     * verdict.
     */
    @Test
    fun `an approved account does reach the provider`() = runTest {
        val f = blockedAs(AccountStatus.APPROVED)
        f.tls.server.enqueue(MockResponse().setBody("""{"text":"hej"}"""))

        val result = f.manager.transcribe(f.audioFile(), 1_000L, appContext = null)

        // The failure, if any, is the message: a broken fixture should say why.
        assertEquals("${result.exceptionOrNull()}", "hej", result.getOrNull())
        assertEquals(1, f.tls.server.requestCount)
    }

    @Test
    fun `a signed-out device cannot dictate at all`() = runTest {
        val f = blockedAs(AccountStatus.APPROVED)
        f.access.signOut()

        val result = f.manager.transcribe(f.audioFile(), 1_000L, appContext = null)

        assertTrue(result.exceptionOrNull() is AccessRevokedException)
        assertEquals(0, f.tls.server.requestCount)
    }
}
