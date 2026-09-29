package se.optiqon.voice.data.feedback

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import se.optiqon.voice.MainActivity
import se.optiqon.voice.R
import se.optiqon.voice.domain.feedback.FeedbackReplyNotice
import se.optiqon.voice.domain.feedback.NoticePoster
import se.optiqon.voice.domain.feedback.NotificationPermission
import se.optiqon.voice.domain.feedback.PushTokenSource
import java.util.UUID

/**
 * This installation's id for `users/{uid}/notificationTokens/{installationId}`.
 *
 * A random UUID in its own device-level preferences file, outside every account's storage root
 * (like [se.optiqon.voice.data.storage.DeviceDataOwner]): the same installation keeps one id
 * across account switches, so leaving one account and registering with the next address the
 * same document name under different accounts. No network, no provider id, no device
 * identifier. Committed on first use so two readers never see two ids.
 */
class InstallationId(private val prefs: SharedPreferences) {

    @Synchronized
    fun get(): String {
        prefs.getString(KEY, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit().putString(KEY, id).commit()
        return id
    }

    companion object {
        const val FILE_NAME = "feedback_installation"
        private const val KEY = "installation_id"

        fun of(context: Context) = InstallationId(context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE))
    }
}

/**
 * The FCM token. Asked for only by [se.optiqon.voice.domain.feedback.NotificationRegistrar],
 * which does so only for an approved account with permission granted in a remote build;
 * auto-init is off in the manifest, so no other path creates one.
 */
class FirebasePushTokenSource : PushTokenSource {
    override suspend fun token(): String? = try {
        withTimeout(TIMEOUT_MS) { FirebaseMessaging.getInstance().token.await() }?.takeIf { it.isNotBlank() }
    } catch (cancellation: kotlinx.coroutines.CancellationException) {
        if (cancellation is kotlinx.coroutines.TimeoutCancellationException) null else throw cancellation
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
    }
}

/**
 * Whether a notification from this app would be shown. POST_NOTIFICATIONS is a runtime
 * permission from API 33 (asked for in onboarding, `PermissionHelper`); below it the user can
 * still switch the app's notifications off. Either answer is ordinary; nothing in Feedback
 * waits for it.
 */
class SystemNotificationPermission(private val context: Context) : NotificationPermission {
    override fun granted(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
}

/** Shows [FeedbackReplyNotice] — fixed text, no case id, opens the app and nothing more. */
class SystemNoticePoster(
    private val context: Context,
    private val permission: NotificationPermission
) : NoticePoster {

    override fun post() {
        if (!permission.granted()) return
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context,
            FeedbackReplyNotice.NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        try {
            NotificationManagerCompat.from(context).notify(FeedbackReplyNotice.NOTIFICATION_ID, build(context, open))
        } catch (_: SecurityException) {
            // Permission withdrawn between the check and the post: nothing is shown, nothing breaks.
        }
    }

    companion object {
        fun build(context: Context, open: PendingIntent?) =
            NotificationCompat.Builder(context, FeedbackReplyNotice.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(FeedbackReplyNotice.TITLE)
                .setContentText(FeedbackReplyNotice.BODY)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()

        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(
                    FeedbackReplyNotice.CHANNEL_ID,
                    context.getString(R.string.feedback_reply_channel),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
    }
}
