package se.optiqon.voice.domain.transcription

import android.content.Context
import androidx.room.Room
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
import se.optiqon.voice.data.db.OptiqonVoiceDatabase
import se.optiqon.voice.data.db.entity.Dictation
import se.optiqon.voice.data.storage.StorageRoot
import se.optiqon.voice.di.DatabaseModule
import se.optiqon.voice.domain.access.AccessRevokedException
import se.optiqon.voice.domain.access.AccountStatus
import se.optiqon.voice.domain.access.BlockReason
import se.optiqon.voice.domain.access.RefreshOutcome
import se.optiqon.voice.domain.model.DictationStatus
import se.optiqon.voice.testing.TranscriptionFixture
import java.io.File

/**
 * A preserved recording belongs to the person who spoke it, and to nobody else.
 *
 * Keeping an interrupted dictation is only defensible if two things hold afterwards. Its owner
 * must be able to come back to it — otherwise "preserved" is a nicer word for lost. And nobody
 * else may reach it: not the next account on this device, and not the owner's own session while
 * that account is blocked, because "kept for later" must not become a way to send audio that
 * the server has just refused.
 *
 * The second promise is not kept by filtering a query. It is kept by the item living in its
 * owner's storage root, which another account does not open. That is why the cross-account half
 * of this file goes through the production database builder rather than the in-memory fixture:
 * the isolation under test *is* the file name.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PreservedItemOwnershipTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var fixture: TranscriptionFixture? = null

    @After
    fun tearDown() {
        fixture?.shutdown()
    }

    // --- The owner's own session ---------------------------------------------------------------

    @Test
    fun `a preserved item is not retriable once its owner has signed out`() = runTest {
        val f = preserved()
        val id = f.manager.latestRetriableFailureId()!!

        f.access.signOut()

        val result = f.manager.retry(id)
        assertTrue("${result.getOrNull()}", result.exceptionOrNull() is AccessRevokedException)
        assertEquals("no audio left the device on behalf of nobody", 0, f.tls.server.requestCount)
    }

    @Test
    fun `the owner signing back in can retry the very same item`() = runTest {
        // The control for every refusal here. A sign-out is not a revocation, and the whole
        // point of preserving was that the owner gets their words back.
        val f = preserved()
        val id = f.manager.latestRetriableFailureId()!!
        f.access.signOut()
        f.access.signIn("uid-a")
        f.tls.server.enqueue(MockResponse().setBody("""{"text":"hej"}"""))

        val result = f.manager.retry(id)

        assertEquals("${result.exceptionOrNull()}", "hej", result.getOrNull())
    }

    @Test
    fun `a revoked owner cannot retry it either`() = runTest {
        val f = preserved()
        val id = f.manager.latestRetriableFailureId()!!

        f.access.recordServerVerdict(AccountStatus.REVOKED)
        f.access.server.answer = { RefreshOutcome.Confirmed(AccountStatus.REVOKED) }

        val result = f.manager.retry(id)
        val thrown = result.exceptionOrNull() as AccessRevokedException
        assertEquals(BlockReason.REVOKED, thrown.reason)
        assertEquals(0, f.tls.server.requestCount)
    }

    @Test
    fun `the item survives the refusal rather than being consumed by it`() = runTest {
        val f = preserved()
        val id = f.manager.latestRetriableFailureId()!!
        f.access.signOut()

        f.manager.retry(id)

        // A refused retry must leave the entry exactly where it was. Losing it here would turn
        // an access decision into data loss — the failure mode this whole preservation path
        // exists to avoid.
        assertEquals(id, f.manager.latestRetriableFailureId())
        val entry = f.database.dictationDao().getById(id)!!
        assertTrue("and its audio is still on disk", File(entry.audioPath!!).exists())
    }

    // --- The next account on the same device ---------------------------------------------------

    @Test
    fun `a preserved item is retriable in the root it was written to`() = runTest {
        // The positive control for the isolation test below: the row and its audio are exactly
        // what a retry looks for, so the other root's silence is about the root, not the row.
        withDatabase(StorageRoot.DEFAULT) { db ->
            db.dictationDao().insert(preservedRow(StorageRoot.DEFAULT))
            assertNotNull(db.dictationDao().getLatestRetriableFailureId())
        }
    }

    @Test
    fun `another account finds no preserved item at all`() = runTest {
        withDatabase(StorageRoot.DEFAULT) { it.dictationDao().insert(preservedRow(StorageRoot.DEFAULT)) }

        withDatabase(SECOND) { db ->
            assertNull(
                "not hidden from the next account — absent from the files it opens",
                db.dictationDao().getLatestRetriableFailureId()
            )
            assertTrue(db.dictationDao().getRecent().first().isEmpty())
        }
    }

    @Test
    fun `and no path to the audio either`() {
        val ownerDir = StorageRoot.DEFAULT.retainedAudioDir(context.filesDir)
        ownerDir.mkdirs()
        val clip = File(ownerDir, "interrupted.m4a").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        assertTrue(clip.exists())
        assertFalse(
            "a recording of somebody's voice is the last thing that may be reachable",
            File(SECOND.retainedAudioDir(context.filesDir), clip.name).exists()
        )
    }

    // --- Fixtures ------------------------------------------------------------------------------

    /** An approved uid-a whose recording was interrupted, with history on so it is kept. */
    private suspend fun preserved(): TranscriptionFixture {
        val f = TranscriptionFixture(context)
        fixture = f
        f.configureAsr()
        f.access.signIn("uid-a")
        f.access.recordServerVerdict(AccountStatus.APPROVED)
        f.access.server.answer = { RefreshOutcome.Confirmed(AccountStatus.APPROVED) }
        f.preferences.updateGeneralSettings(
            autoClipboard = false,
            vibrateOnRecord = false,
            pauseOtherAudio = false,
            silenceThresholdMs = 2_000L,
            historyEnabled = true,
            keepStatsWithoutHistory = false,
            historyRetentionLimit = 100,
            startOnBoot = false
        )
        f.manager.preserveInterrupted(
            f.audioFile(),
            4_000L,
            null,
            AccessRevokedException(BlockReason.REVOKED)
        )
        assertNotNull(
            "the fixture must actually have preserved something",
            f.manager.latestRetriableFailureId()
        )
        return f
    }

    /** What `preserveInterrupted` leaves behind, written directly so a root can be chosen. */
    private fun preservedRow(root: StorageRoot): Dictation {
        val dir = root.retainedAudioDir(context.filesDir).apply { mkdirs() }
        val audio = File(dir, "interrupted.m4a").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        return Dictation(
            text = "",
            rawText = "",
            wordCount = 0,
            durationMs = 4_000L,
            status = DictationStatus.FAILURE.name,
            errorMessage = "interrupted",
            audioPath = audio.absolutePath
        )
    }

    /** The production builder and the production migration list, per root. */
    private suspend fun <T> withDatabase(
        root: StorageRoot,
        block: suspend (OptiqonVoiceDatabase) -> T
    ): T {
        val db = Room
            .databaseBuilder(context, OptiqonVoiceDatabase::class.java, root.databaseName)
            .addMigrations(*DatabaseModule.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            return block(db)
        } finally {
            db.close()
        }
    }

    private companion object {
        val SECOND = StorageRoot("u1")
    }
}
