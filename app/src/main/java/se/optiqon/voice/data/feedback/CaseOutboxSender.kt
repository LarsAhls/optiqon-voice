package se.optiqon.voice.data.feedback

import se.optiqon.voice.data.db.dao.OutboxDao
import se.optiqon.voice.data.db.entity.OutboxEntry
import se.optiqon.voice.data.storage.UserScopedStorage
import se.optiqon.voice.domain.access.AuthGateway
import se.optiqon.voice.domain.feedback.ApprovalGeneration
import se.optiqon.voice.domain.feedback.AttachmentStore
import se.optiqon.voice.domain.feedback.CaseOutboxPayloads
import se.optiqon.voice.domain.feedback.CasePayload
import se.optiqon.voice.domain.feedback.CaseRemote
import se.optiqon.voice.domain.feedback.RemoteResult
import se.optiqon.voice.domain.feedback.RemoteState
import se.optiqon.voice.domain.feedback.StoreResult
import se.optiqon.voice.domain.feedback.WithdrawalStatus
import se.optiqon.voice.domain.sync.OutboxSender
import se.optiqon.voice.domain.sync.SendFailure
import java.io.File

/**
 * Sends one case-channel row, and makes every step safe to repeat.
 *
 * A retry is the normal case, not the exception: the worker can be killed between a write
 * landing and the row being marked sent. So each step first does its write and, when refused,
 * asks whether the thing it was trying to make is already there and already the caller's. Only
 * a refusal that survives that question parks the row.
 *
 * A case and a message go in two phases, `submitted` then `accepted`, and a row is sent only
 * once both are done. A retry after either phase reads where the write stands and carries on
 * from there; an accepted one is success and is never written again.
 *
 * A row travels only under the approval generation it was queued in. One stamped with another,
 * or with none, is not tried at all: it is held for its owner, who may send it again under the
 * current approval ([se.optiqon.voice.domain.feedback.CaseComposer.releaseHeld]).
 *
 * The device's generation can be behind the server's: a revoke and a reapproval can both land
 * between the local check and the write. So a refusal that would otherwise be final is first
 * held up against the approval the server holds right now ([CaseRemote.approvalOf]). An
 * approval that has moved holds the row; an unchanged one leaves the refusal as it was, so a
 * closed, foreign or full case is not dressed up as a reapproval.
 *
 * A withdrawal is not stamped and needs no current approval (M3=A): it can only ever stop or
 * take back the owner's own `submitted` leftovers. It is sent once the server has reconciled
 * it, and the server's verdict is recorded on the row ([outbox]) before the row is marked sent.
 */
