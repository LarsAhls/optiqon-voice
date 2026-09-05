package se.optiqon.voice

import android.app.Application
import se.optiqon.voice.data.preferences.PreferencesDataStore
import se.optiqon.voice.data.repository.ProfileRepository
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

    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        startupScope.launch {
            preferencesDataStore.runStartupMigrations()
            profileRepository.ensureDefaults()
        }
    }
}
