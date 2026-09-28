package se.optiqon.voice.data.feedback

import se.optiqon.voice.data.db.entity.OutboxEntry
import se.optiqon.voice.data.storage.UserScopedStorage
import se.optiqon.voice.domain.access.AuthGateway
import se.optiqon.voice.domain.feedback.AttachmentStore
import se.optiqon.voice.domain.feedback.CaseOutboxPayloads
import se.optiqon.voice.domain.feedback.CasePayload
import se.optiqon.voice.domain.feedback.CaseRemote
import se.optiqon.voice.domain.feedback.RemoteResult
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
 */
class CaseOutboxSender(
    private val remote: CaseRemote,
    private val store: AttachmentStore,
    private val files: UserScopedStorage,
    private val auth: AuthGateway
) : OutboxSender {

    override suspend fun send(entry: OutboxEntry): SendFailure? {
        val uid = entry.ownerUid
        // Belt and braces: the flush already filters by owner. A row must never travel under
        // another account's token, so the sender checks for itself.
        if (auth.currentUid != uid) return SendFailure.Transient("Another account is signed in.")
        val payload = CaseOutboxPayloads.decode(entry.kind, entry.payload)
            ?: return SendFailure.Permanent("This row could not be read and was not sent.")

        return when (payload) {
            is CasePayload.CreateCase -> settle(
                remote.createCase(uid, payload.caseId, payload.title, payload.body)
            ) { remote.caseIsMine(uid, payload.caseId) }

            is CasePayload.Message -> settle(
                remote.addMessage(uid, payload.caseId, payload.messageId, payload.body)
            ) { remote.messageIsMine(uid, payload.caseId, payload.messageId) }

            is CasePayload.Upload -> upload(uid, payload)

            is CasePayload.Tombstone -> when (val r = remote.tombstoneAttachment(payload.caseId, payload.aid)) {
                RemoteResult.Ok -> null
                is RemoteResult.Denied -> SendFailure.Permanent(r.message)
                is RemoteResult.Failed -> r.failure
            }
        }
    }

    private suspend fun settle(result: RemoteResult, isMine: suspend () -> Boolean?): SendFailure? =
        when (result) {
            RemoteResult.Ok -> null
            is RemoteResult.Failed -> result.failure
            // Refused. Either it was already written by an earlier attempt — a create over an
            // existing document is an update, and the rules refuse that — or it is truly refused.
            is RemoteResult.Denied -> when (isMine()) {
                true -> null
                false -> SendFailure.Permanent(result.message)
                null -> SendFailure.Transient(result.message)
            }
        }

    private suspend fun upload(uid: String, p: CasePayload.Upload): SendFailure? {
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

        when (val r = remote.commitAttachment(uid, p.caseId, p.messageId, p.aid, p.bytes)) {
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
