package se.optiqon.voice.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import se.optiqon.voice.domain.feedback.FeedbackNotificationSync

/**
 * Where the push registration is kept in step with the account (S6).
 *
 * [start] runs once per process: the registration is synced for every distinct pair of identity
 * and access decision — so at startup, on approval, on revocation and after sign-in — and
 * [onForeground] syncs again, because notification permission is changed in system settings
 * where nothing tells the app. A token refresh syncs through the messaging service; sign-out
 * removes the registration first ([se.optiqon.voice.domain.access.AccountLeaving]). An account
 * switch restarts the process, so the new account starts here.
 *
 * With remote Feedback off, nothing is started and no token is ever created.
 */
class FeedbackPushLifecycle(
    private val sync: FeedbackNotificationSync,
    private val identity: Flow<String?>,
    private val decision: Flow<Any>,
    private val scope: CoroutineScope,
    private val remoteEnabled: Boolean
) {
    @Volatile private var started = false

    fun start() {
        if (!remoteEnabled || started) return
        started = true
        scope.launch {
            combine(identity, decision) { uid, verdict -> uid to verdict::class }
                .distinctUntilChanged()
                .collect { sync.sync() }
        }
    }

    fun onForeground() {
        if (!remoteEnabled) return
        scope.launch { sync.sync() }
    }
}
