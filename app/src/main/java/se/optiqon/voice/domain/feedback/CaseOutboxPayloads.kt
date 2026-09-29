package se.optiqon.voice.domain.feedback

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * The outbox rows of the case channel.
 *
 * Written with Gson's tree API rather than reflection: the fields are named here, in the
 * source, so R8 has nothing to rename and ReleaseReflectionContractTest has nothing new to
 * guard. A row that cannot be read decodes to null and is parked, never guessed at.
 */
sealed interface CasePayload {
    val caseId: String

    /**
     * The account's approval generation when this row was queued, or null for a row written
     * before rows carried one. A row is only sent under the generation it was written in; one
     * whose stamp is missing or stale waits for its owner to send it again. See
     * [CaseComposer.releaseHeld].
     */
    sealed interface Stamped : CasePayload {
        val generation: Long?
        fun restamp(generation: Long): Stamped
    }

    data class CreateCase(
        override val caseId: String,
        val title: String,
        val body: String,
        override val generation: Long? = null
    ) : Stamped {
        override fun restamp(generation: Long) = copy(generation = generation)
    }

    data class Message(
        override val caseId: String,
        val messageId: String,
        val body: String,
        override val generation: Long? = null
    ) : Stamped {
        override fun restamp(generation: Long) = copy(generation = generation)
    }

    /** [file] is a bare file name inside the owner's own attachments directory. */
    data class Upload(
        override val caseId: String,
        val messageId: String?,
        val aid: String,
        val file: String,
        val mime: String,
        val bytes: Int,
        override val generation: Long? = null
    ) : Stamped {
        override fun restamp(generation: Long) = copy(generation = generation)
    }

    data class Tombstone(override val caseId: String, val aid: String) : CasePayload
}

object CaseOutboxPayloads {
    const val KIND_CREATE = "case_create"
    const val KIND_MESSAGE = "case_message"
    const val KIND_UPLOAD = "attachment_upload"
    const val KIND_DELETE = "attachment_delete"

    val KINDS = setOf(KIND_CREATE, KIND_MESSAGE, KIND_UPLOAD, KIND_DELETE)

    fun kindOf(payload: CasePayload): String = when (payload) {
        is CasePayload.CreateCase -> KIND_CREATE
        is CasePayload.Message -> KIND_MESSAGE
        is CasePayload.Upload -> KIND_UPLOAD
        is CasePayload.Tombstone -> KIND_DELETE
    }

    fun encode(payload: CasePayload): String = JsonObject().apply {
        addProperty("caseId", payload.caseId)
        when (payload) {
            is CasePayload.CreateCase -> {
                addProperty("title", payload.title)
                addProperty("body", payload.body)
            }
            is CasePayload.Message -> {
                addProperty("messageId", payload.messageId)
                addProperty("body", payload.body)
            }
            is CasePayload.Upload -> {
                payload.messageId?.let { addProperty("messageId", it) }
                addProperty("aid", payload.aid)
                addProperty("file", payload.file)
                addProperty("mime", payload.mime)
                addProperty("bytes", payload.bytes)
            }
            is CasePayload.Tombstone -> addProperty("aid", payload.aid)
        }
        if (payload is CasePayload.Stamped) payload.generation?.let { addProperty("gen", it) }
    }.toString()

    fun decode(kind: String, json: String): CasePayload? {
        val o = try {
            JsonParser.parseString(json).asJsonObject
        } catch (_: RuntimeException) {
            return null
        }
        fun str(name: String): String? =
            o.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        fun int(name: String): Int? =
            o.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                ?.let { runCatching { it.asInt }.getOrNull() }
        fun long(name: String): Long? =
            o.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                ?.let { runCatching { it.asLong }.getOrNull() }
        val gen = long("gen")
        val caseId = str("caseId") ?: return null
        return when (kind) {
            KIND_CREATE -> CasePayload.CreateCase(caseId, str("title") ?: return null, str("body") ?: return null, gen)
            KIND_MESSAGE -> CasePayload.Message(
                caseId, str("messageId") ?: return null, str("body") ?: return null, gen
            )
            KIND_UPLOAD -> CasePayload.Upload(
                caseId = caseId,
                messageId = str("messageId"),
                aid = str("aid") ?: return null,
                file = str("file") ?: return null,
                mime = str("mime") ?: return null,
                bytes = int("bytes") ?: return null,
                generation = gen
            )
            KIND_DELETE -> CasePayload.Tombstone(caseId, str("aid") ?: return null)
            else -> null
        }
    }
}
