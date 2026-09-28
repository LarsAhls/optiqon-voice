package se.optiqon.voice.domain.feedback

import se.optiqon.voice.data.db.dao.OutboxDao
import se.optiqon.voice.data.db.entity.OutboxEntry
import se.optiqon.voice.data.db.entity.OutboxState
import se.optiqon.voice.domain.access.AuthGateway
import java.util.UUID

/** The ceilings the rules enforce, repeated here so the user hears about them before sending. */
object FeedbackLimits {
    const val MAX_TEXT_CHARS = 4000
    const val MAX_TITLE_CHARS = 80
    const val MAX_IMAGES_PER_MESSAGE = 3
    const val MAX_ACTIVE_PER_CASE = 10
    const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
    val IMAGE_MIME = setOf("image/png", "image/jpeg", "image/webp")
}

/** A screenshot already prepared and saved under the owner's attachments directory. */
data class PreparedImage(val file: String, val mime: String, val bytes: Int)

sealed interface ComposeOutcome {
    data class Queued(val caseId: String) : ComposeOutcome
    data object Empty : ComposeOutcome
    data object TooLong : ComposeOutcome
    data object TooManyImages : ComposeOutcome
    data object SignedOut : ComposeOutcome

    /** Pending, rejected, revoked, or a grace period that has run out. Nothing is queued. */
    data object NotApproved : ComposeOutcome
}

/**
 * Puts a new case, a message or a screenshot deletion on the outbox, in the order it must be
 * sent: the case or message first, then each of its screenshots.
 *
 * Only an approved account may queue. A row written for a pending or revoked account would sit
 * on the device waiting to be sent under a verdict that has already said no.
 */
class CaseComposer(
    private val outbox: OutboxDao,
    private val auth: AuthGateway,
    private val approval: ApprovalCheck,
    private val scheduler: OutboxScheduler,
    private val build: FeedbackBuildInfo,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { UUID.randomUUID().toString() }
) {

    suspend fun createCase(text: String, images: List<PreparedImage>): ComposeOutcome {
        val clean = text.trim()
        refusal(clean, images)?.let { return it }
        val uid = auth.currentUid ?: return ComposeOutcome.SignedOut
        if (!approval.isApproved()) return ComposeOutcome.NotApproved

        val caseId = newId()
        enqueue(uid, listOf(CasePayload.CreateCase(caseId, titleOf(clean), bodyOf(clean))) +
            images.map { upload(caseId, null, it) })
        return ComposeOutcome.Queued(caseId)
    }

    suspend fun addMessage(caseId: String, text: String, images: List<PreparedImage>): ComposeOutcome {
        val clean = text.trim()
        refusal(clean, images)?.let { return it }
        val uid = auth.currentUid ?: return ComposeOutcome.SignedOut
        if (!approval.isApproved()) return ComposeOutcome.NotApproved

        val messageId = newId()
        enqueue(uid, listOf(CasePayload.Message(caseId, messageId, clean)) +
            images.map { upload(caseId, messageId, it) })
        return ComposeOutcome.Queued(caseId)
    }

    /**
     * Takes one of the owner's screenshots down. An upload of it that has not left the device
     * yet is dropped instead of sent, so the bytes never reach the bucket at all.
     */
    suspend fun deleteScreenshot(caseId: String, aid: String): ComposeOutcome {
        val uid = auth.currentUid ?: return ComposeOutcome.SignedOut
        if (!approval.isApproved()) return ComposeOutcome.NotApproved
        outbox.pendingFor(uid)
            .filter { it.kind == CaseOutboxPayloads.KIND_UPLOAD }
            .filter { (CaseOutboxPayloads.decode(it.kind, it.payload) as? CasePayload.Upload)?.aid == aid }
            .forEach { outbox.discard(it.id) }
        enqueue(uid, listOf(CasePayload.Tombstone(caseId, aid)))
        return ComposeOutcome.Queued(caseId)
    }

    /**
     * Sends a message saved by the earlier, text-only feedback screen as a case of its own. The
     * old row is removed only once the new one is on the queue, and only at the user's word.
     */
    suspend fun sendLegacy(row: OutboxEntry): ComposeOutcome {
        val uid = auth.currentUid ?: return ComposeOutcome.SignedOut
        if (row.ownerUid != uid || row.kind != FeedbackPayloads.KIND) return ComposeOutcome.Empty
        if (!approval.isApproved()) return ComposeOutcome.NotApproved
        val legacy = runCatching { FeedbackPayloads.decode(row.payload) }.getOrNull()
            ?: return ComposeOutcome.Empty
        val text = buildString {
            append(legacy.message.trim())
            legacy.contact?.let { append("\n\n").append(it) }
        }
        val caseId = newId()
        enqueue(uid, listOf(CasePayload.CreateCase(caseId, titleOf(text), bodyOf(text))))
        outbox.discard(row.id)
        return ComposeOutcome.Queued(caseId)
    }

    private fun refusal(text: String, images: List<PreparedImage>): ComposeOutcome? = when {
        text.isEmpty() -> ComposeOutcome.Empty
        text.length > FeedbackLimits.MAX_TEXT_CHARS -> ComposeOutcome.TooLong
        images.size > FeedbackLimits.MAX_IMAGES_PER_MESSAGE -> ComposeOutcome.TooManyImages
        images.any { it.mime !in FeedbackLimits.IMAGE_MIME || it.bytes !in 1..FeedbackLimits.MAX_IMAGE_BYTES } ->
            ComposeOutcome.TooManyImages
        else -> null
    }

    private fun upload(caseId: String, messageId: String?, image: PreparedImage) =
        CasePayload.Upload(caseId, messageId, newId(), image.file, image.mime, image.bytes)

    private suspend fun enqueue(uid: String, payloads: List<CasePayload>) {
        val base = now()
        payloads.forEachIndexed { i, payload ->
            outbox.insert(
                OutboxEntry(
                    id = newId(),
                    ownerUid = uid,
                    kind = CaseOutboxPayloads.kindOf(payload),
                    payload = CaseOutboxPayloads.encode(payload),
                    // One millisecond apart, so the queue's createdAt order is the send order.
                    createdAtMs = base + i,
                    state = OutboxState.PENDING
                )
            )
        }
        scheduler.schedule()
    }

    private fun titleOf(text: String): String {
        val line = text.lineSequence().first().trim()
        return if (line.length <= FeedbackLimits.MAX_TITLE_CHARS) line
        else line.take(FeedbackLimits.MAX_TITLE_CHARS - 1).trimEnd() + "…"
    }

    /** The text as written, then the build it was written on — enough to reproduce a report. */
    private fun bodyOf(text: String): String =
        "$text\n\n— ${build.appVersion} · Android ${build.androidSdk} · ${build.deviceModel}"
}
