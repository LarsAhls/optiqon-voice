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

/** What became of the owner's choice about rows held when their approval was withdrawn. */
sealed interface HeldOutcome {
    /** [files] are local screenshot copies that nothing will send any more. */
    data class Done(val files: List<String>) : HeldOutcome
    data object NothingHeld : HeldOutcome
    data object SignedOut : HeldOutcome
    data object NotApproved : HeldOutcome
}

/**
 * Puts a new case, a message or a screenshot deletion on the outbox, in the order it must be
 * sent: the case or message first, then each of its screenshots.
 *
 * Only an approved account may queue. A row written for a pending or revoked account would sit
 * on the device waiting to be sent under a verdict that has already said no. Each case, message
 * and screenshot row carries the approval generation it was written under, and is sent only
 * under that one.
 */
class CaseComposer(
    private val outbox: OutboxDao,
    private val auth: AuthGateway,
    private val approval: ApprovalCheck,
    private val generation: ApprovalGeneration,
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

        val gen = generation.current()
        val caseId = newId()
        enqueue(uid, listOf(CasePayload.CreateCase(caseId, titleOf(clean), bodyOf(clean), gen)) +
            images.map { upload(caseId, null, it, gen) })
        return ComposeOutcome.Queued(caseId)
    }

    suspend fun addMessage(caseId: String, text: String, images: List<PreparedImage>): ComposeOutcome {
        val clean = text.trim()
        refusal(clean, images)?.let { return it }
        val uid = auth.currentUid ?: return ComposeOutcome.SignedOut
        if (!approval.isApproved()) return ComposeOutcome.NotApproved

        val gen = generation.current()
        val messageId = newId()
        enqueue(uid, listOf(CasePayload.Message(caseId, messageId, clean, gen)) +
            images.map { upload(caseId, messageId, it, gen) })
        return ComposeOutcome.Queued(caseId)
    }

    /**
     * Takes one of the owner's screenshots down. An upload of it that has not left the device
     * yet — waiting or refused — is dropped instead of sent, so its bytes never leave from here
     * on. An earlier removal of the same screenshot still queued is replaced, not doubled.
     *
     * A tombstone follows unless the case itself has not left the device: then nothing of the
     * screenshot can be on the server, and dropping the upload is the whole removal. Otherwise the
     * tombstone is queued even when the upload looked unsent, because that upload may be under
     * way right now; the tombstone is what makes sure it cannot stay readable.
     *
     * Neither needs approval (M3): a revoked or pending owner may still take their own picture
     * down. The removal is a local-first pending delete (M6): the screenshot is hidden at once,
     * the row is the durable intent, and it is called removed only once the server has taken
     * the tombstone.
     */
    suspend fun deleteScreenshot(caseId: String, aid: String): ComposeOutcome {
        val uid = auth.currentUid ?: return ComposeOutcome.SignedOut
        val unsent = outbox.all()
            .filter { it.ownerUid == uid && it.state != OutboxState.SENT && it.kind in CaseOutboxPayloads.KINDS }
            .mapNotNull { row -> CaseOutboxPayloads.decode(row.kind, row.payload)?.let { row to it } }
            .filter { (_, payload) -> payload.caseId == caseId }
        val caseOnDevice = unsent.any { (_, payload) -> payload is CasePayload.CreateCase }
        // No approval needed (M3): taking the owner's own screenshot down only ever removes, so a
        // revoked or pending owner may still do it, and the rules let it through the same way.
        unsent.filter { (_, payload) ->
            (payload as? CasePayload.Upload)?.aid == aid || (payload as? CasePayload.Tombstone)?.aid == aid
        }.forEach { (row, _) -> outbox.discard(row.id) }
        if (!caseOnDevice) enqueue(uid, listOf(CasePayload.Tombstone(caseId, aid)))
        return ComposeOutcome.Queued(caseId)
    }

    /**
     * Drops a screenshot's upload that never reached the server, and nothing else: no tombstone
     * is queued. For a closed case, where nothing may be taken down on the server but a parked
     * upload and its local copy must still be clearable. False when there was no such upload.
     */
    suspend fun discardUnsentUpload(caseId: String, aid: String): Boolean {
        val uid = auth.currentUid ?: return false
        val rows = outbox.all()
            .filter { it.ownerUid == uid && it.state != OutboxState.SENT && it.kind == CaseOutboxPayloads.KIND_UPLOAD }
            .filter { row ->
                (CaseOutboxPayloads.decode(row.kind, row.payload) as? CasePayload.Upload)
                    ?.let { it.caseId == caseId && it.aid == aid } == true
            }
        rows.forEach { outbox.discard(it.id) }
        return rows.isNotEmpty()
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
        enqueue(uid, listOf(CasePayload.CreateCase(caseId, titleOf(text), bodyOf(text), generation.current())))
        outbox.discard(row.id)
        return ComposeOutcome.Queued(caseId)
    }

    /** Removes a saved text-only message of the signed-in account, at the user's word. */
    suspend fun discardLegacy(id: String): Boolean {
        val uid = auth.currentUid ?: return false
        val row = outbox.byId(id) ?: return false
        if (row.ownerUid != uid || row.kind != FeedbackPayloads.KIND) return false
        outbox.discard(row.id)
        return true
    }

    /**
     * Takes back a case that has not left the device: its opening, its screenshots and anything
     * queued after it. Returns the screenshot files to remove, or null when the opening has
     * already been sent and there is nothing left to take back.
     *
     * No approval check: removing your own unsent words and pictures from your own phone is
     * always allowed, revoked or not.
     *
     * Every discarded opening and message is also withdrawn on the server, by id
     * ([withdrawalFor]), whether or not it looks tried: see [enqueueWithdrawn].
     */
    suspend fun discardQueuedCase(caseId: String): List<String>? {
        val uid = auth.currentUid ?: return null
        val rows = outbox.all().filter { it.ownerUid == uid && it.state != OutboxState.SENT }
            .filter { it.kind != CaseOutboxPayloads.KIND_WITHDRAW }
            .map { it to CaseOutboxPayloads.decode(it.kind, it.payload) }
            .filter { (_, payload) -> payload?.caseId == caseId }
        if (rows.none { (_, payload) -> payload is CasePayload.CreateCase }) return null
        val intents = rows.mapNotNull { (_, payload) -> payload?.let(::withdrawalFor) }
        enqueueWithdrawn(uid, intents, rows.map { (row, _) -> row.id })
        return rows.mapNotNull { (_, payload) -> (payload as? CasePayload.Upload)?.file }
    }

    /**
     * The owner chose to send what was held when their approval was withdrawn. Every held row
     * of the signed-in account goes back on the queue in its original order, and nobody else's.
     * Needs approval now: sending is exactly what a revoked account may not do.
     *
     * This is the one place a row's approval generation is changed: the owner's word, given
     * under the current approval, is what lets words written under an earlier one go out.
     */
    suspend fun releaseHeld(): HeldOutcome {
        val uid = auth.currentUid ?: return HeldOutcome.SignedOut
        if (!approval.isApproved()) return HeldOutcome.NotApproved
        val gen = generation.current() ?: return HeldOutcome.NotApproved
        outbox.heldFor(uid).forEach { row ->
            val payload = CaseOutboxPayloads.decode(row.kind, row.payload) as? CasePayload.Stamped
                ?: return@forEach
            if (payload.generation != gen) {
                outbox.updateHeldPayload(row.id, CaseOutboxPayloads.encode(payload.restamp(gen)))
            }
        }
        if (outbox.releaseHeldFor(uid) == 0) return HeldOutcome.NothingHeld
        scheduler.schedule()
        return HeldOutcome.Done(emptyList())
    }

    /**
     * The owner chose to throw away what was held. No approval needed: it is their own unsent
     * work on their own phone.
     *
     * A held case opening takes the whole case with it, as [discardQueuedCase] does. A held
     * message or screenshot of a case already on the server goes alone, and each screenshot
     * gets a removal queued, as [deleteScreenshot] does for a case that has left the device —
     * its upload may have been under way when approval was withdrawn. Returns the local
     * screenshot copies nothing will send any more, for the caller to delete.
     *
     * Every discarded opening and message is also withdrawn on the server by id
     * ([withdrawalFor], [enqueueWithdrawn]). An accepted one stays: the server answers
     * "already received".
     * Discarding never sends: nothing held goes out, now or after a later reapproval.
     */
    suspend fun discardHeld(): HeldOutcome {
        val uid = auth.currentUid ?: return HeldOutcome.SignedOut
        val held = outbox.heldFor(uid)
            .mapNotNull { row -> CaseOutboxPayloads.decode(row.kind, row.payload)?.let { row to it } }
        if (held.isEmpty()) return HeldOutcome.NothingHeld

        val unsent = outbox.all()
            .filter { it.ownerUid == uid && it.state != OutboxState.SENT }
            .filter { it.kind != CaseOutboxPayloads.KIND_WITHDRAW }
            .mapNotNull { row -> CaseOutboxPayloads.decode(row.kind, row.payload)?.let { row to it } }
        val onDevice = unsent.mapNotNull { (_, payload) -> (payload as? CasePayload.CreateCase)?.caseId }.toSet()
        val heldOpenings = held.mapNotNull { (_, payload) -> (payload as? CasePayload.CreateCase)?.caseId }.toSet()
        val wholeCases = unsent.filter { (_, payload) -> payload.caseId in heldOpenings }
        val alone = held.filter { (_, payload) -> payload.caseId !in heldOpenings }

        val discarded = wholeCases + alone
        val files = discarded.mapNotNull { (_, payload) -> (payload as? CasePayload.Upload)?.file }
        val intents = discarded.mapNotNull { (_, payload) -> withdrawalFor(payload) }
        val removals = alone.mapNotNull { (_, payload) -> payload as? CasePayload.Upload }
            .filter { it.caseId !in onDevice }
            .map { CasePayload.Tombstone(it.caseId, it.aid) }
        enqueueWithdrawn(uid, intents + removals, discarded.map { (row, _) -> row.id })
        return HeldOutcome.Done(files)
    }

    /**
     * The withdrawal a discarded opening or message gets: always, by id only. Nothing on the
     * row can say it never left -- `attempts` counts only sends that have returned, so a first
     * send may be inside the sender, unrecorded, at the very moment of the discard. The server
     * answers `absent` for one that never arrived, `withdrawn` for a `submitted` one, and
     * "already received" for one accepted; and once the intent is there the rules refuse a
     * late create or finalize under that id. A screenshot has its own removal, the tombstone.
     */
    private fun withdrawalFor(payload: CasePayload): CasePayload.Withdrawal? = when (payload) {
        is CasePayload.CreateCase ->
            CasePayload.Withdrawal(payload.caseId, payload.caseId, CasePayload.Withdrawal.TARGET_CASE)
        is CasePayload.Message ->
            CasePayload.Withdrawal(payload.caseId, payload.messageId, CasePayload.Withdrawal.TARGET_MESSAGE)
        else -> null
    }

    /**
     * Queues [payloads] and removes the [discarded] rows in one transaction, the new rows
     * first. A process that dies part-way can never leave a taken-back row gone with its
     * withdrawal not yet written: it leaves both or neither, and a second discard of the
     * same thing finds nothing left to take back.
     */
    private suspend fun enqueueWithdrawn(uid: String, payloads: List<CasePayload>, discarded: List<String>) {
        outbox.enqueueAndDiscard(entriesFor(uid, payloads), discarded)
        scheduler.schedule()
    }

    private fun refusal(text: String, images: List<PreparedImage>): ComposeOutcome? = when {
        text.isEmpty() -> ComposeOutcome.Empty
        text.length > FeedbackLimits.MAX_TEXT_CHARS -> ComposeOutcome.TooLong
        images.size > FeedbackLimits.MAX_IMAGES_PER_MESSAGE -> ComposeOutcome.TooManyImages
        images.any { it.mime !in FeedbackLimits.IMAGE_MIME || it.bytes !in 1..FeedbackLimits.MAX_IMAGE_BYTES } ->
            ComposeOutcome.TooManyImages
        else -> null
    }

    private fun upload(caseId: String, messageId: String?, image: PreparedImage, gen: Long?) =
        CasePayload.Upload(caseId, messageId, newId(), image.file, image.mime, image.bytes, gen)

    /**
     * All rows of one action in one transaction: a process that dies half-way leaves none of
     * them, never a case without its screenshots' uploads or an upload without its case.
     */
    private suspend fun enqueue(uid: String, payloads: List<CasePayload>) {
        outbox.insertAll(entriesFor(uid, payloads))
        scheduler.schedule()
    }

    private fun entriesFor(uid: String, payloads: List<CasePayload>): List<OutboxEntry> {
        val base = now()
        return payloads.mapIndexed { i, payload ->
            OutboxEntry(
                id = newId(),
                ownerUid = uid,
                kind = CaseOutboxPayloads.kindOf(payload),
                payload = CaseOutboxPayloads.encode(payload),
                // One millisecond apart, so the queue's createdAt order is the send order.
                createdAtMs = base + i,
                state = OutboxState.PENDING
            )
        }
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
