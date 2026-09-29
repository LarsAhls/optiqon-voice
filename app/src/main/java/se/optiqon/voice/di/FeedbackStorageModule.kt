package se.optiqon.voice.di

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import se.optiqon.voice.BuildConfig
import se.optiqon.voice.data.db.dao.OutboxDao
import se.optiqon.voice.data.feedback.CaseOutboxSender
import se.optiqon.voice.data.feedback.FirebaseAttachmentStore
import se.optiqon.voice.data.feedback.FirestoreCaseRemote
import se.optiqon.voice.data.feedback.ScreenshotPreprocessor
import se.optiqon.voice.data.storage.UserScopedStorage
import se.optiqon.voice.domain.access.AccessDecision
import se.optiqon.voice.domain.access.AccessRepository
import se.optiqon.voice.domain.access.AuthGateway
import se.optiqon.voice.domain.access.ServerVerdictListener
import se.optiqon.voice.domain.feedback.ApprovalCheck
import se.optiqon.voice.domain.feedback.ApprovalGeneration
import se.optiqon.voice.domain.feedback.AttachmentStore
import se.optiqon.voice.domain.feedback.CaseComposer
import se.optiqon.voice.domain.feedback.CaseRemote
import se.optiqon.voice.domain.feedback.FeedbackBuildInfo
import se.optiqon.voice.domain.feedback.FeedbackConfig
import se.optiqon.voice.domain.feedback.FeedbackHold
import se.optiqon.voice.domain.feedback.OutboxScheduler
import se.optiqon.voice.domain.sync.OutboxFlush
import se.optiqon.voice.domain.sync.OutboxSender
import se.optiqon.voice.domain.sync.OutboxWorker
import javax.inject.Provider
import javax.inject.Qualifier
import javax.inject.Singleton

/** The Storage instance for the Feedback bucket, and no other. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class FeedbackBucket

/**
 * The case channel's wiring: Firestore cases, screenshots in the named Feedback bucket, and
 * the outbox that carries both.
 *
 * The bucket is always named. `google-services.json` names a default bucket that does not
 * exist, so the no-argument Storage instance is never asked for anywhere in the app;
 * `FeedbackStorageContractTest` fails the build if it appears.
 *
 * Nothing leaves the device unless the build was made with `-Pfeedback.remote=true`. Without
 * it rows are queued and shown, and the worker is never scheduled.
 */
@Module
@InstallIn(SingletonComponent::class)
object FeedbackStorageModule {

    @Provides
    @Singleton
    fun provideFeedbackConfig(): FeedbackConfig = FeedbackConfig(
        bucketUrl = BuildConfig.FEEDBACK_BUCKET_URL,
        remoteEnabled = BuildConfig.FEEDBACK_REMOTE_ENABLED
    )

    @Provides
    @Singleton
    @FeedbackBucket
    fun provideFeedbackStorage(config: FeedbackConfig): FirebaseStorage =
        FirebaseStorage.getInstance(FirebaseApp.getInstance(), config.bucketUrl)

    @Provides
    @Singleton
    fun provideApprovalCheck(access: AccessRepository): ApprovalCheck =
        ApprovalCheck { access.currentDecision() is AccessDecision.Allowed }

    /** The approval generation of the signed-in account's stored verdict. */
    @Provides
    fun provideApprovalGeneration(access: AccessRepository): ApprovalGeneration =
        ApprovalGeneration { access.currentSnapshot()?.approvalGeneration }

    /**
     * What the access layer tells before it stores a verdict: queued reports of an account whose
     * approval is withdrawn are held until their owner decides. See [FeedbackHold].
     */
    @Provides
    @Singleton
    fun provideServerVerdictListener(outbox: OutboxDao): ServerVerdictListener = FeedbackHold(outbox)

    @Provides
    @Singleton
    fun provideOutboxScheduler(
        @ApplicationContext context: Context,
        config: FeedbackConfig
    ): OutboxScheduler = OutboxScheduler {
        if (config.remoteEnabled) OutboxWorker.enqueue(context)
    }

    @Provides
    @Singleton
    fun provideCaseRemote(firestore: Provider<FirebaseFirestore>): CaseRemote =
        FirestoreCaseRemote(firestore)

    @Provides
    @Singleton
    fun provideAttachmentStore(@FeedbackBucket storage: Provider<FirebaseStorage>): AttachmentStore =
        FirebaseAttachmentStore(storage)

    @Provides
    @Singleton
    fun provideScreenshotPreprocessor(files: UserScopedStorage): ScreenshotPreprocessor =
        ScreenshotPreprocessor(files)

    @Provides
    @Singleton
    fun provideCaseComposer(
        outbox: OutboxDao,
        auth: AuthGateway,
        approval: ApprovalCheck,
        generation: ApprovalGeneration,
        scheduler: OutboxScheduler,
        build: FeedbackBuildInfo
    ): CaseComposer = CaseComposer(outbox, auth, approval, generation, scheduler, build)

    @Provides
    @Singleton
    fun provideOutboxSender(
        remote: CaseRemote,
        store: AttachmentStore,
        files: UserScopedStorage,
        auth: AuthGateway,
        generation: ApprovalGeneration,
        outbox: OutboxDao
    ): OutboxSender = CaseOutboxSender(remote, store, files, auth, generation, outbox)

    @Provides
    fun provideOutboxFlush(
        outbox: OutboxDao,
        auth: AuthGateway,
        approval: ApprovalCheck,
        sender: OutboxSender,
        config: FeedbackConfig
    ): OutboxFlush = OutboxFlush(outbox, auth, approval, sender, config.remoteEnabled)
}
