package se.optiqon.voice.domain.access

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The negative tests §3.14–§3.17 of the backend plan, over the one function that decides
 * whether an account may dictate.
 *
 * Every case here is a case where the app must say no. The gate runs on the device and is a
 * product boundary rather than a security boundary — but "pending testers cannot dictate" is
 * a requirement in its own right, and this is where it is proven.
 */
class AccessGateTest {

    private val uid = "uid-a"
    private val grace = AccessGate.BETA_GRACE_MS

    /** A verdict recorded `ageMs` ago, on both clocks, as seen from [NOW_WALL]/[NOW_ELAPSED]. */
    private fun snapshot(
        status: AccountStatus,
        ageMs: Long = 0L,
        uid: String = this.uid
    ) = AccessSnapshot(
        uid = uid,
        status = status,
        verifiedAtWallMs = NOW_WALL - ageMs,
        verifiedAtElapsedMs = NOW_ELAPSED - ageMs
    )

    private fun decide(
        snapshot: AccessSnapshot?,
        activeUid: String? = uid,
        nowWallMs: Long = NOW_WALL,
        nowElapsedMs: Long = NOW_ELAPSED
    ) = AccessGate.evaluate(snapshot, activeUid, nowWallMs, nowElapsedMs, grace)

    // §3.14 — pending / rejected / revoked cannot dictate, online or offline.
    // Note that the gate never sees the network: it cannot be talked round by losing it.

    @Test
    fun `a pending account is blocked`() {
        assertEquals(
            AccessDecision.Blocked(BlockReason.AWAITING_APPROVAL),
            decide(snapshot(AccountStatus.PENDING))
        )
    }

    @Test
    fun `a rejected account is blocked`() {
        assertEquals(
            AccessDecision.Blocked(BlockReason.REJECTED),
            decide(snapshot(AccountStatus.REJECTED))
        )
    }

    @Test
    fun `a revoked account is blocked`() {
        assertEquals(
            AccessDecision.Blocked(BlockReason.REVOKED),
            decide(snapshot(AccountStatus.REVOKED))
        )
    }

    @Test
    fun `a fresh account with no verdict yet is blocked`() {
        assertEquals(
            AccessDecision.Blocked(BlockReason.NOT_REGISTERED),
            decide(snapshot(AccountStatus.NEW))
        )
    }

    @Test
    fun `an approved account within grace may dictate`() {
        assertEquals(AccessDecision.Allowed, decide(snapshot(AccountStatus.APPROVED, ageMs = grace / 2)))
    }

    // §3.15 — expired grace blocks; a seen revocation blocks at once and airplane mode
    // does not undo it.

    @Test
    fun `an approved verdict older than the grace period no longer allows dictation`() {
        assertEquals(
            AccessDecision.Blocked(BlockReason.GRACE_EXPIRED),
            decide(snapshot(AccountStatus.APPROVED, ageMs = grace + 1))
        )
    }

    @Test
    fun `grace ends exactly at the configured window, not a millisecond after`() {
        assertEquals(
            AccessDecision.Blocked(BlockReason.GRACE_EXPIRED),
            decide(snapshot(AccountStatus.APPROVED, ageMs = grace))
        )
    }

    @Test
    fun `a revocation seen a moment ago blocks even though the approval is still fresh`() {
        // The revoked verdict is the newest thing we know. Grace applies to approval only;
        // there is no window in which a known revocation is ignored.
        assertEquals(
            AccessDecision.Blocked(BlockReason.REVOKED),
            decide(snapshot(AccountStatus.REVOKED, ageMs = 0L))
        )
    }

    @Test
    fun `going offline does not restore a revoked account`() {
        // Offline is not an input to this function, which is the point: there is nothing to
        // switch off that turns a REVOKED snapshot back into an allowance.
        val offline = decide(snapshot(AccountStatus.REVOKED, ageMs = 5L * 60L * 1000L))

        assertEquals(AccessDecision.Blocked(BlockReason.REVOKED), offline)
    }

    // §3.16 — the wall clock moved backwards shortens grace; it must never extend it.

    @Test
    fun `a wall clock set backwards spends the grace instead of extending it`() {
        val fresh = snapshot(AccountStatus.APPROVED, ageMs = 60L * 1000L)

        // "Now" is a day before the verdict was recorded, which is impossible; the reading is
        // therefore untrusted and a server check is forced.
        val decision = decide(fresh, nowWallMs = NOW_WALL - 24L * 60L * 60L * 1000L)

        assertEquals(AccessDecision.Blocked(BlockReason.GRACE_EXPIRED), decision)
    }

    @Test
    fun `a reboot that restarts elapsedRealtime spends the grace`() {
        val fresh = snapshot(AccountStatus.APPROVED, ageMs = 60L * 1000L)

        assertEquals(
            AccessDecision.Blocked(BlockReason.GRACE_EXPIRED),
            decide(fresh, nowElapsedMs = 0L)
        )
    }

    @Test
    fun `the older of the two clocks decides, so a stalled wall clock cannot buy time`() {
        val snapshot = AccessSnapshot(
            uid = uid,
            status = AccountStatus.APPROVED,
            verifiedAtWallMs = NOW_WALL, // wall clock claims no time has passed
            verifiedAtElapsedMs = NOW_ELAPSED - (grace + 1)
        )

        assertEquals(AccessDecision.Blocked(BlockReason.GRACE_EXPIRED), decide(snapshot))
    }

    // §3.17 — a new uid starts with no grace at all, and a cached token proves nothing.

    @Test
    fun `a newly signed-in account has no verdict and therefore no grace`() {
        assertEquals(
            AccessDecision.Blocked(BlockReason.NOT_REGISTERED),
            decide(snapshot = null)
        )
    }

    @Test
    fun `another account's approval is not inherited by the account signed in`() {
        // The snapshot is a valid, fresh approval — for somebody else. Reusing it would be
        // exactly the leak per-uid storage exists to prevent.
        val other = snapshot(AccountStatus.APPROVED, uid = "uid-b")

        assertEquals(
            AccessDecision.Blocked(BlockReason.NOT_REGISTERED),
            decide(other, activeUid = "uid-a")
        )
    }

    @Test
    fun `being signed out blocks even with an approved verdict on file`() {
        // Holding a valid cached ID token is what "signed in" means to Firebase; it says
        // nothing about approval, and the gate does not consult it.
        assertEquals(
            AccessDecision.Blocked(BlockReason.NOT_REGISTERED),
            decide(snapshot(AccountStatus.APPROVED), activeUid = null)
        )
    }

    private companion object {
        const val NOW_WALL = 1_800_000_000_000L
        const val NOW_ELAPSED = 5_000_000L
    }
}
