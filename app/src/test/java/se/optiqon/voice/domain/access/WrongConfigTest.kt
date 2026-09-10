package se.optiqon.voice.domain.access

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.optiqon.voice.data.storage.DeviceDataOwner
import se.optiqon.voice.data.storage.ProcessRestarter
import se.optiqon.voice.data.storage.StorageOwnership
import se.optiqon.voice.data.storage.StorageRoot
import se.optiqon.voice.domain.transcription.NetworkMonitor
import se.optiqon.voice.testing.AccessFixture
import se.optiqon.voice.testing.TranscriptionFixture

/**
 * Whose settings a job runs under, when the account changes while it is running.
 *
 * A transcription reads a profile, a post-processing prompt and an API key. All three come from
 * storage, and storage belongs to an account. The failure this guards against is quiet and
 * specific: a job started by one person suspends, somebody else signs in, and the job resumes
 * and resolves *the new person's* key — sending one user's audio under another user's
 * credentials, and filing the result in their history.
 *
 * The app's answer is deliberately not "re-resolve carefully". It is that such a job does not
 * resume at all: within a process the lease refuses it at the boundary, and a switch to an
 * account whose storage root differs ends the process instead of re-pointing open handles.
 * Both halves are tested, because either one alone leaves the hole open.
 *
 * The evidence for the first half is the `Authorization` header actually put on the wire —
 * assertions about which key was *read* would be satisfied by code that read the right one and
 * sent the wrong one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class WrongConfigTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var fixture: TranscriptionFixture? = null

    @After
    fun tearDown() {
        fixture?.shutdown()
    }

    // --- Half one: within a process, an in-flight job is refused rather than re-resolved -----

    @Test
    fun `a switch before the upload means the new account's key is never used`() = runTest {
        val f = approved()
        val lease = f.lease()

        switchTo(f, "uid-b", KEY_B)

        val thrown = refusal { f.manager.transcribe(f.audioFile(), 1_000L, null, lease) }
        assertTrue("$thrown", thrown.message!!.contains("NOT_REGISTERED"))
        assertEquals("nothing was sent under anybody's key", 0, f.tls.server.requestCount)
    }

    @Test
    fun `a switch while the audio is on the wire stops the call that would use the new key`() =
        runTest {
            val f = approved()
            val lease = f.lease()
            f.tls.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    // The switch lands mid-upload: the ASR request is already gone, and the
                    // post-processing request is not.
                    switchTo(f, "uid-b", KEY_B)
                    return MockResponse().setBody("""{"text":"hej"}""")
                }
            }

            refusal { f.manager.transcribe(f.audioFile(), 1_000L, null, lease) }

            assertEquals("the ASR call, and nothing after it", 1, f.tls.server.requestCount)
            assertEquals(
                "and the one call that did happen carried the key of whoever started it",
                "Bearer $KEY_A",
                f.tls.server.takeRequest().getHeader("Authorization")
            )
        }

    @Test
    fun `without a switch the dictation reaches the server under its own key`() = runTest {
        // The control. Both refusals above assert an absence, which a fixture that could not
        // send anything at all would satisfy just as well.
        val f = approved()
        val lease = f.lease()
        f.tls.server.enqueue(MockResponse().setBody("""{"text":"hej"}"""))

        val result = f.manager.transcribe(f.audioFile(), 1_000L, null, lease)

        assertEquals("${result.exceptionOrNull()}", "hej", result.getOrNull())
        assertEquals("Bearer $KEY_A", f.tls.server.takeRequest().getHeader("Authorization"))
    }

    // --- Half two: across accounts, the handles are not re-pointed at all --------------------

    @Test
    fun `an account with a different root gets a new process, not re-pointed handles`() = runTest {
        val h = harness()
        h.owner.claimDefault("uid-a")
        h.owner.setActiveUid("uid-a")

        h.access.auth.signIn("uid-b", "b@example.test")
        settle()

        val rootB = h.owner.rootFor("uid-b")
        assertNotEquals("a second account is a second set of files", StorageRoot.DEFAULT, rootB)
        assertEquals("uid-b", h.owner.activeUid())
        assertEquals(
            "the new identity is persisted before the process ends, so the next start opens " +
                "the right files rather than guessing",
            1,
            h.restarter.restarts
        )
    }

    @Test
    fun `the owner signing back in re-points nothing and restarts nothing`() = runTest {
        // The control for the restart: it must be caused by the root differing, not merely by
        // an identity changing, or every sign-in would kill the app.
        val h = harness()
        h.owner.claimDefault("uid-a")

        h.access.auth.signIn("uid-a", "a@example.test")
        settle()

        assertEquals(StorageRoot.DEFAULT, h.owner.rootFor("uid-a"))
        assertEquals(0, h.restarter.restarts)
    }

    @Test
    fun `two accounts never resolve to the same storage handles`() = runTest {
        val owner = DeviceDataOwner(context)
        owner.claimDefault("uid-a")
        owner.declineDefault("uid-b")

        val a = owner.rootFor("uid-a")
        val b = owner.rootFor("uid-b")

        // Every handle, not just the database: a shared preferences file or a shared audio
        // directory would leak exactly what the separate database was for.
        assertNotEquals(a.databaseName, b.databaseName)
        assertNotEquals(a.preferencesName, b.preferencesName)
        assertNotEquals(a.securePreferencesName, b.securePreferencesName)
        assertNotEquals(
            a.retainedAudioDir(context.filesDir),
            b.retainedAudioDir(context.filesDir)
        )
        assertFalse("and the device's own files stay where they were", b.isDefault)
        assertTrue(a.isDefault)
    }

    // --- Fixtures ----------------------------------------------------------------------------

    private suspend fun approved(): TranscriptionFixture {
        val f = TranscriptionFixture(context)
        fixture = f
        f.preferences.updateAsrConfig(f.tls.baseUrl, KEY_A, "whisper-1")
        f.access.signIn("uid-a")
        f.access.recordServerVerdict(AccountStatus.APPROVED)
        f.access.server.answer = { RefreshOutcome.Confirmed(AccountStatus.APPROVED) }
        return f
    }

    private suspend fun TranscriptionFixture.lease(): AccessLease =
        (access.guard.authorize() as AccessGrant.Granted).lease

    /**
     * Somebody else signs in, and the settings become theirs.
     *
     * In the app the settings change because a different root is open; here they are simply
     * overwritten, which is the harsher version of the same thing — the wrong key is not merely
     * reachable, it is the only one there.
     */
    private fun switchTo(f: TranscriptionFixture, uid: String, key: String) = runBlocking {
        f.access.signOut()
        f.access.signIn(uid)
        f.access.recordServerVerdict(AccountStatus.APPROVED)
        f.preferences.updateAsrConfig(f.tls.baseUrl, key, "whisper-1")
    }

    private suspend fun refusal(block: suspend () -> Unit): AccessRevokedException {
        try {
            block()
        } catch (e: AccessRevokedException) {
            return e
        }
        fail("expected the effect to be refused")
        error("unreachable")
    }

    private class RecordingRestarter : ProcessRestarter {
        var restarts = 0
            private set

        override fun restart() {
            restarts++
        }
    }

    private class Harness(
        val access: AccessFixture,
        val owner: DeviceDataOwner,
        val restarter: RecordingRestarter
    )

    private fun TestScope.harness(): Harness {
        val access = AccessFixture(context, backgroundScope)
        val owner = DeviceDataOwner(context)
        val restarter = RecordingRestarter()
        val ownership = StorageOwnership(owner) { access.auth.currentUid }
        // Read once here, because that is when the app reads it: the root is resolved as the
        // first storage handle is injected at start-up, before any of these accounts sign in.
        // Leaving it to resolve lazily *during* a switch would let the switch reconcile itself
        // and hide the very restart these tests are about.
        ownership.root
        AccessSession(
            authGateway = access.auth,
            accessRepository = access.repository,
            refresher = access.refresher,
            activeIdentity = access.activeIdentity,
            networkMonitor = NetworkMonitor(context),
            deviceDataOwner = owner,
            processRestarter = restarter,
            storageOwnership = ownership,
            scope = backgroundScope
        ).start()
        settle()
        return Harness(access, owner, restarter)
    }

    /** Virtual time for the collectors, real time for DataStore's file I/O. */
    private fun TestScope.settle(rounds: Int = 20) {
        repeat(rounds) {
            runCurrent()
            Thread.sleep(1L)
        }
        runCurrent()
    }

    private companion object {
        const val KEY_A = "key-belonging-to-uid-a"
        const val KEY_B = "key-belonging-to-uid-b"
    }
}
