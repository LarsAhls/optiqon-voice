package se.optiqon.voice.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import se.optiqon.voice.domain.sync.OutboxSender
import se.optiqon.voice.domain.sync.SendFailure
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SyncModule {

    /**
     * Mission 1 ships the queue without a producer: nothing enqueues rows yet, so this sender
     * is unreachable in practice. It refuses permanently rather than retrying, so that if a
     * later mission adds a producer and forgets to bind a real sender, the rows park visibly
     * with an explanation instead of spinning in the background forever.
     */
    @Provides
    @Singleton
    fun provideOutboxSender(): OutboxSender = OutboxSender {
        SendFailure.Permanent("Sending is not implemented in this build")
    }
}
