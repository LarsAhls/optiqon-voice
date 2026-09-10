package se.optiqon.voice.di

import dagger.Binds
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import se.optiqon.voice.data.storage.AndroidProcessRestarter
import se.optiqon.voice.data.storage.ProcessRestarter
import se.optiqon.voice.data.storage.SignedInUid
import se.optiqon.voice.data.storage.StorageOwnership
import se.optiqon.voice.data.storage.StorageRoot
import se.optiqon.voice.domain.access.AuthGateway
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object StorageModule {

    /**
     * Who this process is, as far as storage is concerned.
     *
     * [Lazy] because the identity provider is only built if it is actually asked, and a build
     * without Firebase configuration must still start; `runCatching` because an SDK that cannot
     * be initialised has to read as "unknown identity", which is the closed door, rather than as
     * an exception on the way to the first file.
     */
    @Provides
    @Singleton
    fun provideSignedInUid(authGateway: Lazy<AuthGateway>): SignedInUid =
        SignedInUid { runCatching { authGateway.get().currentUid }.getOrNull() }

    /**
     * Resolved once, at the first injection in this process, and never again.
     *
     * Being a singleton is the whole mechanism: every store, DAO and background job in this
     * process is handed the same root, so none of them can end up reading one account and
     * writing another. Changing accounts changes the persisted binding and then ends the
     * process; it does not change this value.
     *
     * The resolution itself lives in [StorageOwnership], which reconciles the persisted active
     * account against the one actually signed in *here*, before this value exists and therefore
     * before any handle can be opened from it.
     */
    @Provides
    @Singleton
    fun provideStorageRoot(storageOwnership: StorageOwnership): StorageRoot = storageOwnership.root
}

@Module
@InstallIn(SingletonComponent::class)
abstract class StorageBindingsModule {

    @Binds
    @Singleton
    abstract fun bindProcessRestarter(impl: AndroidProcessRestarter): ProcessRestarter
}
