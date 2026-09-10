package se.optiqon.voice.domain.transcription

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.optiqon.voice.domain.access.AccessGrant
import se.optiqon.voice.domain.access.AccessLease
import se.optiqon.voice.domain.access.AccessRevokedException
import se.optiqon.voice.domain.access.AccountStatus
import se.optiqon.voice.domain.access.RefreshOutcome
import se.optiqon.voice.testing.TranscriptionFixture

/**
 * Authorisation is not a fact established once at the top of a function.
 *
 * A dictation runs for seconds and suspends repeatedly: a profile read, an upload, a wait on a
 * model, an injection. An account can be revoked, signed out or replaced inside any of those
 * gaps, so a check at the entry proves only what was true before the work started. These tests
 * move the revocation *into* the gaps and ask whether the next outgoing call still happens.
 *
 * The evidence is the request count, because that is what an effect means here: the point at
 * which the user's voice, or their text, has left the device and cannot be recalled.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class EffectBoundaryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var fixture: TranscriptionFixture

    @After
    fun tearDown() {
        if (::fixture.isInitialized) fixture.shutdown()
    }

    @Test
    fun `revocation between authorisation and the ASR call stops the upload`() = runTest {
        val f = approvedFixture()
        val lease = f.lease()

        // The gap: the lease is in hand, the audio is on disk, nothing has been sent.
        revoke(f)

        val thrown = refusal { f.manager.transcribe(f.audioFile(), 1_000L, null, lease) }
        assertTrue("$thrown", thrown.message!!.contains("REVOKED"))
        assertEquals("the audio must not leave the device", 0, f.tls.server.requestCount)
    }

    @Test
    fun `revocation while the ASR call is in flight stops everything after it`() = runTest {
        val f = approvedFixture()
        val lease = f.lease()

        // The revocation lands while the audio is on the wire — the one gap that cannot be
        // closed by checking earlier, because the upload is already under way.
        f.tls.server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                revoke(f)
                return MockResponse().setBody("""{"text":"hej"}""")
            }
        }

        val thrown = refusal { f.manager.transcribe(f.audioFile(), 1_000L, null, lease) }

        assertTrue("$thrown", thrown.message!!.contains("REVOKED"))
        assertEquals("the ASR call, and nothing after it", 1, f.tls.server.requestCount)
        // Post-processing never ran, so nothing was written down as a completed dictation.
        assertEquals(0, f.database.dictationDao().getRecent().first().size)
    }

    @Test
    fun `a lease issued to one account is void once another signs in`() = runTest {
        val f = approvedFixture()
        val lease = f.lease()

        f.access.signOut()
        f.access.signIn("uid-b")
        f.access.recordServerVerdict(AccountStatus.APPROVED)

        // uid-b is approved, so this is not a question about permission — it is a question
        // about whose permission. A lease is not transferable.
        refusal { f.manager.transcribe(f.audioFile(), 1_000L, null, lease) }
        assertEquals(0, f.tls.server.requestCount)
    }

    /**
     * The control the three refusals above depend on: the same fixture, the same lease, the
     * same call, with nothing revoked. Without it, a fixture that could not reach its server
     * would satisfy every `requestCount == 0` assertion while proving nothing at all.
     */
    @Test
    fun `the same lease works while it is still valid`() = runTest {
        val f = approvedFixture()
        val lease = f.lease()
        f.tls.server.enqueue(MockResponse().setBody("""{"text":"hej"}"""))

        val result = f.manager.transcribe(f.audioFile(), 1_000L, null, lease)

        assertEquals("${result.exceptionOrNull()}", "hej", result.getOrNull())
        assertEquals(1, f.tls.server.requestCount)
    }

    private suspend fun approvedFixture(): TranscriptionFixture {
        val f = TranscriptionFixture(context)
        fixture = f
        f.configureAsr()
        f.access.signIn("uid-a")
        f.access.recordServerVerdict(AccountStatus.APPROVED)
        f.access.server.answer = { RefreshOutcome.Confirmed(AccountStatus.APPROVED) }
        return f
    }

    private suspend fun TranscriptionFixture.lease(): AccessLease =
        (access.guard.authorize() as AccessGrant.Granted).lease

    /**
     * A refusal at a boundary is thrown, not returned: the boundary is passed by code that has
     * already committed to the effect, so there is no result left to hand back.
     */
    private suspend fun refusal(block: suspend () -> Unit): AccessRevokedException {
        try {
            block()
        } catch (e: AccessRevokedException) {
            return e
        }
        fail("expected the effect to be refused")
        error("unreachable")
    }

    /** What the server saying revoked does to the device, with no UI in the way. */
    private fun revoke(f: TranscriptionFixture) = runBlocking {
        f.access.recordServerVerdict(AccountStatus.REVOKED)
        f.access.server.answer = { RefreshOutcome.Confirmed(AccountStatus.REVOKED) }
    }
}
