package se.optiqon.voice.service

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import se.optiqon.voice.domain.feedback.FeedbackPushHandler
import javax.inject.Inject

/**
 * The receive and token-refresh path for Feedback push (S6).
 *
 * The server sends data only, so every message lands here, foreground or background, and the
 * visible notification is built on the device from fixed text ([FeedbackPushHandler]). What the
 * message carries besides its kind is never read. Receiving marks nothing read.
 */
@AndroidEntryPoint
class FeedbackMessagingService : FirebaseMessagingService() {

    @Inject lateinit var handler: FeedbackPushHandler
    @Inject lateinit var scope: CoroutineScope

    override fun onMessageReceived(message: RemoteMessage) {
        // Called on a worker thread with a short budget; the decision needs the stored verdict.
        runBlocking { withTimeoutOrNull(RECEIVE_BUDGET_MS) { handler.onMessage(message.data) } }
    }

    override fun onNewToken(token: String) {
        // The token itself is fetched again by the registrar, under the same eligibility checks.
        scope.launch { handler.onNewToken() }
    }

    private companion object {
        const val RECEIVE_BUDGET_MS = 8_000L
    }
}
