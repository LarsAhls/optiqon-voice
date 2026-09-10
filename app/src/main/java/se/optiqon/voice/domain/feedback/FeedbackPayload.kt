package se.optiqon.voice.domain.feedback

import com.google.gson.GsonBuilder

/**
 * One feedback message, as it travels through the outbox.
 *
 * The field list is the whole privacy promise: what the person typed, an optional way to
 * reply, and enough about the build to make the report actionable. No dictation text, no
 * audio, no account identifier — the row already carries `ownerUid` for ownership, and the
 * message itself does not need to repeat who wrote it.
 */
data class FeedbackPayload(
    val message: String,
    val contact: String?,
    val appVersion: String,
    val androidSdk: Int,
    val deviceModel: String,
    val createdAtMs: Long
)

object FeedbackPayloads {
    /** The `kind` marker on the existing outbox row. No new table, no migration. */
    const val KIND = "feedback"

    /** Long enough for a real description; short enough that a runaway paste is caught here. */
    const val MAX_MESSAGE_CHARS = 4000

    // serializeNulls: an omitted contact and an absent one would otherwise be the same wire
    // shape, and a reader could not tell "chose not to say" from "field added later".
    private val gson = GsonBuilder().serializeNulls().create()

    fun encode(payload: FeedbackPayload): String =
        gson.toJson(payload.copy(contact = payload.contact?.trim()?.ifBlank { null }))

    fun decode(json: String): FeedbackPayload =
        gson.fromJson(json, FeedbackPayload::class.java)
            .let { it.copy(contact = it.contact?.trim()?.ifBlank { null }) }

    /**
     * Whether there is something to send at all. Over-long text is refused rather than cut:
     * truncation would send words the person did not choose to end on.
     */
    fun isSendable(message: String): Boolean =
        message.trim().let { it.isNotEmpty() && it.length <= MAX_MESSAGE_CHARS }
}
