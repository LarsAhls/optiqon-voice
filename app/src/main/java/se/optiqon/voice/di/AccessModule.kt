package se.optiqon.voice.di

import android.content.Context
import android.os.SystemClock
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import se.optiqon.voice.data.access.FirebaseAuthGateway
import se.optiqon.voice.data.access.FirebaseSignInClient
import se.optiqon.voice.data.access.RegistrationRepository
import se.optiqon.voice.domain.access.AccountRegistrar
import se.optiqon.voice.data.storage.UserScopedStorage
import se.optiqon.voice.domain.access.AccessConfig
import se.optiqon.voice.domain.access.AccessRefresher
import se.optiqon.voice.domain.access.AuthGateway
import se.optiqon.voice.domain.access.Clock
import se.optiqon.voice.domain.access.SignInClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AccessModule {

    @Provides
    @Singleton
    fun provideFirebaseAuth(): FirebaseAuth = FirebaseAuth.getInstance()

    /**
     * Firestore runs with an in-memory cache on purpose.
     *
     * Disk persistence would leave one account's documents readable offline by the next
     * account on the device, and would hold a pending-write queue we do not control. The
     * standard cure — `clearPersistence()` at sign-out — destroys unsent writes, so instead
     * Firestore is never allowed to own durable local data: our own Room outbox does.
     */
    @Provides
    @Singleton
    fun provideFirestore(): FirebaseFirestore = FirebaseFirestore.getInstance().apply {
        firestoreSettings = FirebaseFirestoreSettings.Builder()
            .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
            .build()
    }

    @Provides
    @Singleton
    fun provideClock(): Clock = object : Clock {
        override fun wallMs(): Long = System.currentTimeMillis()
        override fun elapsedMs(): Long = SystemClock.elapsedRealtime()
    }

    /** The proposed 72 h test value; see [AccessConfig]. */
    @Provides
    @Singleton
    fun provideAccessConfig(): AccessConfig = AccessConfig()

    @Provides
    @Singleton
    fun provideApplicationScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun provideUserScopedStorage(@ApplicationContext context: Context): UserScopedStorage =
        UserScopedStorage(context.filesDir)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AccessBindingsModule {

    @Binds
    @Singleton
    abstract fun bindAuthGateway(impl: FirebaseAuthGateway): AuthGateway

    @Binds
    @Singleton
    abstract fun bindSignInClient(impl: FirebaseSignInClient): SignInClient

    /**
     * The refresher is deliberately given the narrow reader interface rather than the
     * repository: it decides *when* to ask, and nothing about it should be able to reach
     * registration, sign-in or Firestore directly.
     */
    @Binds
    @Singleton
    abstract fun bindRegistrationReader(
        impl: RegistrationRepository
    ): AccessRefresher.RegistrationReader

    /** The same object seen through the one method the account screen is allowed to call. */
    @Binds
    @Singleton
    abstract fun bindAccountRegistrar(impl: RegistrationRepository): AccountRegistrar
}
