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

/** Firestore, as far as the feedback channel is concerned. */
interface CaseRemote {

    suspend fun createCase(uid: String, caseId: String, title: String, body: String): RemoteResult

    /**
     * True when the case exists and is [uid]'s. False when it is absent: the rules refuse a
     * get on a case that does not exist, so a refusal is read as "not there". Null when the
     * question could not be asked.
     */
    suspend fun caseIsMine(uid: String, caseId: String): Boolean?

    suspend fun addMessage(uid: String, caseId: String, messageId: String, body: String): RemoteResult

    /** As [caseIsMine], for one of the owner's own messages. */
    suspend fun messageIsMine(uid: String, caseId: String, messageId: String): Boolean?

    /**
     * Reservation, quota, attachment document and the counters on case and message, in one
     * transaction. Idempotent: an attachment that already exists is success.
     */
    suspend fun commitAttachment(
        uid: String,
        caseId: String,
        messageId: String?,
        aid: String,
        maxBytes: Int
    ): RemoteResult

    /** The one-way delete flag, and the slot given back. Idempotent. */
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
