package se.optiqon.voice.di

import android.os.Build
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import se.optiqon.voice.BuildConfig
import se.optiqon.voice.data.db.dao.OutboxDao
import se.optiqon.voice.domain.access.AuthGateway
import se.optiqon.voice.domain.feedback.FeedbackBuildInfo
import se.optiqon.voice.domain.feedback.FeedbackQueue
import javax.inject.Singleton

/**
 * The feedback channel's wiring — the queue only.
 *
 * There is deliberately no `OutboxSender` binding here: `SyncModule` still provides the
 * placeholder, so a queued feedback row has nothing that could send it. See
 * `FirestoreFeedbackSender` for the two steps that switch the channel on, in order.
 */
@Module
@InstallIn(SingletonComponent::class)
object FeedbackModule {

    @Provides
    @Singleton
    fun provideFeedbackBuildInfo(): FeedbackBuildInfo = FeedbackBuildInfo(
        appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        androidSdk = Build.VERSION.SDK_INT,
        // Manufacturer and model, not a serial or an advertising id: enough to reproduce a
        // report, not enough to single out a device.
        deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
    )

    @Provides
    @Singleton
    fun provideFeedbackQueue(
        outbox: OutboxDao,
        auth: AuthGateway,
        build: FeedbackBuildInfo
    ): FeedbackQueue = FeedbackQueue(outbox = outbox, auth = auth, build = build)
}
