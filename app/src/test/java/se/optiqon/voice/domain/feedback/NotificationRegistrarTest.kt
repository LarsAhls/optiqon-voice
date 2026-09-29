package se.optiqon.voice.domain.feedback

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.optiqon.voice.domain.feedback.NotificationRegistrar.Outcome
import se.optiqon.voice.testing.SwitchableAuth

/** S6: the push token is bound to the one account that may receive on it, and to nobody else. */
class NotificationRegistrarTest {

    private class FakeTokens : NotificationTokens {
        /** uid -> installationId -> token, as the server would hold it. */
        val held = mutableMapOf<String, MutableMap<String, String>>()
        val calls = mutableListOf<String>()
        var result: RemoteResult = RemoteResult.Ok

        override suspend fun register(uid: String, installationId: String, token: String): RemoteResult {
            calls += "register:$uid"
            if (result == RemoteResult.Ok) held.getOrPut(uid) { mutableMapOf() }[installationId] = token
            return result
        }

        override suspend fun unregister(uid: String, installationId: String): RemoteResult {
            calls += "unregister:$uid"
            if (result == RemoteResult.Ok) held[uid]?.remove(installationId)
            return result
        }
    }

    private val tokens = FakeTokens()
    private val auth = SwitchableAuth("uid-a")
    private var approved = true
    private var granted = true
    private var token: String? = "tok-1"
    private var tokenAsked = 0

    private fun registrar(remoteEnabled: Boolean = true) = NotificationRegistrar(
        tokens, { tokenAsked++; token }, { granted }, auth, { approved }, "inst-1", remoteEnabled
    )

    @Test
    fun `an approved account with permission registers its token`() = runTest {
        assertEquals(Outcome.REGISTERED, registrar().sync())
        assertEquals(mapOf("inst-1" to "tok-1"), tokens.held["uid-a"])
    }

    @Test
    fun `a denied permission registers nothing and removes what was there`() = runTest {
        registrar().sync()
        granted = false

        assertEquals(Outcome.UNREGISTERED, registrar().sync())
        assertTrue(tokens.held["uid-a"].isNullOrEmpty())
        assertEquals(listOf("register:uid-a", "unregister:uid-a"), tokens.calls)
    }

    @Test
    fun `a denied permission never even asks for a token`() = runTest {
        granted = false
        registrar().sync()
        assertEquals(0, tokenAsked)
    }

    @Test
    fun `revocation takes the token away so nothing more is delivered`() = runTest {
        registrar().sync()
        approved = false

        assertEquals(Outcome.UNREGISTERED, registrar().sync())
        assertTrue(tokens.held["uid-a"].isNullOrEmpty())
    }

    @Test
    fun `an account switch removes the token from the account left before the next one gets it`() = runTest {
        registrar().sync()

        assertEquals(Outcome.UNREGISTERED, registrar().leaving("uid-a"))
        auth.currentUid = "uid-b"
        assertEquals(Outcome.REGISTERED, registrar().sync())

        assertTrue(tokens.held["uid-a"].isNullOrEmpty())
        assertEquals(mapOf("inst-1" to "tok-1"), tokens.held["uid-b"])
        assertEquals(listOf("register:uid-a", "unregister:uid-a", "register:uid-b"), tokens.calls)
    }

    @Test
    fun `an account switch while the token is fetched registers nothing for either`() = runTest {
        val switching = NotificationRegistrar(
            tokens, { auth.currentUid = "uid-b"; "tok-1" }, { granted }, auth, { approved }, "inst-1", true
        )
        assertEquals(Outcome.NOTHING, switching.sync())
        assertTrue(tokens.calls.isEmpty())
    }

    @Test
    fun `no token yet means no registration`() = runTest {
        token = null
        assertEquals(Outcome.UNREGISTERED, registrar().sync())
        assertTrue(tokens.held["uid-a"].isNullOrEmpty())
    }

    @Test
    fun `with remote feedback off nothing is registered or removed`() = runTest {
        assertEquals(Outcome.NOTHING, registrar(remoteEnabled = false).sync())
        assertEquals(Outcome.NOTHING, registrar(remoteEnabled = false).leaving("uid-a"))
        assertTrue(tokens.calls.isEmpty())
        assertEquals(0, tokenAsked)
    }

    @Test
    fun `signed out does nothing`() = runTest {
        auth.currentUid = null
        assertEquals(Outcome.NOTHING, registrar().sync())
        assertTrue(tokens.calls.isEmpty())
    }

    @Test
    fun `a refused write is reported, not taken for success`() = runTest {
        tokens.result = RemoteResult.Denied("rules")
        assertEquals(Outcome.FAILED, registrar().sync())
        assertEquals(Outcome.FAILED, registrar().leaving("uid-a"))
    }
}
