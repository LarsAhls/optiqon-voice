package se.optiqon.voice.domain.access

/**
 * The server's verdict on an account. Only [APPROVED] may dictate, and only the server
 * can produce that value — the app never derives it from a successful sign-in.
 */
enum class AccountStatus {
    /** Signed in, but no users/ document has been read yet. */
    NEW,
    PENDING,
    APPROVED,
    REJECTED,
    REVOKED
}

/**
 * One uid together with the epoch it is currently being acted on in.
 *
 * The uid alone cannot answer "is this answer still relevant?", because signing out and back
 * in as the same person produces the same uid while every in-flight request from before the
 * sign-out has become stale. [token] is drawn afresh for each epoch, so equality of the pair
 * — not of the uid — is what says the identity has not moved on.
 *
 * The token is deliberately opaque and unordered. Nothing compares two tokens for age; the
 * only question ever asked of it is whether it is the same one. That is also why it may be
 * random rather than a counter: a counter restarting at zero in a fresh process would collide
 * with a value persisted by the previous process, and a collision here would let a stale
 * sequence number silently veto a legitimate write.
 */
data class IdentityEpoch(val uid: String, val token: Long)

/**
 * The last thing the server told us about one account, with both clocks recorded.
 *
 * `verifiedAtWallMs` is a wall-clock instant so it survives a reboot; `verifiedAtElapsedMs`
 * is `SystemClock.elapsedRealtime()`, which cannot be set by the user. Keeping both is what
 * lets [AccessGate] notice a clock that moved.
 *
 * [epochToken] and [seq] record which epoch produced this verdict and where in that epoch's
 * order it sat. They exist so a slow answer cannot overwrite a newer one; see
 * [AccessRepository.record].
 */
data class AccessSnapshot(
    val uid: String,
    val status: AccountStatus,
    val verifiedAtWallMs: Long,
    val verifiedAtElapsedMs: Long,
    val epochToken: Long = 0L,
    val seq: Long = 0L
)

enum class BlockReason {
    /** No account, or the snapshot belongs to a different uid than the one signed in. */
    NOT_REGISTERED,
    AWAITING_APPROVAL,
    REJECTED,
    REVOKED,

    /** Approved once, but the offline grace period has run out. */
    GRACE_EXPIRED
}

sealed interface AccessDecision {
    data object Allowed : AccessDecision
    data class Blocked(val reason: BlockReason) : AccessDecision
}

/**
 * What a refresh attempt actually learned.
 *
 * The distinction this type exists to make is between *a verdict* and *no verdict*. Before it,
 * a missing network, a signed-out account and a backend fault all returned null, which the UI
 * then had to render as something — and the something it chose was "still waiting for
 * approval". Only [Confirmed] is a statement about the account.
 */
sealed interface RefreshOutcome {

    /** The server answered. A missing document is still an answer: [AccountStatus.NEW]. */
    data class Confirmed(val status: AccountStatus) : RefreshOutcome

    /** No uid, or the address is not verified yet. Nothing was asked of the server. */
    data object NoAccount : RefreshOutcome

    /** The device could not reach the server. Explicitly not a verdict on the account. */
    data object NoNetwork : RefreshOutcome

    /** The network works but Firebase did not answer usefully. Also not a verdict. */
    data class Failed(val cause: Throwable?) : RefreshOutcome

    /** A recent server read is still good, so nothing was asked again. */
    data object Throttled : RefreshOutcome
}

/** Why a refresh was attempted. Used for throttling policy, not for the verdict itself. */
enum class RefreshTrigger {
    /** The app came to the foreground. */
    FOREGROUND,

    /** The device regained connectivity. */
    CONNECTIVITY,

    /** A different account signed in, or the current one signed out. Never throttled. */
    UID_CHANGE,

    /** The user pressed a refresh control. Never throttled. */
    MANUAL,

    /** Something is about to be allowed or refused, so the verdict had better be current. */
    POINT_OF_ACTION
}

/** Why the session is degraded but still usable. Never a full-screen state. */
enum class DegradedKind {
    /** The last refresh failed for a reason other than the network. Dictation still works. */
    VERIFICATION_FAILED,

    /** The device is offline. Cloud transcription is unreachable; the account is fine. */
    OFFLINE
}

/**
 * What the UI renders, as opposed to what the gate decides.
 *
 * [AccessDecision] answers "may this account dictate"; this answers "what should the person
 * be looking at". They are separate because the answers genuinely differ: an approved account
 * whose last check-in failed may dictate *and* must be told that verification is stale.
 */
sealed interface AccessSessionState {

    /** Nothing is known yet. Conservative: never treated as approval. */
    data object Verifying : AccessSessionState

    data object SignedOut : AccessSessionState

    /** Approved, server-confirmed, recently checked. */
    data object Active : AccessSessionState

    /** Approved and inside grace, with something worth saying about it. */
    data class Degraded(val kind: DegradedKind) : AccessSessionState

    data class Blocked(val reason: BlockReason) : AccessSessionState
}