class CaseOutboxSender(
    private val remote: CaseRemote,
    private val store: AttachmentStore,
    private val files: UserScopedStorage,
    private val auth: AuthGateway,
    private val generation: ApprovalGeneration,
    private val outbox: OutboxDao
) : OutboxSender {

    override suspend fun send(entry: OutboxEntry): SendFailure? {
        val uid = entry.ownerUid
        // Belt and braces: the flush already filters by owner. A row must never travel under
        // another account's token, so the sender checks for itself.
        if (auth.currentUid != uid) return SendFailure.Transient("Another account is signed in.")
        val payload = CaseOutboxPayloads.decode(entry.kind, entry.payload)
            ?: return SendFailure.Permanent("This row could not be read and was not sent.")

        val gen = if (payload is CasePayload.Stamped) {
            val current = generation.current()
                ?: return SendFailure.Transient("The account's approval is not known yet.")
            if (payload.generation != current) {
                return SendFailure.Held("Written under an earlier approval; waiting for you to send it again.")
            }
            current
        } else 0L

        return when (payload) {
            is CasePayload.CreateCase -> twoPhase(
                uid, gen,
                create = { remote.createCase(uid, payload.caseId, payload.title, payload.body, gen) },
                state = { remote.caseState(uid, payload.caseId) },
                finalize = { remote.finalizeCase(payload.caseId, gen) }
            )

            is CasePayload.Message -> twoPhase(
                uid, gen,
                create = { remote.addMessage(uid, payload.caseId, payload.messageId, payload.body, gen) },
                state = { remote.messageState(uid, payload.caseId, payload.messageId) },
                finalize = { remote.finalizeMessage(payload.caseId, payload.messageId, gen) }
            )

            is CasePayload.Upload -> upload(uid, payload, gen)

            is CasePayload.Tombstone -> when (val r = remote.tombstoneAttachment(payload.caseId, payload.aid)) {
                RemoteResult.Ok -> null
                is RemoteResult.Denied -> SendFailure.Permanent(r.message)
                is RemoteResult.Failed -> r.failure
            }

            is CasePayload.Withdrawal -> withdraw(entry, uid, payload)
        }
    }

    /**
     * Intent first, verdict second. Every step starts by reading where the intent stands, so a
     * lost acknowledgement, a retry or a restart simply picks up from there: an intent is never
     * written twice, and a verdict already given is recorded rather than asked for again.
     */
    private suspend fun withdraw(entry: OutboxEntry, uid: String, p: CasePayload.Withdrawal): SendFailure? {
        // The rules let only a verified account write or read its intents; waiting is right,
        // the owner's word stands until it can be delivered.
        if (!auth.isEmailVerified) return SendFailure.Transient("Waiting for a verified e-mail address.")
        when (val before = remote.withdrawalStatus(uid, p.targetId)) {
            is WithdrawalStatus.Reconciled -> return reconciled(entry, p, before.outcome)
            WithdrawalStatus.Pending -> return AWAITING_SERVER
            null -> return SendFailure.Transient("Could not check the withdrawal.")
            WithdrawalStatus.Missing -> Unit
        }
        val refusal = when (val r = remote.requestWithdrawal(uid, p.target, p.targetId, p.caseId)) {
            RemoteResult.Ok -> null
            is RemoteResult.Failed -> return r.failure
            is RemoteResult.Denied -> r.message
        }
        return when (val after = remote.withdrawalStatus(uid, p.targetId)) {
            is WithdrawalStatus.Reconciled -> reconciled(entry, p, after.outcome)
            WithdrawalStatus.Pending -> AWAITING_SERVER
            null -> SendFailure.Transient(refusal ?: "Could not check the withdrawal.")
            // Written and gone again cannot happen: no one may delete an intent. Refused and
            // absent is a refusal for good -- no account document to hold it, for one.
            WithdrawalStatus.Missing -> SendFailure.Permanent(refusal ?: "The withdrawal was not recorded.")
        }
    }

    private suspend fun reconciled(entry: OutboxEntry, p: CasePayload.Withdrawal, outcome: String): SendFailure? {
        if (p.outcome != outcome) {
            outbox.updatePendingPayload(entry.id, CaseOutboxPayloads.encode(p.copy(outcome = outcome)))
        }
        return null
    }

    /**
     * After a refusal: how the row goes if the approval it was stamped under no longer stands on
     * the server, or null when it still does and the refusal is about something else.
     */
    private suspend fun approvalMoved(uid: String, gen: Long, message: String): SendFailure? {
        val now = remote.approvalOf(uid) ?: return SendFailure.Transient(message)
        return if (!now.approved || now.generation != gen) SendFailure.Held(message) else null
    }

    private suspend fun twoPhase(
        uid: String,
        gen: Long,
        create: suspend () -> RemoteResult,
        state: suspend () -> RemoteState?,
        finalize: suspend () -> RemoteResult
    ): SendFailure? {
        when (val created = create()) {
            RemoteResult.Ok -> Unit
            is RemoteResult.Failed -> return created.failure
            // Refused. Either an earlier attempt already wrote it -- a create over an existing
            // document is an update, and the rules refuse that -- or it is truly refused: by an
            // approval that moved since the row was checked (held), or for good.
            is RemoteResult.Denied -> when (state()) {
                RemoteState.ACCEPTED -> return null
                RemoteState.SUBMITTED -> Unit
                RemoteState.NOT_MINE ->
                    return approvalMoved(uid, gen, created.message) ?: SendFailure.Permanent(created.message)
                null -> return SendFailure.Transient(created.message)
            }
        }
        return when (val finalized = finalize()) {
            RemoteResult.Ok -> null
            is RemoteResult.Failed -> finalized.failure
            // Accepting was refused: the approval moved since the row was checked (held for the
            // owner), or it was withdrawn, closed or never ours under an unchanged approval (final).
            is RemoteResult.Denied -> when (state()) {
                RemoteState.ACCEPTED -> null
                null -> SendFailure.Transient(finalized.message)
                else -> approvalMoved(uid, gen, finalized.message) ?: SendFailure.Permanent(finalized.message)
            }
        }
    }

    private companion object {
        /** Not a fault: the intent is in place and the server has not given its verdict yet. */
        val AWAITING_SERVER = SendFailure.Transient("Waiting for Optiqon to confirm the withdrawal.")
    }

    private suspend fun upload(uid: String, p: CasePayload.Upload, gen: Long): SendFailure? {
        val path = AttachmentStore.path(uid, p.caseId, p.aid)
        val file = File(files.attachmentsDir(uid), p.file)
        if (!files.isReadableBy(file, uid) ||
            file.canonicalFile.parentFile != files.attachmentsDir(uid).canonicalFile
        ) {
            return SendFailure.Permanent("The screenshot is outside this account's storage.")
        }
        if (!file.exists()) {
            // Gone locally: fine if an earlier attempt finished and removed its copy.
            return when (store.exists(path)) {
                true -> null
                false -> SendFailure.Permanent("The screenshot is no longer on this device.")
                null -> SendFailure.Transient("Could not check the screenshot.")
            }
        }
        val bytes = file.readBytes()
        if (bytes.size != p.bytes) return SendFailure.Permanent("The screenshot changed after it was queued.")

        when (val r = remote.commitAttachment(uid, p.caseId, p.messageId, p.aid, p.bytes, gen)) {
            RemoteResult.Ok -> Unit
            is RemoteResult.Denied -> return approvalMoved(uid, gen, r.message) ?: SendFailure.Permanent(r.message)
            is RemoteResult.Failed -> return r.failure
        }
        if (auth.currentUid != uid) return SendFailure.Transient("Another account is signed in.")

        val stored = when (val put = store.put(path, bytes, p.mime)) {
            StoreResult.Ok -> null
            // The object is already there (a create over an existing object is refused), or
            // the screenshot was taken down before its bytes arrived. Only the first is success.
            StoreResult.Denied -> when (store.exists(path)) {
                true -> null
                false -> approvalMoved(uid, gen, "The screenshot was refused.")
                    ?: SendFailure.Permanent("The screenshot was refused.")
                null -> SendFailure.Transient("Could not check the screenshot.")
            }
            is StoreResult.Failed -> put.failure
        }
        if (stored == null) file.delete()
        return stored
    }
}
