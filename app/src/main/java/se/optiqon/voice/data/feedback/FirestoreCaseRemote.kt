package se.optiqon.voice.data.feedback

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreException.Code
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import se.optiqon.voice.domain.feedback.CaseAttachment
import se.optiqon.voice.domain.feedback.CaseEvent
import se.optiqon.voice.domain.feedback.CaseRemote
import se.optiqon.voice.domain.feedback.FeedbackCase
import se.optiqon.voice.domain.feedback.FeedbackLimits
import se.optiqon.voice.domain.feedback.RemoteResult
import se.optiqon.voice.domain.sync.SendFailure
import javax.inject.Provider

/**
 * The case channel in Firestore, shaped document for document after `firestore.rules`.
 *
 * Every write here has a twin in the rules and must match it key for key; the rules refuse a
 * write with one field too many. [CaseDocuments] builds the maps so a JVM test can hold them
 * against the rules' key lists without a Firestore instance.
 */
class FirestoreCaseRemote(
    private val firestoreProvider: Provider<FirebaseFirestore>
) : CaseRemote {

    private val firestore get() = firestoreProvider.get()

    private fun case(caseId: String) = firestore.collection("cases").document(caseId)
    private fun event(caseId: String, id: String) = case(caseId).collection("events").document(id)
    private fun attachment(caseId: String, aid: String) = case(caseId).collection("attachments").document(aid)
    private fun reservation(uid: String, aid: String) =
        firestore.collection("users").document(uid).collection("uploads").document(aid)
    private fun quota(uid: String) =
        firestore.collection("users").document(uid).collection("quota").document("attachments")

    override suspend fun createCase(uid: String, caseId: String, title: String, body: String) = write {
        case(caseId).set(CaseDocuments.newCase(uid, title, body)).await()
        RemoteResult.Ok
    }

    override suspend fun caseIsMine(uid: String, caseId: String): Boolean? =
        ownedBy(uid, "ownerUid") { case(caseId).get().await() }

    override suspend fun addMessage(uid: String, caseId: String, messageId: String, body: String) = write {
        firestore.batch()
            .set(event(caseId, messageId), CaseDocuments.newMessage(uid, body))
            .update(case(caseId), CaseDocuments.touch())
            .commit()
            .await()
        RemoteResult.Ok
    }

    override suspend fun messageIsMine(uid: String, caseId: String, messageId: String): Boolean? =
        ownedBy(uid, "actorUid") { event(caseId, messageId).get().await() }

    override suspend fun commitAttachment(
        uid: String,
        caseId: String,
        messageId: String?,
        aid: String,
        maxBytes: Int
    ): RemoteResult = write {
        firestore.runTransaction { tx ->
            if (tx.get(attachment(caseId, aid)).exists()) return@runTransaction RemoteResult.Ok

            val c = tx.get(case(caseId))
            if (c.contains("closedAt")) return@runTransaction RemoteResult.Denied("The case is closed.")
            val active = c.getLong("activeAttachmentCount") ?: 0L
            val opening = c.getLong("attachmentCount") ?: 0L
            if (active >= FeedbackLimits.MAX_ACTIVE_PER_CASE) {
                return@runTransaction RemoteResult.Denied("The case already has ten screenshots.")
            }
            if (messageId == null && opening >= FeedbackLimits.MAX_IMAGES_PER_MESSAGE) {
                return@runTransaction RemoteResult.Denied("The case already has three screenshots.")
            }
            val e = messageId?.let { tx.get(event(caseId, it)) }
            val onMessage = e?.getLong("attachmentCount") ?: 0L
            if (e != null && onMessage >= FeedbackLimits.MAX_IMAGES_PER_MESSAGE) {
                return@runTransaction RemoteResult.Denied("The message already has three screenshots.")
            }

            val q = tx.get(quota(uid))
            val quotaWrite = CaseDocuments.quotaAfter(
                existing = if (q.exists()) CaseDocuments.Quota(
                    count = q.getLong("count") ?: 0L,
                    bytes = q.getLong("bytes") ?: 0L,
                    windowStartMs = q.getTimestamp("windowStart")?.toDate()?.time ?: 0L,
                    windowCount = q.getLong("windowCount") ?: 0L
                ) else null,
                aid = aid,
                maxBytes = maxBytes,
                nowMs = System.currentTimeMillis()
            )
            when (quotaWrite) {
                is CaseDocuments.QuotaWrite.Refused -> return@runTransaction quotaWrite.result
                is CaseDocuments.QuotaWrite.Create -> tx.set(quota(uid), quotaWrite.fields)
                is CaseDocuments.QuotaWrite.Update -> tx.update(quota(uid), quotaWrite.fields)
            }

            tx.set(reservation(uid, aid), CaseDocuments.reservation(caseId, maxBytes))
            tx.set(attachment(caseId, aid), CaseDocuments.attachment(uid, caseId, messageId, maxBytes))
            tx.update(case(caseId), CaseDocuments.caseSlotTaken(aid, active, opening, messageId == null))
            if (messageId != null) {
                tx.update(event(caseId, messageId), CaseDocuments.messageSlotTaken(aid, onMessage))
            }
            RemoteResult.Ok
        }.await()
    }

    override suspend fun tombstoneAttachment(caseId: String, aid: String): RemoteResult = write {
        firestore.runTransaction { tx ->
            val a = tx.get(attachment(caseId, aid))
            // Never committed: its upload was dropped before it left, and with the local copy
            // gone nothing can commit it later. There is nothing to take down.
            if (!a.exists()) return@runTransaction RemoteResult.Ok
            if (a.contains("deleteRequestedAt")) return@runTransaction RemoteResult.Ok
            val active = tx.get(case(caseId)).getLong("activeAttachmentCount") ?: 0L
            tx.update(attachment(caseId, aid), CaseDocuments.tombstone())
            tx.update(case(caseId), CaseDocuments.caseSlotReleased(aid, active))
            RemoteResult.Ok
        }.await()
    }

    override suspend fun listCases(uid: String): List<FeedbackCase> =
        firestore.collection("cases").whereEqualTo("ownerUid", uid).get().await()
            .documents.map { d ->
                FeedbackCase(
                    id = d.id,
                    title = d.getString("title").orEmpty(),
                    body = d.getString("body").orEmpty(),
                    status = d.getString("statusCache").orEmpty(),
                    createdAtMs = d.ms("createdAt"),
                    lastActivityAtMs = d.ms("lastActivityAt"),
                    activeAttachmentCount = (d.getLong("activeAttachmentCount") ?: 0L).toInt(),
                    closed = d.contains("closedAt")
                )
            }
            .sortedByDescending { it.lastActivityAtMs ?: Long.MAX_VALUE }

    override suspend fun events(caseId: String): List<CaseEvent> {
        val owner = case(caseId).get().await().getString("ownerUid")
        return case(caseId).collection("events").whereEqualTo("visibility", "public").get().await()
            .documents.map { d ->
                CaseEvent(
                    id = d.id,
                    type = d.getString("type").orEmpty(),
                    body = d.getString("body"),
                    fromOwner = owner != null && d.getString("actorUid") == owner,
                    toStatus = d.getString("toStatus"),
                    createdAtMs = d.ms("createdAt")
                )
            }
            .sortedBy { it.createdAtMs ?: Long.MAX_VALUE }
    }

    override suspend fun attachments(caseId: String): List<CaseAttachment> =
        case(caseId).collection("attachments").get().await()
            .documents.map { d ->
                CaseAttachment(
                    id = d.id,
                    messageId = d.getString("messageId"),
                    deleted = d.contains("deleteRequestedAt"),
                    createdAtMs = d.ms("createdAt")
                )
            }
            .sortedBy { it.createdAtMs ?: Long.MAX_VALUE }

    private fun DocumentSnapshot.ms(field: String): Long? = getTimestamp(field)?.toDate()?.time

    private suspend fun ownedBy(uid: String, field: String, read: suspend () -> DocumentSnapshot): Boolean? =
        try {
            withTimeout(TIMEOUT_MS) { read() }.let { it.exists() && it.getString(field) == uid }
        } catch (cancellation: TimeoutCancellationException) {
            null
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: FirebaseFirestoreException) {
            // A get on a case or message that does not exist is refused, not answered empty.
            if (failure.code == Code.PERMISSION_DENIED) false else null
        } catch (_: Exception) {
            null
        }

    private suspend fun write(block: suspend () -> RemoteResult): RemoteResult = try {
        withTimeout(TIMEOUT_MS) { block() }
    } catch (timeout: TimeoutCancellationException) {
        RemoteResult.Failed(SendFailure.Transient("Timed out."))
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: FirebaseFirestoreException) {
        if (failure.code == Code.PERMISSION_DENIED) RemoteResult.Denied(failure.message ?: "Refused.")
        else RemoteResult.Failed(FeedbackSendFailures.classify(failure.code, failure.message ?: failure.code.name))
    } catch (failure: Exception) {
        RemoteResult.Failed(SendFailure.Transient(failure.message ?: "Sending failed."))
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
    }
}

