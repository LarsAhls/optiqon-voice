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
 * The last thing the server told us about one account, with both clocks recorded.
 *
 * `verifiedAtWallMs` is a wall-clock instant so it survives a reboot; `verifiedAtElapsedMs`
 * is `SystemClock.elapsedRealtime()`, which cannot be set by the user. Keeping both is what
 * lets [AccessGate] notice a clock that moved.
 */
data class AccessSnapshot(
    val uid: String,
    val status: AccountStatus,
    val verifiedAtWallMs: Long,
    val verifiedAtElapsedMs: Long
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
