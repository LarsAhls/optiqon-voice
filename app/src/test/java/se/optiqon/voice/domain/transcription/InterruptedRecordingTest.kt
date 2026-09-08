package se.optiqon.voice.domain.transcription

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.optiqon.voice.data.storage.StorageRoot
import se.optiqon.voice.domain.access.AccessRevokedException
import se.optiqon.voice.domain.access.AccountStatus
import se.optiqon.voice.domain.access.BlockReason
import se.optiqon.voice.domain.access.RefreshOutcome
import se.optiqon.voice.domain.model.DictationStatus
import se.optiqon.voice.testing.TranscriptionFixture
import java.io.File

/**
 * Losing permission mid-sentence must stop the sending. It must not destroy the recording.
 *
 * Those are two different obligations and it is tempting to satisfy the first by doing the
 * second — cancel everything, delete the buffer, done. That would take someone's words away
 * from them for an administrative reason, at the one moment they cannot repeat them.
 *
 * So the rule is narrower than "cancel": stop every outgoing effect immediately, never resume
 * or retry on its own, and keep what was recorded **on exactly the terms the user already
 * chose**. History off still means history off; interruption is not a reason to start keeping
 * audio for somebody who asked us not to. That is why this reuses the ordinary failure path
 * rather than inventing a special store — a special store would be a new decision taken on the
 * user's behalf.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class InterruptedRecordingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var fixture: TranscriptionFixture? = null

    @After
    fun tearDown() {
        fixture?.shutdown()
    }

    @Test
    fun `an interrupted recording is kept for its owner, with its audio`() = runTest {
        val f = approved()
        history(f, enabled = true)
        val audio = f.audioFile()

        f.manager.preserveInterrupted(audio, 4_000L, null, AccessRevokedException(BlockReason.REVOKED))

        val rows = f.database.dictationDao().getRecent().first()
        assertEquals(1, rows.size)
        assertEquals(DictationStatus.FAILURE.name, rows.single().status)
        val entry = f.database.dictationDao().getById(rows.single().id)!!
        val retained = entry.audioPath?.let(::File)
        assertNotNull("without the audio there is nothing to come back to", retained)
        assertTrue("the kept copy exists", retained!!.exists())
        assertEquals(
            "and it lives under this account's own root",
            StorageRoot.DEFAULT.retainedAudioDir(context.filesDir),
            retained.parentFile
        )
    }

    @Test
    fun `history turned off means nothing is written and no audio is kept`() = runTest {
        val f = approved()
        history(f, enabled = false)
        val audio = f.audioFile()

        f.manager.preserveInterrupted(audio, 4_000L, null, AccessRevokedException(BlockReason.REVOKED))

        assertEquals(
            "an interruption is not an occasion to overrule a standing choice",
            0,
            f.database.dictationDao().getRecent().first().size
        )
        val retainedDir = StorageRoot.DEFAULT.retainedAudioDir(context.filesDir)
        assertFalse(
            "and no audio store appears for a user who turned audio off",
            retainedDir.exists() && retainedDir.listFiles()?.isNotEmpty() == true
        )
    }

    @Test
    fun `an empty recording leaves nothing behind`() = runTest {
        val f = approved()
        history(f, enabled = true)
        val empty = File.createTempFile("silence", ".wav", context.cacheDir)

        f.manager.preserveInterrupted(empty, 0L, null, AccessRevokedException(BlockReason.REVOKED))

        // A zero-length file is not a recording, and filing it would give the user a history
        // entry that can never do anything.
        assertEquals(0, f.database.dictationDao().getRecent().first().size)
    }

    @Test
    fun `preserving sends nothing, then or later`() = runTest {
        val f = approved()
        history(f, enabled = true)

        f.manager.preserveInterrupted(f.audioFile(), 4_000L, null, AccessRevokedException(BlockReason.REVOKED))

        assertEquals(
            "preserving is a local act; an automatic retry is what this is here to prevent",
            0,
            f.tls.server.requestCount
        )
    }

    @Test
    fun `the preserved item cannot be retried while its owner is blocked`() = runTest {
        val f = approved()
        history(f, enabled = true)
        f.manager.preserveInterrupted(f.audioFile(), 4_000L, null, AccessRevokedException(BlockReason.REVOKED))
        val id = f.manager.latestRetriableFailureId()
        assertNotNull("the control: it is a retriable row to begin with", id)

        revoke(f)
        val result = f.manager.retry(id!!)

        assertTrue("${result.getOrNull()}", result.isFailure)
        assertTrue("${result.exceptionOrNull()}", result.exceptionOrNull() is AccessRevokedException)
        assertEquals("and its audio stayed on the device", 0, f.tls.server.requestCount)
    }

    @Test
    fun `the same item is retriable by its owner once they are allowed again`() = runTest {
        // The control for the refusal above. Preservation is only defensible if the owner can
        // actually come back to it — otherwise "kept" is indistinguishable from lost.
        val f = approved()
        history(f, enabled = true)
        f.manager.preserveInterrupted(f.audioFile(), 4_000L, null, AccessRevokedException(BlockReason.REVOKED))
        val id = f.manager.latestRetriableFailureId()!!
        f.tls.server.enqueue(MockResponse().setBody("""{"text":"hej"}"""))

        val result = f.manager.retry(id)

        assertEquals("${result.exceptionOrNull()}", "hej", result.getOrNull())
        assertEquals(1, f.tls.server.requestCount)
    }

    @Test
    fun `a revoked account is refused before any audio is preserved as a failed dictation`() =
        runTest {
            val f = approved()
            history(f, enabled = true)
            revoke(f)

            val result = f.manager.transcribe(f.audioFile(), 4_000L, null)

            assertTrue(result.exceptionOrNull() is AccessRevokedException)
            // A refusal on account grounds is not a transcription failure. Filing it as one
            // would hand a blocked user a list of failures they could not have avoided — and a
            // retriable row for work that must not be retried.
            assertEquals(0, f.database.dictationDao().getRecent().first().size)
            assertNull(f.manager.latestRetriableFailureId())
        }

    private suspend fun approved(): TranscriptionFixture {
        val f = TranscriptionFixture(context)
        fixture = f
        f.configureAsr()
        f.access.signIn("uid-a")
        f.access.recordServerVerdict(AccountStatus.APPROVED)
        f.access.server.answer = { RefreshOutcome.Confirmed(AccountStatus.APPROVED) }
        return f
    }

    private suspend fun revoke(f: TranscriptionFixture) {
        f.access.recordServerVerdict(AccountStatus.REVOKED)
        f.access.server.answer = { RefreshOutcome.Confirmed(AccountStatus.REVOKED) }
    }

    /** The standing retention choice, written the way the settings screen writes it. */
    private suspend fun history(f: TranscriptionFixture, enabled: Boolean) {
        f.preferences.updateGeneralSettings(
            autoClipboard = false,
            vibrateOnRecord = false,
            pauseOtherAudio = false,
            silenceThresholdMs = 2_000L,
            historyEnabled = enabled,
            keepStatsWithoutHistory = false,
            historyRetentionLimit = 100,
            startOnBoot = false
        )
    }
}
