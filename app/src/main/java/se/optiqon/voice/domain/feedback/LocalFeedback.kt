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
    val createdAtMs: Long,
    /** Held when approval was withdrawn; it waits for the owner to send or discard it. */
    val held: Boolean = false
)

/**
 * Where one screenshot of a case stands, as far as the device can truthfully say.
 *
 * A refusal is never shown as waiting: [UPLOAD_FAILED] and [REMOVE_FAILED] are rows the outbox
 * has parked and will not try again on its own. A removal is [REMOVING] until the tombstone is
 * confirmed written — only then does the screenshot leave the list.
 */
enum class ShotState { UPLOADING, UPLOAD_FAILED, AVAILABLE, REMOVING, REMOVE_FAILED }

/**
 * Whether the picture itself may be drawn. Once its owner has asked for it to go, it is not
 * shown again — not while the removal is on its way, and not when it failed. Only the words
 * saying where the removal stands remain.
 */
val ShotState.showsContent: Boolean
    get() = this != ShotState.REMOVING && this != ShotState.REMOVE_FAILED

/** One screenshot of a case. [file] is the local copy's name while it is still on the device. */
data class ShotStatus(val aid: String, val messageId: String?, val file: String?, val state: ShotState)

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
                createdAtMs = row.createdAtMs,
                held = row.state == OutboxState.HELD
            )
        }.sortedByDescending { it.createdAtMs }
    }

    /**
     * How many of the account's rows wait for their owner's word because approval was
     * withdrawn — openings, messages and screenshots alike.
     */
    fun heldCount(rows: List<OutboxEntry>, uid: String): Int =
        rows.count { it.ownerUid == uid && it.state == OutboxState.HELD }

    /**
     * The account's delivered withdrawals the server answered with "already received": the
     * report had been accepted before the discard reached it, and stays with Optiqon. Their row
     * ids, for the screen to say so once and then let go of.
     */
    fun alreadyReceived(rows: List<OutboxEntry>, uid: String): List<String> =
        rows.filter { it.ownerUid == uid && it.state == OutboxState.SENT && it.kind == CaseOutboxPayloads.KIND_WITHDRAW }
            .filter {
                (CaseOutboxPayloads.decode(it.kind, it.payload) as? CasePayload.Withdrawal)?.outcome ==
                    WithdrawalOutcome.IGNORED_ACCEPTED
            }
            .map { it.id }

    fun legacy(rows: List<OutboxEntry>, uid: String): List<LegacyNote> =
        rows.filter { it.ownerUid == uid && it.kind == FeedbackPayloads.KIND && it.state != OutboxState.SENT }
            .mapNotNull { row ->
                // Gson fills a missing field with null behind Kotlin's back; the copy into a
                // non-null String is what throws, so it sits inside the catch.
                runCatching { LegacyNote(row.id, FeedbackPayloads.decode(row.payload).message.trim(), row.createdAtMs) }
                    .getOrNull()
            }
            .sortedByDescending { it.createdAtMs }

    /**
     * Every screenshot of [caseId] still to be shown, and where each one stands.
     *
     * [rows] may include sent rows: a sent tombstone is the confirmation that the screenshot is
     * gone, and so is [remote] reporting it deleted. Until one of the two, a removal is only
     * pending. An upload still on the device outranks a remote document that says it exists,
     * since the document is written before the bytes.
     */
    fun shots(rows: List<OutboxEntry>, uid: String, caseId: String, remote: List<CaseAttachment>): List<ShotStatus> {
        val mine = rows.filter { it.ownerUid == uid && it.kind in CaseOutboxPayloads.KINDS }
            .mapNotNull { row -> CaseOutboxPayloads.decode(row.kind, row.payload)?.let { row to it } }
            .filter { (_, payload) -> payload.caseId == caseId }
        val tombstones = mine.filter { it.second is CasePayload.Tombstone }
            .groupBy({ (it.second as CasePayload.Tombstone).aid }, { it.first.state })
        val uploads = mine.filter { (row, payload) -> payload is CasePayload.Upload && row.state != OutboxState.SENT }
            .associate { (row, payload) -> (payload as CasePayload.Upload).aid to (row.state to payload) }

        fun confirmedGone(aid: String) = tombstones[aid]?.contains(OutboxState.SENT) == true
        fun removal(aid: String): ShotState? {
            val states = tombstones[aid] ?: return null
            return if (OutboxState.PENDING in states) ShotState.REMOVING else ShotState.REMOVE_FAILED
        }
        fun uploadState(aid: String): ShotState? = uploads[aid]?.first?.let {
            if (it == OutboxState.BLOCKED) ShotState.UPLOAD_FAILED else ShotState.UPLOADING
        }

        val fromRemote = remote.filterNot { it.deleted || confirmedGone(it.id) }.map { a ->
            ShotStatus(a.id, a.messageId, uploads[a.id]?.second?.file, removal(a.id) ?: uploadState(a.id) ?: ShotState.AVAILABLE)
        }
        val known = remote.map { it.id }.toSet()
        val fromDevice = uploads.filterKeys { it !in known && !confirmedGone(it) }.map { (aid, entry) ->
            val upload = entry.second
            ShotStatus(aid, upload.messageId, upload.file, removal(aid) ?: uploadState(aid)!!)
        }
        return fromRemote + fromDevice
    }

    /**
     * Screenshot copies in the account's attachments directory that nothing will ever send: no
     * unsent upload names them. A copy prepared for a draft the process did not live to send
     * ends up here.
     */
    fun orphanedFiles(rows: List<OutboxEntry>, uid: String, present: List<String>): List<String> {
        val referenced = rows
            .filter { it.ownerUid == uid && it.kind == CaseOutboxPayloads.KIND_UPLOAD && it.state != OutboxState.SENT }
            .mapNotNull { (CaseOutboxPayloads.decode(it.kind, it.payload) as? CasePayload.Upload)?.file }
            .toSet()
        return present.filterNot { it in referenced }
    }

    /** Screenshots of [caseId] still on their way up; shown with the case until they land. */
    fun pendingUploads(rows: List<OutboxEntry>, uid: String, caseId: String): List<CasePayload.Upload> =
        rows.filter { it.ownerUid == uid && it.kind == CaseOutboxPayloads.KIND_UPLOAD && it.state != OutboxState.SENT }
            .mapNotNull { CaseOutboxPayloads.decode(it.kind, it.payload) as? CasePayload.Upload }
            .filter { it.caseId == caseId }
}
