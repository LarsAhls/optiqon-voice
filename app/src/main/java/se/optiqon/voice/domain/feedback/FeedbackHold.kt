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
 *    withdrawn. This also covers a device that stored the revocation before this code existed.
 *
 * Only the account the verdict is about is touched. What happens to the held rows afterwards
 * is the owner's choice: [CaseComposer.releaseHeld] or [CaseComposer.discardHeld].
 */
class FeedbackHold(private val outbox: OutboxDao) : ServerVerdictListener {

    override suspend fun beforeRecord(uid: String, previous: AccountStatus?, status: AccountStatus) {
        val withdrawn = status != AccountStatus.APPROVED
        val restored = status == AccountStatus.APPROVED && previous != null && previous != AccountStatus.APPROVED
        if (withdrawn || restored) outbox.holdPendingFor(uid)
    }
}
