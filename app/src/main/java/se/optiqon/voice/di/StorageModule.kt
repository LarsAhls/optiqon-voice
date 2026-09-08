package se.optiqon.voice.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import se.optiqon.voice.data.storage.AndroidProcessRestarter
import se.optiqon.voice.data.storage.DeviceDataOwner
import se.optiqon.voice.data.storage.ProcessRestarter
import se.optiqon.voice.data.storage.StorageRoot
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object StorageModule {

    /**
     * Resolved once, at the first injection in this process, and never again.
     *
     * Being a singleton is the whole mechanism: every store, DAO and background job in this
     * process is handed the same root, so none of them can end up reading one account and
     * writing another. Changing accounts changes the persisted binding and then ends the
     * process; it does not change this value.
     */
    @Provides
    @Singleton
    fun provideStorageRoot(deviceDataOwner: DeviceDataOwner): StorageRoot =
        deviceDataOwner.rootFor(deviceDataOwner.activeUid())
}

@Module
@InstallIn(SingletonComponent::class)
abstract class StorageBindingsModule {

    @Binds
    @Singleton
    abstract fun bindProcessRestarter(impl: AndroidProcessRestarter): ProcessRestarter
}