/**
 * The exact documents the case channel writes. Each map's keys are the rules' key list for that
 * write, no more; `CaseDocumentsTest` holds them to it.
 */
object CaseDocuments {
    const val QUOTA_MAX_COUNT = 50L
    const val QUOTA_MAX_BYTES = 104_857_600L
    const val WINDOW_MAX = 20L

    /** A little over the rules' hour, so a slow device clock does not ask for a window early. */
    const val WINDOW_MS = 62L * 60L * 1000L

    private val now get() = FieldValue.serverTimestamp()

    fun newCase(uid: String, title: String, body: String): Map<String, Any?> = mapOf(
        "ownerUid" to uid,
        "title" to title,
        "body" to body,
        "statusCache" to "Mottaget",
        "lastStatusEventId" to null,
        "attachmentCount" to 0L,
        "activeAttachmentCount" to 0L,
        "createdAt" to now,
        "updatedAt" to now,
        "lastActivityAt" to now
    )

    fun newMessage(uid: String, body: String): Map<String, Any?> = mapOf(
        "type" to "message",
        "visibility" to "public",
        "actorUid" to uid,
        "body" to body,
        "attachmentCount" to 0L,
        "createdAt" to now
    )

    fun touch(): Map<String, Any?> = mapOf("lastActivityAt" to now)

