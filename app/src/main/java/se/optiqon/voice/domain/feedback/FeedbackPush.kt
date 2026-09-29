package se.optiqon.voice.domain.feedback

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import se.optiqon.voice.domain.access.AuthGateway

/**
 * The one notification Feedback ever shows, with fixed text built on the device (S6).
 *
 * The server's message is data-only (`server/notify.mjs`), so nothing it sends is ever
 * displayed: the title and body below are the whole of what can appear on a lock screen. No
 * case id, no title, no reply, no status. The notification is attention only; it opens the app
 * and marks nothing read — the read marker in Firestore moves only when the owner opens a case.
 */
object FeedbackReplyNotice {
    const val TITLE = "OPTIQON Voice"
    const val BODY = "Du har fått svar på din feedback."
    const val KIND = "feedback_reply"
    const val CHANNEL_ID = "optiqon_voice_feedback"

    /** One slot, replaced by the next reply: the text is the same, so a stack would say nothing. */
    const val NOTIFICATION_ID = 4468
}

/** Posts [FeedbackReplyNotice] on the device. */
fun interface NoticePoster {
    fun post()
}

/**
 * What the device does with a push message and a new token.
 *
 * A message is shown only if it is the Feedback kind, remote Feedback is built in, someone is
 * signed in, that account is approved *now*, and the user lets the app post notifications. A
 * revoked account that still had a token somewhere shows nothing. Everything else in the
 * message is ignored. Nothing here can reach [CaseReads]: receiving is never reading.
 */
class FeedbackPushHandler(
    private val auth: AuthGateway,
    private val approval: ApprovalCheck,
    private val permission: NotificationPermission,
    private val poster: NoticePoster,
    private val sync: FeedbackNotificationSync,
    private val remoteEnabled: Boolean
) {

    suspend fun onMessage(data: Map<String, String>): Boolean {
        if (!remoteEnabled) return false
        if (data["kind"] != FeedbackReplyNotice.KIND) return false
        if (auth.currentUid == null) return false
        if (!approval.isApproved()) return false
        if (!permission.granted()) return false
        poster.post()
        return true
    }

    /** The provider rotated this installation's token; the registration follows it. */
    suspend fun onNewToken() {
        sync.sync()
    }
}

/**
 * Runs [NotificationRegistrar.sync] one at a time, from every point where what the account may
 * receive can have changed: start, a new access decision or identity, coming to the foreground
 * (the permission may have been changed in system settings), and a token refresh.
 *
 * Duplicate triggers are harmless: a sync writes the same document again or deletes one that is
 * already gone.
 */
class FeedbackNotificationSync(
    private val registrar: NotificationRegistrar
) {
    private val lock = Mutex()

    suspend fun sync(): NotificationRegistrar.Outcome = lock.withLock {
        try {
            registrar.sync()
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            NotificationRegistrar.Outcome.FAILED
        }
    }
}
