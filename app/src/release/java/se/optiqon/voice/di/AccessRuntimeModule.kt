package se.optiqon.voice.di

import android.os.SystemClock
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import se.optiqon.voice.data.access.RegistrationRepository
import se.optiqon.voice.domain.access.AccessRefresher
import se.optiqon.voice.domain.access.Clock
import javax.inject.Singleton

/**
 * The two access seams that a debug build is allowed to bend, in their plain form.
 *
 * A file with this name exists in both the `release` and the `debug` source set. This is
 * the release one: the real clock and the real reader, nothing in between. The debug one
 * routes both through [se.optiqon.voice.debug.AccessDebugControls] so a smoke test can
 * force a failed refresh or move the clock without touching the device's own clock. Keeping
 * them as two files rather than one file with a `BuildConfig.DEBUG` branch means the hook
 * code is not merely dormant in a release APK — it is not compiled into it.
 */
@Module
@InstallIn(SingletonComponent::class)
object AccessRuntimeModule {

    @Provides
    @Singleton
    fun provideClock(): Clock = object : Clock {
        override fun wallMs(): Long = System.currentTimeMillis()
        override fun elapsedMs(): Long = SystemClock.elapsedRealtime()
    }

    /**
     * The refresher is deliberately given the narrow reader interface rather than the
     * repository: it decides *when* to ask, and nothing about it should be able to reach
     * registration, sign-in or Firestore directly.
     */
    @Provides
    @Singleton
    fun provideRegistrationReader(impl: RegistrationRepository): AccessRefresher.RegistrationReader = impl
}
