package se.optiqon.voice.domain.access

/**
 * Decides, without touching Firebase or the Android framework, whether the account that is
 * signed in right now may dictate.
 *
 * The gate is a product boundary, not a security boundary. It runs on the device, so a rooted
 * phone can lie to it. The security boundary is `firestore.rules`, which has no grace period.
 * What this class guarantees is that a pending, rejected or revoked account cannot dictate,
 * that a snapshot belonging to someone else is never mistaken for the signed-in account, and
 * that a moved clock shortens the grace period rather than extending it.
 */
object AccessGate {

    /**
     * The beta policy: Lars decided D6 = 72 h on 2026-09-09, for a closed group of at most ten
     * known testers. It is still passed in through [AccessConfig] rather than read here by the
     * call sites that enforce it, because a wider release is expected to want a different value
     * and the enforcement path should not have to change when it does.
     */
    const val BETA_GRACE_MS: Long = 72L * 60L * 60L * 1000L

    /**
     * @param snapshot the last server verdict we hold, or null if we have never had one
     * @param activeUid the uid Firebase Auth reports right now, or null when signed out
     * @param nowWallMs `System.currentTimeMillis()`
     * @param nowElapsedMs `SystemClock.elapsedRealtime()`
     * @param graceMs how long an approved verdict may be reused without server contact
     */
    fun evaluate(
        snapshot: AccessSnapshot?,
        activeUid: String?,
        nowWallMs: Long,
        nowElapsedMs: Long,
        graceMs: Long
    ): AccessDecision {
        if (activeUid == null || snapshot == null || snapshot.uid != activeUid) {
            return AccessDecision.Blocked(BlockReason.NOT_REGISTERED)
        }
        return when (snapshot.status) {
            AccountStatus.NEW -> AccessDecision.Blocked(BlockReason.NOT_REGISTERED)
            AccountStatus.PENDING -> AccessDecision.Blocked(BlockReason.AWAITING_APPROVAL)
            AccountStatus.REJECTED -> AccessDecision.Blocked(BlockReason.REJECTED)
            AccountStatus.REVOKED -> AccessDecision.Blocked(BlockReason.REVOKED)
            AccountStatus.APPROVED ->
                if (isWithinGrace(snapshot, nowWallMs, nowElapsedMs, graceMs)) {
                    AccessDecision.Allowed
                } else {
                    AccessDecision.Blocked(BlockReason.GRACE_EXPIRED)
                }
        }
    }

    /**
     * Two clocks, read conservatively.
     *
     * A negative age on either clock means the reading is untrustworthy: the wall clock was set
     * backwards, or the device rebooted and `elapsedRealtime()` restarted below the stored value.
     * Neither is proof of foul play, but both make the stored age meaningless, so the grace is
     * treated as spent and a server check is forced. When both readings look sane, the larger
     * age wins — moving either clock can only shorten the window, never lengthen it.
     */
    private fun isWithinGrace(
        snapshot: AccessSnapshot,
        nowWallMs: Long,
        nowElapsedMs: Long,
        graceMs: Long
    ): Boolean {
        val wallAge = nowWallMs - snapshot.verifiedAtWallMs
        val elapsedAge = nowElapsedMs - snapshot.verifiedAtElapsedMs
        if (wallAge < 0 || elapsedAge < 0) return false
        return maxOf(wallAge, elapsedAge) < graceMs
    }
}
