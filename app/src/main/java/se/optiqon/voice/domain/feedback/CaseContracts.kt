package se.optiqon.voice.domain.feedback

import se.optiqon.voice.domain.sync.SendFailure

/**
 * The case-based feedback channel, expressed without Firebase so the rules of the road can be
 * tested with fakes.
 *
 * A case is the opening text plus its screenshots; messages and status changes follow on its
 * timeline. Every write the device makes travels through the outbox, so a case written on a
 * train reaches Optiqon when the phone next has a network, and never under another account.
 */

/**
 * @property bucketUrl the one bucket feedback screenshots live in. Never the project default:
 *   google-services.json names a default bucket that does not exist.
 * @property remoteEnabled false until the feedback rules are published and probed. While
 *   false, a case is queued on the device and nothing reaches Firestore or Storage.
 */
data class FeedbackConfig(val bucketUrl: String, val remoteEnabled: Boolean)

/** Whether the signed-in account may use the feedback channel at all. */
fun interface ApprovalCheck {
    suspend fun isApproved(): Boolean
}

/** Asks for the outbox to be flushed when the network allows. */
fun interface OutboxScheduler {
    fun schedule()
}

data class FeedbackCase(
    val id: String,
    val title: String,
    val body: String,
    val status: String,
    val createdAtMs: Long?,
    val lastActivityAtMs: Long?,
    val activeAttachmentCount: Int,
    val closed: Boolean
)

data class CaseEvent(
    val id: String,
    val type: String,
    val body: String?,
    val fromOwner: Boolean,
    val toStatus: String?,
    val createdAtMs: Long?
)

data class CaseAttachment(
    val id: String,
    val messageId: String?,
    val deleted: Boolean,
    val createdAtMs: Long?
)

sealed interface RemoteResult {
    data object Ok : RemoteResult

    /** The rules said no. Whether that is final depends on what the caller finds next. */
    data class Denied(val message: String) : RemoteResult

    data class Failed(val failure: SendFailure) : RemoteResult
}

/**
 * Where one of the owner's own writes stands on the server.
 *
 * A case or message is written in two steps: `submitted`, then `accepted`. Only an accepted one
 * exists as far as anyone else is concerned -- an admin cannot see it before, nothing can hang
 * off it before, and the server's retention sweep may remove a submitted one that was withdrawn.
 * Accepted is final: no rule lets it go back, and no withdrawal can remove it.
 */
enum class RemoteState {
    /** Absent, or someone else's: the rules refuse a get on a document that does not exist. */
    NOT_MINE,
    SUBMITTED,
    ACCEPTED
}

/**
 * The account's approval generation as last verified by the server, or null when there is no
 * verdict on this device. Stamped on every case, message and screenshot at the moment it is
 * queued; the rules refuse a stamp that is not the account's current one.
 */
fun interface ApprovalGeneration {
    suspend fun current(): Long?
}

/** The account's approval as the server holds it at the moment of asking. */
data class ServerApproval(val approved: Boolean, val generation: Long)

/** Firestore, as far as the feedback channel is concerned. */
interface CaseRemote {

    /**
     * The account's approval read from the server itself, never from a cache. Null when the
     * question could not be asked. Asked after a refusal only, to tell an approval that moved
     * under a row from a refusal that has nothing to do with approval.
     */
    suspend fun approvalOf(uid: String): ServerApproval?

    /** Phase one: the case, `submitted`, stamped with [generation]. */
    suspend fun createCase(uid: String, caseId: String, title: String, body: String, generation: Long): RemoteResult

    /**
     * Phase two, idempotent: `submitted` becomes `accepted`, and an already accepted case is
     * success. Read and write in one transaction, so a lost acknowledgement costs nothing.
     */
    suspend fun finalizeCase(caseId: String, generation: Long): RemoteResult

    /** Where the case stands. Null when the question could not be asked. */
    suspend fun caseState(uid: String, caseId: String): RemoteState?

    /** Phase one: the message alone, `submitted`. The case is not touched until phase two. */
    suspend fun addMessage(uid: String, caseId: String, messageId: String, body: String, generation: Long): RemoteResult

    /**
     * Phase two, idempotent: the message becomes `accepted` and the case's activity revision
     * moves on by one, in one transaction. An already accepted message is success and moves
     * nothing, so a retry never counts the same message twice.
     */
    suspend fun finalizeMessage(caseId: String, messageId: String, generation: Long): RemoteResult

    /** As [caseState], for one of the owner's own messages. */
    suspend fun messageState(uid: String, caseId: String, messageId: String): RemoteState?

    /**
     * Reservation, quota, attachment document and the counters on case and message, in one
     * transaction. Idempotent: an attachment that already exists is success.
     */
    suspend fun commitAttachment(
        uid: String,
        caseId: String,
        messageId: String?,
        aid: String,
        maxBytes: Int,
        generation: Long
    ): RemoteResult

    /**
     * The one-way delete flag, and the slot given back. Idempotent: a screenshot already taken
     * down, or never committed at all, is success.
     */
    suspend fun tombstoneAttachment(caseId: String, aid: String): RemoteResult

    suspend fun listCases(uid: String): List<FeedbackCase>

    suspend fun events(caseId: String): List<CaseEvent>

    suspend fun attachments(caseId: String): List<CaseAttachment>
}

sealed interface StoreResult {
    data object Ok : StoreResult
    data object Denied : StoreResult
    data class Failed(val failure: SendFailure) : StoreResult
}

/**
 * Screenshot bytes in the feedback bucket.
 *
 * There is deliberately no delete, no metadata update and no download URL. Taking a screenshot
 * down is a Firestore tombstone; the bytes are the server's to remove. A download URL would be
 * a bearer link that outlives the tombstone, so reads go through the rules every time.
 */
interface AttachmentStore {

    suspend fun put(path: String, bytes: ByteArray, mime: String): StoreResult

    /** True when the object is there and readable by the caller, false when it is not. */
    suspend fun exists(path: String): Boolean?

    suspend fun getBytes(path: String, maxBytes: Long): ByteArray?

    companion object {
        fun path(uid: String, caseId: String, aid: String) = "case-attachments/$uid/$caseId/$aid"
    }
}
