package se.optiqon.voice.domain.access

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rev. 11 matrix, as a pure function.
 *
 * Two mistakes are being guarded against here, and they pull in opposite directions. One is
 * telling a user their account is the problem when the truth is that we could not reach the
 * server — an accusation, made at the moment they are most likely to be reading. The other is
 * showing a calm banner over a session that has actually been blocked. Hence the shape of the
 * type: `Degraded` is never a full screen, `Blocked` is never a banner, and nothing in between.
 *
 * The offline case carries a third obligation. Being offline blocks dictation because cloud
 * transcription is unreachable, *not* because of the account — and there is no queue, so no
 * state here may be rendered as "it will be sent later".
 */
class AccessSessionStateTest {

    private fun state(
        decision: AccessDecision,
        signedIn: Boolean = true,
        hasSnapshot: Boolean = true,
        lastOutcome: RefreshOutcome? = RefreshOutcome.Confirmed(AccountStatus.APPROVED)
    ) = AccessSessionStates.evaluate(decision, signedIn, hasSnapshot, lastOutcome)

    @Test
    fun `approved and freshly confirmed is simply active`() {
        assertEquals(AccessSessionState.Active, state(AccessDecision.Allowed))
    }

    @Test
    fun `a failed check-in degrades the session and still allows dictation`() {
        val result = state(AccessDecision.Allowed, lastOutcome = RefreshOutcome.Failed(RuntimeException()))

        // A temporary Firebase or auth fault, with a working network and a reachable speech
        // service, is our problem. Refusing to dictate would punish the user for it.
        assertEquals(AccessSessionState.Degraded(DegradedKind.VERIFICATION_FAILED), result)
        assertTrue(AccessSessionStates.allowsDictation(result))
    }

    @Test
    fun `offline degrades for a different reason and does not allow dictation`() {
        val result = state(AccessDecision.Allowed, lastOutcome = RefreshOutcome.NoNetwork)

        assertEquals(AccessSessionState.Degraded(DegradedKind.OFFLINE), result)
        // Not an account refusal: the transcription itself has nowhere to go.
        assertFalse(AccessSessionStates.allowsDictation(result))
    }

    @Test
    fun `a throttled check-in is not a degradation`() {
        // Throttled means a recent server read is still good, which is the healthy path.
        assertEquals(AccessSessionState.Active, state(AccessDecision.Allowed, lastOutcome = RefreshOutcome.Throttled))
    }

    @Test
    fun `every server verdict blocks with its own reason`() {
        val reasons = listOf(
            BlockReason.AWAITING_APPROVAL,
            BlockReason.REJECTED,
            BlockReason.REVOKED,
            BlockReason.GRACE_EXPIRED
        )

        for (reason in reasons) {
            val result = state(AccessDecision.Blocked(reason))
            assertEquals("$reason must be told apart from the others", AccessSessionState.Blocked(reason), result)
            assertFalse(AccessSessionStates.allowsDictation(result))
        }
    }

    @Test
    fun `an unanswered first question is verifying, not an accusation`() {
        val result = state(
            AccessDecision.Blocked(BlockReason.NOT_REGISTERED),
            hasSnapshot = false,
            lastOutcome = null
        )

        assertEquals(AccessSessionState.Verifying, result)
        assertFalse("and it is never mistaken for approval", AccessSessionStates.allowsDictation(result))
    }

    @Test
    fun `once the server has answered, not registered is stated plainly`() {
        val result = state(
            AccessDecision.Blocked(BlockReason.NOT_REGISTERED),
            hasSnapshot = false,
            lastOutcome = RefreshOutcome.Confirmed(AccountStatus.NEW)
        )

        // The server did answer — the document simply is not there — so this is a verdict and
        // pretending to still be checking would be a stall with no end.
        assertEquals(AccessSessionState.Blocked(BlockReason.NOT_REGISTERED), result)
    }

    @Test
    fun `offline while unverified does not turn into a verdict`() {
        val result = state(
            AccessDecision.Blocked(BlockReason.NOT_REGISTERED),
            hasSnapshot = false,
            lastOutcome = RefreshOutcome.NoNetwork
        )

        assertEquals("no network means no answer, not a refusal", AccessSessionState.Verifying, result)
    }

    @Test
    fun `signed out beats everything else`() {
        assertEquals(
            AccessSessionState.SignedOut,
            state(AccessDecision.Allowed, signedIn = false)
        )
        assertFalse(AccessSessionStates.allowsDictation(AccessSessionState.SignedOut))
    }

    @Test
    fun `a degraded state is never blocked and a blocked state is never degraded`() {
        // The rule that keeps a banner from covering a refusal and a full screen from covering
        // a hiccup. Stated as a test because it is a property of the whole matrix, not of one
        // row of it.
        val outcomes = listOf(
            null,
            RefreshOutcome.Throttled,
            RefreshOutcome.NoAccount,
            RefreshOutcome.NoNetwork,
            RefreshOutcome.Failed(null),
            RefreshOutcome.Confirmed(AccountStatus.APPROVED)
        )
        val decisions = listOf(AccessDecision.Allowed) +
            BlockReason.entries.map { AccessDecision.Blocked(it) }

        for (decision in decisions) {
            for (outcome in outcomes) {
                when (val result = state(decision, lastOutcome = outcome)) {
                    is AccessSessionState.Degraded ->
                        assertTrue("$decision/$outcome degraded from a block", decision is AccessDecision.Allowed)
                    is AccessSessionState.Blocked ->
                        assertTrue("$decision/$outcome blocked from an allow", decision is AccessDecision.Blocked)
                    else -> Unit
                }
            }
        }
    }
}
