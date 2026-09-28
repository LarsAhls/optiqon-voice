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
 * - Nothing is sent for an account that is not approved. Its rows stay pending, untouched.
 * - Rows saved by the earlier text-only feedback screen are never sent and never changed here.
 *   They leave the device only when their owner chooses to send them.
 * - Rows are sent oldest first. Once a row of a case fails, the rest of that case waits: a
 *   screenshot must not go ahead of the case or message it belongs to.
 * - A screenshot removal goes first and is never held. It is only queued for a case that has
 *   left the device, with the screenshot's own upload already dropped, so it waits for nothing —
 *   and a stuck message of the same case must not keep a picture readable.
 * - Each row is looked up again just before it is sent. A row taken back while the run was under
 *   way — a screenshot removed before it left — is not sent from a stale list.
 * - If the account changes mid-run, the run stops.
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
        if (!approval.isApproved()) return Result.DONE

        var retry = false
        val heldCases = mutableSetOf<String>()
        val queue = OutboxPolicy.flushable(outbox.pendingFor(uid), uid)
            .sortedBy { if (it.kind == CaseOutboxPayloads.KIND_DELETE) 0 else 1 }
        for (entry in queue) {
            if (entry.kind == FeedbackPayloads.KIND) continue
            if (auth.currentUid != uid) return Result.DONE
            if (outbox.byId(entry.id)?.state != OutboxState.PENDING) continue
            val removal = entry.kind == CaseOutboxPayloads.KIND_DELETE

            val caseId = if (entry.kind in CaseOutboxPayloads.KINDS) {
                CaseOutboxPayloads.decode(entry.kind, entry.payload)?.caseId
            } else null
            if (caseId != null && caseId in heldCases && !removal) continue

            when (val failure = sender.send(entry)) {
                null -> outbox.updateState(entry.id, OutboxState.SENT, entry.attempts + 1, null)
                else -> {
                    val updated = OutboxPolicy.afterFailure(entry, failure)
                    outbox.updateState(entry.id, updated.state, updated.attempts, updated.lastError)
                    retry = retry || OutboxPolicy.shouldRetry(failure)
                    if (!removal) caseId?.let { heldCases += it }
                }
            }
        }
        return if (retry) Result.RETRY else Result.DONE
    }
}
