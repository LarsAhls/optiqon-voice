package se.optiqon.voice.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.optiqon.voice.domain.feedback.FeedbackNotificationSync
import se.optiqon.voice.domain.feedback.NotificationRegistrar
import se.optiqon.voice.domain.feedback.NotificationTokens
import se.optiqon.voice.domain.feedback.RemoteResult
import se.optiqon.voice.testing.SwitchableAuth

/** S6 lifecycle: the registration follows identity, access decision and foreground, and only in a remote build. */
class FeedbackPushLifecycleTest {

    private class CountingTokens : NotificationTokens {
        val calls = mutableListOf<String>()
        override suspend fun register(uid: String, installationId: String, token: String): RemoteResult {
            calls += "register:$uid"; return RemoteResult.Ok
        }
        override suspend fun unregister(uid: String, installationId: String): RemoteResult {
            calls += "unregister:$uid"; return RemoteResult.Ok
        }
    }

    private sealed interface Verdict {
        data object Approved : Verdict
        data object Revoked : Verdict
    }

    private val tokens = CountingTokens()
    private val auth = SwitchableAuth("uid-a")
    private val identity = MutableStateFlow<String?>("uid-a")
    private val decision = MutableStateFlow<Any>(Verdict.Approved)
    private var granted = true

    private fun TestScope.lifecycle(remoteEnabled: Boolean = true): FeedbackPushLifecycle {
        val sync = FeedbackNotificationSync(
            NotificationRegistrar(
                tokens, { "tok" }, { granted }, auth, { decision.value == Verdict.Approved }, "inst-1", remoteEnabled
            )
        )
        return FeedbackPushLifecycle(sync, identity, decision, this, remoteEnabled)
    }

    @Test
    fun `startup registers, and a repeat of the same state does not sync again`() = runTest(StandardTestDispatcher()) {
        val l = lifecycle()
        l.start()
        l.start()
        runCurrent()
        decision.value = Verdict.Approved
        runCurrent()

        assertEquals(listOf("register:uid-a"), tokens.calls)
        coroutineContext.cancelChildren()
    }

    @Test
    fun `revocation removes the registration`() = runTest(StandardTestDispatcher()) {
        lifecycle().start()
        runCurrent()
        decision.value = Verdict.Revoked
        runCurrent()

        assertEquals(listOf("register:uid-a", "unregister:uid-a"), tokens.calls)
        coroutineContext.cancelChildren()
    }

    @Test
    fun `a permission change is picked up on the next foreground`() = runTest(StandardTestDispatcher()) {
        val l = lifecycle()
        l.start()
        runCurrent()
        granted = false
        l.onForeground()
        runCurrent()

        assertEquals(listOf("register:uid-a", "unregister:uid-a"), tokens.calls)
        coroutineContext.cancelChildren()
    }

    @Test
    fun `with remote feedback off nothing starts and nothing is synced`() = runTest(StandardTestDispatcher()) {
        val l = lifecycle(remoteEnabled = false)
        l.start()
        l.onForeground()
        runCurrent()
        decision.value = Verdict.Revoked
        runCurrent()

        assertTrue(tokens.calls.isEmpty())
    }

    private fun kotlin.coroutines.CoroutineContext.cancelChildren() =
        this[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }
}
