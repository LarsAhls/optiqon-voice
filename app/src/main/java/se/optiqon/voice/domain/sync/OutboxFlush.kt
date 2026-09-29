package se.optiqon.voice.domain.sync

import se.optiqon.voice.data.db.dao.OutboxDao
import se.optiqon.voice.data.db.entity.OutboxState
import se.optiqon.voice.domain.access.AuthGateway
import se.optiqon.voice.domain.feedback.ApprovalCheck
import se.optiqon.voice.domain.feedback.CaseOutboxPayloads
import se.optiqon.voice.domain.feedback.FeedbackPayloads

/**
 * One pass over the signed-in account's queue: the body of [OutboxWorker], without WorkManager.
 *
 * - Nothing is sent for an account that is not approved. Its rows stay pending, untouched --
 *   except removals (M3=A): a withdrawal can only ever take back the owner's own unaccepted
 *   words, and a screenshot removal only ever makes the owner's own picture unreadable, so a
 *   revoked or pending account may still say "take this back" (M6: once online, it goes).
 * - Rows saved by the earlier text-only feedback screen are never sent and never changed here.
 *   They leave the device only when their owner chooses to send them.
 * - Rows are sent oldest first. Once a row of a case fails, the rest of that case waits: a
 *   screenshot must not go ahead of the case or message it belongs to.
 * - A withdrawal goes first of all, is never held behind its case, and holds nothing back when
 *   it fails: its verdict may be a while coming, and the rest of the case need not wait for it.
 * - A screenshot removal goes next and is never held. It is only queued for a case that has
 *   left the device, with the screenshot's own upload already dropped, so it waits for nothing —
 *   and a stuck message of the same case must not keep a picture readable.
 * - Each row is looked up again just before it is sent. A row taken back while the run was under
 *   way — a screenshot removed before it left — is not sent from a stale list.
 * - The queue is read again once a pass is done, and whatever was added meanwhile is sent in the
 *   same run. A screenshot removed while its upload was inside the sender is queued exactly then:
 *   its tombstone goes out right after that upload has returned, never before it and never
 *   waiting for some later run to be started by something else. The run ends only on a pass
 *   that finds nothing new; [OutboxWorker] covers the moment after that last look.
 * - A row that failed after it was taken back asks for no retry: there is nothing left to retry.
 * - If the account changes mid-run, the run stops.
 * - Rows held because approval was withdrawn ([OutboxState.HELD]) are never sent from here, and
 *   neither is anything queued after them for the same case. A held row that was already inside
 *   the sender when the hold came stays held whether the send then failed or succeeded: the
 *   outcome is written only if the row is still pending, in one statement, so a late answer
 *   cannot undo the hold. A success after the hold may mean the remote has the row; explicit
 *   Send sends it again and relies on the remote accepting a repeat.
 */
class OutboxFlush(
    private val outbox: OutboxDao,
    private val auth: AuthGateway,
    private val approval: ApprovalCheck,
    private val sender: OutboxSender,
    private val remoteEnabled: Boolean
) {

    enum class Result { DONE, RETRY }

    suspend fun run(): Result {
        if (!remoteEnabled) return Result.DONE
        val uid = auth.currentUid ?: return Result.DONE
        val approved = approval.isApproved()

        var retry = false
        val heldCases = outbox.heldFor(uid)
            .mapNotNull { CaseOutboxPayloads.decode(it.kind, it.payload)?.caseId }
            .toMutableSet()
        val attempted = mutableSetOf<String>()
        while (true) {
            val queue = OutboxPolicy.flushable(outbox.pendingFor(uid), uid)
                .filter { it.kind != FeedbackPayloads.KIND && it.id !in attempted }
                .filter { approved || it.kind in CaseOutboxPayloads.REMOVALS }
                .sortedBy {
                    when (it.kind) {
                        CaseOutboxPayloads.KIND_WITHDRAW -> 0
                        CaseOutboxPayloads.KIND_DELETE -> 1
                        else -> 2
                    }
                }
            if (queue.isEmpty()) break
            for (entry in queue) {
                if (auth.currentUid != uid) return Result.DONE
                attempted += entry.id
                if (outbox.byId(entry.id)?.state != OutboxState.PENDING) continue
                val removal = entry.kind in CaseOutboxPayloads.REMOVALS

                val caseId = if (entry.kind in CaseOutboxPayloads.KINDS) {
                    CaseOutboxPayloads.decode(entry.kind, entry.payload)?.caseId
                } else null
                if (caseId != null && caseId in heldCases && !removal) continue

                val failure = sender.send(entry)
                val updated = if (failure == null) {
                    entry.copy(state = OutboxState.SENT, attempts = entry.attempts + 1, lastError = null)
                } else OutboxPolicy.afterFailure(entry, failure)
                if (outbox.completeIfPending(entry.id, updated.state, updated.attempts, updated.lastError) == 0) {
                    // Held or taken back while it was inside the sender: the later word stands.
                    if (outbox.byId(entry.id)?.state == OutboxState.HELD) caseId?.let { heldCases += it }
                    continue
                }
                if (failure != null) {
                    retry = retry || OutboxPolicy.shouldRetry(failure)
                    if (!removal) caseId?.let { heldCases += it }
                }
            }
        }
        return if (retry) Result.RETRY else Result.DONE
    }
}
