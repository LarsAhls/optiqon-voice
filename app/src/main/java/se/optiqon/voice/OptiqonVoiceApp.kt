package se.optiqon.voice

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import se.optiqon.voice.data.preferences.PreferencesDataStore
import se.optiqon.voice.data.repository.ProfileRepository
import se.optiqon.voice.data.storage.StorageOwnership
import se.optiqon.voice.domain.access.AccessSession
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class OptiqonVoiceApp : Application() {
    @Inject lateinit var preferencesDataStore: PreferencesDataStore
    @Inject lateinit var profileRepository: ProfileRepository
    @Inject lateinit var accessSession: AccessSession
    @Inject lateinit var storageOwnership: StorageOwnership

    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()

        // Started here rather than lazily from a screen: a revocation has to be noticed even
        // when the only thing running is the bubble service, which has no UI to observe it.
        accessSession.start()
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = accessSession.onForeground()
            }
        )

        startupScope.launch {
            // Both of these write into the active storage root, and both can still be running
            // when an account switch decides that root is no longer the right one. Checked
            // before each, rather than once: the identity can move between them, and a profile
            // initialised into the previous account's files is exactly the silent write this
            // whole mechanism exists to prevent.
            if (storageOwnership.isWritable) preferencesDataStore.runStartupMigrations()
            if (storageOwnership.isWritable) profileRepository.ensureDefaults()
        }
    }
}
