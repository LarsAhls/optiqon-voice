package se.optiqon.voice.domain.feedback

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.optiqon.voice.testing.SwitchableAuth

/**
 * S6 receive path: a push is attention only. It is shown with fixed text, to an approved account
 * that lets the app post, in a remote build — and it never marks anything read.
 */
class FeedbackPushHandlerTest {

    private class FakeTokens : NotificationTokens {
        val held = mutableMapOf<String, MutableMap<String, String>>()
        override suspend fun register(uid: String, installationId: String, token: String): RemoteResult {
            held.getOrPut(uid) { mutableMapOf() }[installationId] = token
            return RemoteResult.Ok
        }
        override suspend fun unregister(uid: String, installationId: String): RemoteResult {
            held[uid]?.remove(installationId)
            return RemoteResult.Ok
        }
    }

    private val auth = SwitchableAuth("uid-a")
    private var approved = true
    private var granted = true
    private var posted = 0
    private var token = "tok-1"
    private val tokens = FakeTokens()
    private val reads = UnreadTrackerTest.FakeReads()

    private fun handler(remoteEnabled: Boolean = true): FeedbackPushHandler {
        val registrar = NotificationRegistrar(
            tokens, { token }, { granted }, auth, { approved }, "inst-1", remoteEnabled
        )
        return FeedbackPushHandler(
            auth, { approved }, { granted }, { posted++ }, FeedbackNotificationSync(registrar), remoteEnabled
        )
    }

    private val reply = mapOf("kind" to FeedbackReplyNotice.KIND)

    @Test
    fun `the notice is fixed neutral text with no case id and nothing private`() {
        assertEquals("OPTIQON Voice", FeedbackReplyNotice.TITLE)
        assertEquals("Du har fått svar på din feedback.", FeedbackReplyNotice.BODY)
        // The handler has no parameter through which a message's content could reach the notice.
        assertEquals(0, NoticePoster::class.java.declaredMethods.single { it.name == "post" }.parameterCount)
    }

    @Test
    fun `an approved account with permission is shown the notice`() = runTest {
        assertTrue(handler().onMessage(reply))
        assertEquals(1, posted)
    }

    @Test
    fun `whatever else the message carries is ignored and never shown`() = runTest {
        val leaky = reply + mapOf("caseId" to "c1", "title" to "secret", "body" to "reply text")
        assertTrue(handler().onMessage(leaky))
        assertEquals(1, posted)
    }

    @Test
    fun `any other kind shows nothing`() = runTest {
        assertFalse(handler().onMessage(mapOf("kind" to "internal_note")))
        assertFalse(handler().onMessage(mapOf("kind" to "status_change")))
        assertFalse(handler().onMessage(emptyMap()))
        assertEquals(0, posted)
    }

    @Test
    fun `a revoked or pending account is shown nothing`() = runTest {
        approved = false
        assertFalse(handler().onMessage(reply))
        assertEquals(0, posted)
    }

    @Test
    fun `signed out is shown nothing`() = runTest {
        auth.currentUid = null
        assertFalse(handler().onMessage(reply))
        assertEquals(0, posted)
    }

    @Test
    fun `denied permission shows nothing and breaks nothing`() = runTest {
        granted = false
        assertFalse(handler().onMessage(reply))
        assertEquals(0, posted)
    }

    @Test
    fun `with remote feedback off nothing is shown and nothing is registered`() = runTest {
        val off = handler(remoteEnabled = false)
        assertFalse(off.onMessage(reply))
        off.onNewToken()
        assertEquals(0, posted)
        assertTrue(tokens.held.isEmpty())
    }

    @Test
    fun `receiving a reply marks nothing read`() = runTest {
        val tracker = UnreadTracker(CaseUnread(reads, auth) { approved }, auth)
        val case = FeedbackCase("c1", "t", "b", "Mottaget", 1L, 1L, 0, false, 2L)
        assertEquals(setOf("c1"), tracker.refresh(listOf(case)))

        handler().onMessage(reply)

        assertTrue(reads.writes.isEmpty())
        assertEquals(setOf("c1"), tracker.refresh(listOf(case)))
    }

    @Test
    fun `a refreshed token replaces the registration`() = runTest {
        val h = handler()
        h.onNewToken()
        assertEquals(mapOf("inst-1" to "tok-1"), tokens.held["uid-a"])

        token = "tok-2"
        h.onNewToken()
        assertEquals(mapOf("inst-1" to "tok-2"), tokens.held["uid-a"])
    }

    @Test
    fun `a token refresh for a revoked account removes the registration instead`() = runTest {
        val h = handler()
        h.onNewToken()
        approved = false
        h.onNewToken()
        assertTrue(tokens.held["uid-a"].isNullOrEmpty())
    }

    @Test
    fun `a sync that throws is reported as failed, not raised`() = runTest {
        val throwing = NotificationRegistrar(
            object : NotificationTokens {
                override suspend fun register(uid: String, installationId: String, token: String): RemoteResult =
                    error("boom")
                override suspend fun unregister(uid: String, installationId: String): RemoteResult = error("boom")
            },
            { "tok" }, { true }, auth, { true }, "inst-1", true
        )
        assertEquals(NotificationRegistrar.Outcome.FAILED, FeedbackNotificationSync(throwing).sync())
    }
}