    fun reservation(caseId: String, maxBytes: Int): Map<String, Any?> = mapOf(
        "caseId" to caseId,
        "maxBytes" to maxBytes.toLong(),
        "createdAt" to now
    )

    fun attachment(uid: String, caseId: String, messageId: String?, maxBytes: Int): Map<String, Any?> = mapOf(
        "ownerUid" to uid,
        "caseId" to caseId,
        // Present even when null: the rules require the key.
        "messageId" to messageId,
        "maxBytes" to maxBytes.toLong(),
        "createdAt" to now
    )

    fun caseSlotTaken(aid: String, active: Long, opening: Long, onOpening: Boolean): Map<String, Any?> =
        buildMap {
            put("activeAttachmentCount", active + 1)
            if (onOpening) put("attachmentCount", opening + 1)
            put("attachmentFor", aid)
            put("lastActivityAt", now)
        }

    fun messageSlotTaken(aid: String, onMessage: Long): Map<String, Any?> = mapOf(
        "attachmentCount" to onMessage + 1,
        "attachmentFor" to aid
    )

    fun tombstone(): Map<String, Any?> = mapOf("deleteRequestedAt" to now)

    fun caseSlotReleased(aid: String, active: Long): Map<String, Any?> = mapOf(
        "activeAttachmentCount" to active - 1,
        "attachmentFor" to aid
    )

    data class Quota(val count: Long, val bytes: Long, val windowStartMs: Long, val windowCount: Long)

    sealed interface QuotaWrite {
        data class Create(val fields: Map<String, Any?>) : QuotaWrite
        data class Update(val fields: Map<String, Any?>) : QuotaWrite
        data class Refused(val result: RemoteResult) : QuotaWrite
    }

    /**
     * The quota document after one more screenshot, or why there is no room. The lifetime caps
     * are final; a full hourly window is not, and is reported as something to retry later.
     */
    fun quotaAfter(existing: Quota?, aid: String, maxBytes: Int, nowMs: Long): QuotaWrite {
        if (existing == null) return QuotaWrite.Create(
            mapOf(
                "count" to 1L,
                "bytes" to maxBytes.toLong(),
                "windowStart" to now,
                "windowCount" to 1L,
                "lastUploadId" to aid
            )
        )
        if (existing.count >= QUOTA_MAX_COUNT || existing.bytes + maxBytes > QUOTA_MAX_BYTES) {
            return QuotaWrite.Refused(RemoteResult.Denied("This account has used its screenshot allowance."))
        }
        val base = mapOf(
            "count" to existing.count + 1,
            "bytes" to existing.bytes + maxBytes,
            "lastUploadId" to aid
        )
        return when {
            existing.windowCount < WINDOW_MAX -> QuotaWrite.Update(base + ("windowCount" to existing.windowCount + 1))
            nowMs - existing.windowStartMs > WINDOW_MS ->
                QuotaWrite.Update(base + mapOf("windowStart" to now, "windowCount" to 1L))
            else -> QuotaWrite.Refused(
                RemoteResult.Failed(SendFailure.Transient("Too many screenshots this hour; trying again later."))
            )
        }
    }
}
