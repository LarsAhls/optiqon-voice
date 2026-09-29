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

    /**
     * The owner's "take this back" for a case opening or a message that may have reached the
     * server as `submitted`: an ID-only intent, never the content. [target] is `case` or
     * `message`; for a case, [targetId] is [caseId]. Not stamped: withdrawing needs no current
     * approval (M3=A), only the owner's verified account. [outcome] is the server's verdict once
     * it has reconciled the intent -- see [WithdrawalOutcome].
     */
    data class Withdrawal(
        override val caseId: String,
        val targetId: String,
        val target: String,
        val outcome: String? = null
    ) : CasePayload {
        init {
            require(target == TARGET_CASE || target == TARGET_MESSAGE)
            require(target != TARGET_CASE || targetId == caseId)
        }

        companion object {
            const val TARGET_CASE = "case"
            const val TARGET_MESSAGE = "message"
        }
    }
}

object CaseOutboxPayloads {
    const val KIND_CREATE = "case_create"
    const val KIND_MESSAGE = "case_message"
    const val KIND_UPLOAD = "attachment_upload"
    const val KIND_DELETE = "attachment_delete"
    const val KIND_WITHDRAW = "feedback_withdrawal"

    val KINDS = setOf(KIND_CREATE, KIND_MESSAGE, KIND_UPLOAD, KIND_DELETE, KIND_WITHDRAW)

    /**
     * Rows that only ever stop or take something down: they go first, are never held behind a
     * stuck row of their case, and a failure of theirs holds nothing else back.
     */
    val REMOVALS = setOf(KIND_DELETE, KIND_WITHDRAW)

    fun kindOf(payload: CasePayload): String = when (payload) {
        is CasePayload.CreateCase -> KIND_CREATE
        is CasePayload.Message -> KIND_MESSAGE
        is CasePayload.Upload -> KIND_UPLOAD
        is CasePayload.Tombstone -> KIND_DELETE
        is CasePayload.Withdrawal -> KIND_WITHDRAW
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
            is CasePayload.Withdrawal -> {
                addProperty("targetId", payload.targetId)
                addProperty("target", payload.target)
                payload.outcome?.let { addProperty("outcome", it) }
            }
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
            KIND_WITHDRAW -> runCatching {
                CasePayload.Withdrawal(caseId, str("targetId") ?: return null, str("target") ?: return null, str("outcome"))
            }.getOrNull()
            else -> null
        }
    }
}
