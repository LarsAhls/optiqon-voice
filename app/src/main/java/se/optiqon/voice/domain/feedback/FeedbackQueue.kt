package se.optiqon.voice.domain.feedback

import se.optiqon.voice.data.db.dao.OutboxDao
import se.optiqon.voice.data.db.entity.OutboxEntry
import se.optiqon.voice.data.db.entity.OutboxState
import se.optiqon.voice.domain.access.AuthGateway
import java.util.UUID

/** The parts of the build a report needs to be actionable. Nothing here identifies a person. */
data class FeedbackBuildInfo(val appVersion: String, val androidSdk: Int, val deviceModel: String)

sealed interface FeedbackOutcome {
    /** Written to the device's own queue. Deliberately not "sent" — see [FeedbackQueue]. */
    data object Queued : FeedbackOutcome
    data object Empty : FeedbackOutcome
    data object TooLong : FeedbackOutcome
    data object SignedOut : FeedbackOutcome
}

/**
 * Puts a feedback message on the existing outbox, and does nothing else.
 *
 * There is no sender here and no worker enqueued on purpose. This is the whole meaning of
 * "built, not switched on": the message is durable on the device, owned by the account that
 * wrote it, and it moves only once somebody deliberately binds a real sender and starts the
 * worker — which in turn is refused by the live rules until the feedback rule is deployed.
 * The screen therefore tells the person the truth: saved here, not uploaded.
 */
class FeedbackQueue(
    private val outbox: OutboxDao,
    private val auth: AuthGateway,
    private val build: FeedbackBuildInfo,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { UUID.randomUUID().toString() }
) {

    suspend fun submit(message: String, contact: String?): FeedbackOutcome {
        val text = message.trim()
        if (text.isEmpty()) return FeedbackOutcome.Empty
        if (!FeedbackPayloads.isSendable(text)) return FeedbackOutcome.TooLong

        // A row must name its owner. Writing one with a placeholder owner would put somebody
        // else's message in the path of the next account that signs in.
        val uid = auth.currentUid ?: return FeedbackOutcome.SignedOut

        val createdAt = now()
        outbox.insert(
            OutboxEntry(
                id = newId(),
                ownerUid = uid,
                kind = FeedbackPayloads.KIND,
                payload = FeedbackPayloads.encode(
                    FeedbackPayload(
                        message = text,
                        contact = contact,
                        appVersion = build.appVersion,
                        androidSdk = build.androidSdk,
                        deviceModel = build.deviceModel,
                        createdAtMs = createdAt
                    )
                ),
                createdAtMs = createdAt,
                state = OutboxState.PENDING
            )
        )
        return FeedbackOutcome.Queued
    }
}
