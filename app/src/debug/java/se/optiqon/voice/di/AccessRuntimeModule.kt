package se.optiqon.voice.di

import android.os.SystemClock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import se.optiqon.voice.data.access.RegistrationRepository
import se.optiqon.voice.debug.AccessDebugControls
import se.optiqon.voice.debug.DebugClock
import se.optiqon.voice.debug.DebugRegistrationReader
import se.optiqon.voice.domain.access.AccessRefresher
import se.optiqon.voice.domain.access.Clock
import javax.inject.Singleton

/**
 * Debug counterpart of the release `AccessRuntimeModule`: same two bindings, each wrapped
 * so [AccessDebugControls] can bend them. See the release file for why they are separate
 * files rather than a `BuildConfig.DEBUG` branch.
 */
@Module
@InstallIn(SingletonComponent::class)
object AccessRuntimeModule {

    @Provides
    @Singleton
    fun provideClock(controls: AccessDebugControls): Clock = DebugClock(
        controls = controls,
        wall = { System.currentTimeMillis() },
        elapsed = { SystemClock.elapsedRealtime() }
    )

    @Provides
    @Singleton
    fun provideRegistrationReader(
        impl: RegistrationRepository,
        controls: AccessDebugControls
    ): AccessRefresher.RegistrationReader = DebugRegistrationReader(delegate = impl, controls = controls)
}
