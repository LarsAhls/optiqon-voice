package se.optiqon.voice.domain.sync

import se.optiqon.voice.data.db.entity.OutboxEntry

/**
 * Delivers one queued row.
 *
 * Mission 1 builds the queue, the ownership rule and the failure handling; the feedback and
 * attachment payloads that will travel through it belong to later missions. The interface
 * exists now so [OutboxWorker] can be finished and tested against a fake, and so the eventual
 * implementation has to answer the one question that matters: is this failure permanent?
 */
fun interface OutboxSender {

    /** @return null on success, or the failure that stops or postpones this row. */
    suspend fun send(entry: OutboxEntry): SendFailure?
}
