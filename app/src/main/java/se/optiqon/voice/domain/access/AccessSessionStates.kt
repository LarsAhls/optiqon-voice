package se.optiqon.voice.domain.access

/**
 * Turns "what the gate decided" plus "what the last check-in learned" into the one thing the
 * UI should show. Pure, so the whole rev. 11 matrix is a plain JVM test.
 *
 * The rule that shapes it: a [AccessSessionState.Degraded] state is never a full screen and a
 * [AccessSessionState.Blocked] state is never a banner. Blocking is a statement about the
 * account, which only the server can make; degrading is a statement about our own ability to
 * check, which is our problem and not the user's fault.
 */
object AccessSessionStates {

    fun evaluate(
        decision: AccessDecision,
        signedIn: Boolean,
        hasSnapshot: Boolean,
        lastOutcome: RefreshOutcome?
    ): AccessSessionState {
        if (!signedIn) return AccessSessionState.SignedOut

        if (decision is AccessDecision.Blocked) {
            // No stored verdict and no answer yet is not a refusal, it is an unfinished
            // question. Rendering it as "not registered" would accuse a user who has done
            // nothing wrong, at the one moment they are most likely to be watching.
            if (!hasSnapshot &&
                decision.reason == BlockReason.NOT_REGISTERED &&
                lastOutcome !is RefreshOutcome.Confirmed
            ) {
                return AccessSessionState.Verifying
            }
            return AccessSessionState.Blocked(decision.reason)
        }

        return when (lastOutcome) {
            // Offline is about the network, not the account. Dictation will not work because
            // cloud transcription is unreachable — the banner must not imply otherwise, and
            // must not claim anything is queued, because nothing is.
            is RefreshOutcome.NoNetwork -> AccessSessionState.Degraded(DegradedKind.OFFLINE)

            // The network works and Firebase does not. Dictation still works, because the
            // account is approved and inside its grace window; only our confidence is stale.
            is RefreshOutcome.Failed -> AccessSessionState.Degraded(DegradedKind.VERIFICATION_FAILED)

            else -> AccessSessionState.Active
        }
    }

    /**
     * Whether dictation may proceed *on account grounds*.
     *
     * Deliberately true for [DegradedKind.VERIFICATION_FAILED]: a temporary Firebase or auth
     * fault, with a working network and a reachable speech service, must not stop someone
     * dictating inside a grace window they legitimately hold. It is false for
     * [DegradedKind.OFFLINE] only because the transcription cannot reach anywhere, which is a
     * different sentence entirely and has to be worded as one.
     */
    fun allowsDictation(state: AccessSessionState): Boolean = when (state) {
        is AccessSessionState.Active -> true
        is AccessSessionState.Degraded -> state.kind == DegradedKind.VERIFICATION_FAILED
        else -> false
    }
}
