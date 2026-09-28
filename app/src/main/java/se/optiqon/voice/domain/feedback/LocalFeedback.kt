package se.optiqon.voice.domain.feedback

import se.optiqon.voice.data.db.entity.OutboxEntry
import se.optiqon.voice.data.db.entity.OutboxState

/** A case written on this device whose opening has not reached Optiqon yet. */
data class QueuedCase(
    val caseId: String,
    val title: String,
    val images: List<CasePayload.Upload>,
    /** The outbox gave up on it; it waits for the user rather than for the network. */
    val blocked: Boolean,
    val createdAtMs: Long
)

/** A message saved by the earlier, text-only screen. It is only ever sent at the user's word. */
data class LegacyNote(val id: String, val message: String, val createdAtMs: Long)

/**
 * What the outbox holds for one account, as the feedback screen shows it.
 *
 * Rows of every other account are skipped here rather than filtered by the caller: the screen
 * shows the signed-in account's own queue and nothing else, whatever the database holds.
 */
object LocalFeedback {

    fun queuedCases(rows: List<OutboxEntry>, uid: String): List<QueuedCase> {
        val mine = rows.filter { it.ownerUid == uid && it.state != OutboxState.SENT }
        val uploads = mine.filter { it.kind == CaseOutboxPayloads.KIND_UPLOAD }
            .mapNotNull { CaseOutboxPayloads.decode(it.kind, it.payload) as? CasePayload.Upload }
            .groupBy { it.caseId }
        return mine.filter { it.kind == CaseOutboxPayloads.KIND_CREATE }.mapNotNull { row ->
            val create = CaseOutboxPayloads.decode(row.kind, row.payload) as? CasePayload.CreateCase
                ?: return@mapNotNull null
            QueuedCase(
                caseId = create.caseId,
                title = create.title,
                images = uploads[create.caseId].orEmpty().filter { it.messageId == null },
                blocked = row.state == OutboxState.BLOCKED,
                createdAtMs = row.createdAtMs
            )
        }.sortedByDescending { it.createdAtMs }
    }

    fun legacy(rows: List<OutboxEntry>, uid: String): List<LegacyNote> =
        rows.filter { it.ownerUid == uid && it.kind == FeedbackPayloads.KIND && it.state != OutboxState.SENT }
            .mapNotNull { row ->
                // Gson fills a missing field with null behind Kotlin's back; the copy into a
                // non-null String is what throws, so it sits inside the catch.
                runCatching { LegacyNote(row.id, FeedbackPayloads.decode(row.payload).message.trim(), row.createdAtMs) }
                    .getOrNull()
            }
            .sortedByDescending { it.createdAtMs }

    /** Screenshots of [caseId] still on their way up; shown with the case until they land. */
    fun pendingUploads(rows: List<OutboxEntry>, uid: String, caseId: String): List<CasePayload.Upload> =
        rows.filter { it.ownerUid == uid && it.kind == CaseOutboxPayloads.KIND_UPLOAD && it.state != OutboxState.SENT }
            .mapNotNull { CaseOutboxPayloads.decode(it.kind, it.payload) as? CasePayload.Upload }
            .filter { it.caseId == caseId }
}
