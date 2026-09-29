package se.optiqon.voice.domain.feedback

import se.optiqon.voice.data.db.dao.OutboxDao
import se.optiqon.voice.domain.access.AccountStatus
import se.optiqon.voice.domain.access.ServerVerdictListener

/**
 * Parks an account's queued reports when the server stops approving it, so that approval
 * returning does not send them on its own.
 *
 * Two moments hold, and either is enough:
 *
 *  - a verdict other than APPROVED — revoked, rejected, back to pending;
 *  - an APPROVED verdict replacing one that was not. Nothing can be queued while the account
 *    is not approved, so a row still waiting at that moment is one written before approval was
 *    withdrawn. This also covers a device that stored the revocation before this code existed;
 *  - an APPROVED verdict whose approval generation differs from the stored one. The account was
 *    revoked and reapproved between two reads, and this device never saw the revocation. The
 *    rows would be refused by the server anyway -- their stamp is the old generation -- so they
 *    are parked where the owner can see them rather than left to fail.
 *
 * Only the account the verdict is about is touched. What happens to the held rows afterwards
 * is the owner's choice: [CaseComposer.releaseHeld] or [CaseComposer.discardHeld].
 */
class FeedbackHold(private val outbox: OutboxDao) : ServerVerdictListener {

    override suspend fun beforeRecord(uid: String, previous: AccountStatus?, status: AccountStatus) {
        beforeRecord(uid, previous, status, null, 0L)
    }

    override suspend fun beforeRecord(
        uid: String,
        previous: AccountStatus?,
        status: AccountStatus,
        previousGeneration: Long?,
        generation: Long
    ) {
        val withdrawn = status != AccountStatus.APPROVED
        val restored = status == AccountStatus.APPROVED && previous != null && previous != AccountStatus.APPROVED
        val reissued = status == AccountStatus.APPROVED && previousGeneration != null && previousGeneration != generation
        if (withdrawn || restored || reissued) outbox.holdPendingFor(uid)
    }
}
