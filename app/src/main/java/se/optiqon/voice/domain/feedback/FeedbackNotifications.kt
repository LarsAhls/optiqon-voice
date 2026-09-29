package se.optiqon.voice.domain.feedback

import se.optiqon.voice.domain.access.AuthGateway

/** The device's push token (FCM), or null when there is none yet. */
fun interface PushTokenSource {
    suspend fun token(): String?
}

/** Whether the user lets the app post notifications. Denied is an ordinary answer, not an error. */
fun interface NotificationPermission {
    fun granted(): Boolean
}

/**
 * `users/{uid}/notificationTokens/{installationId}` { token, platform, updatedAt }. Registering
 * needs an approved account; removing never does.
 */
interface NotificationTokens {
    suspend fun register(uid: String, installationId: String, token: String): RemoteResult

    suspend fun unregister(uid: String, installationId: String): RemoteResult
}

/**
 * Keeps this installation's push token bound to exactly the account that may receive support
 * replies on it, and to nobody else (S6).
 *
 * The token is registered only for a signed-in, approved account, in a build with remote
 * feedback on, with notification permission granted. Otherwise this installation's registration
 * is taken away from the account: revocation stops notifications at the source, and a denied
 * permission leaves nothing behind. An account switch removes the token from the account being
 * left ([leaving]) before [sync] gives it to the one signed in.
 *
 * Whether to send is the server's business (server/notify.mjs): a data-only message for a public
 * support reply, nothing for internal events, nothing for a status change. What is shown is the
 * device's: fixed neutral text ([FeedbackReplyNotice]), never anything the message carries.
 */
class NotificationRegistrar(
    private val tokens: NotificationTokens,
    private val source: PushTokenSource,
    private val permission: NotificationPermission,
    private val auth: AuthGateway,
    private val approval: ApprovalCheck,
    private val installationId: String,
    private val remoteEnabled: Boolean
) {

    enum class Outcome { REGISTERED, UNREGISTERED, NOTHING, FAILED }

    /** Brings the signed-in account's registration in line with what it may receive. */
    suspend fun sync(): Outcome {
        val uid = auth.currentUid ?: return Outcome.NOTHING
        if (!remoteEnabled) return Outcome.NOTHING
        val token = if (approval.isApproved() && permission.granted()) source.token() else null
        if (auth.currentUid != uid) return Outcome.NOTHING
        val result = if (token == null) tokens.unregister(uid, installationId)
        else tokens.register(uid, installationId, token)
        return when {
            result != RemoteResult.Ok -> Outcome.FAILED
            token == null -> Outcome.UNREGISTERED
            else -> Outcome.REGISTERED
        }
    }

    /**
     * The account [previousUid] is being left. Its registration of this installation goes first,
     * whatever its approval, so nothing meant for it can reach the next account on this device.
     */
    suspend fun leaving(previousUid: String): Outcome {
        if (!remoteEnabled) return Outcome.NOTHING
        return if (tokens.unregister(previousUid, installationId) == RemoteResult.Ok) Outcome.UNREGISTERED
        else Outcome.FAILED
    }
}
