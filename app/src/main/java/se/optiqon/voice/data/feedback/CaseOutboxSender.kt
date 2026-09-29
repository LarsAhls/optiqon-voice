package se.optiqon.voice.data.feedback

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
 */
class CaseOutboxSender(
    private val remote: CaseRemote,
    private val store: AttachmentStore,
    private val files: UserScopedStorage,
    private val auth: AuthGateway,
    private val generation: ApprovalGeneration
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
                create = { remote.createCase(uid, payload.caseId, payload.title, payload.body, gen) },
                state = { remote.caseState(uid, payload.caseId) },
                finalize = { remote.finalizeCase(payload.caseId, gen) }
            )

            is CasePayload.Message -> twoPhase(
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
        }
    }

    private suspend fun twoPhase(
        create: suspend () -> RemoteResult,
        state: suspend () -> RemoteState?,
        finalize: suspend () -> RemoteResult
    ): SendFailure? {
        when (val created = create()) {
            RemoteResult.Ok -> Unit
            is RemoteResult.Failed -> return created.failure
            // Refused. Either an earlier attempt already wrote it -- a create over an existing
            // document is an update, and the rules refuse that -- or it is truly refused. A
            // refusal of something that is not there cannot be told apart from a stamp the
            // server has moved past before this device heard, so it is never final on its own:
            // the row waits for its owner rather than being lost.
            is RemoteResult.Denied -> when (state()) {
                RemoteState.ACCEPTED -> return null
                RemoteState.SUBMITTED -> Unit
                RemoteState.NOT_MINE -> return SendFailure.Held(created.message)
                null -> return SendFailure.Transient(created.message)
            }
        }
        return when (val finalized = finalize()) {
            RemoteResult.Ok -> null
            is RemoteResult.Failed -> finalized.failure
            // Accepting was refused while the submitted copy stands: the approval moved, or the
            // owner withdrew it, since the row was checked. Never final on its own -- the owner
            // decides, as for any row written under an approval that has since changed.
            is RemoteResult.Denied -> when (state()) {
                RemoteState.ACCEPTED -> null
                null -> SendFailure.Transient(finalized.message)
                else -> SendFailure.Held(finalized.message)
            }
        }
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
            is RemoteResult.Denied -> return SendFailure.Permanent(r.message)
            is RemoteResult.Failed -> return r.failure
        }
        if (auth.currentUid != uid) return SendFailure.Transient("Another account is signed in.")

        val stored = when (val put = store.put(path, bytes, p.mime)) {
            StoreResult.Ok -> null
            // The object is already there (a create over an existing object is refused), or
            // the screenshot was taken down before its bytes arrived. Only the first is success.
            StoreResult.Denied -> when (store.exists(path)) {
                true -> null
                false -> SendFailure.Permanent("The screenshot was refused.")
                null -> SendFailure.Transient("Could not check the screenshot.")
            }
            is StoreResult.Failed -> put.failure
        }
        if (stored == null) file.delete()
        return stored
    }
}
