package se.optiqon.voice.data.feedback

import se.optiqon.voice.data.db.entity.OutboxEntry
import se.optiqon.voice.domain.feedback.FeedbackPayloads

/**
 * The document a feedback row would become, kept separate from any code that can send it.
 *
 * Its own collection, so that switching this channel on later cannot widen a rule that
 * guards something else.
 */
object FeedbackDocument {

    const val COLLECTION = "feedback"

    /** @return the fields to write, or null when this row is not a feedback message we can read. */
    fun of(entry: OutboxEntry): Map<String, Any?>? {
        if (entry.kind != FeedbackPayloads.KIND) return null
        val payload = runCatching { FeedbackPayloads.decode(entry.payload) }.getOrNull()
            ?: return null
        if (payload.message.isBlank()) return null
        return mapOf(
            // The rules compare this against request.auth.uid; the row's ownerUid is what the
            // queue used to decide the write may happen at all. Same value, two different jobs.
            "uid" to entry.ownerUid,
            "message" to payload.message,
            "contact" to payload.contact,
            "appVersion" to payload.appVersion,
            "androidSdk" to payload.androidSdk,
            "deviceModel" to payload.deviceModel,
            "createdAtMs" to payload.createdAtMs
        )
    }
}
