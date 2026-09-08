package se.optiqon.voice.testing

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.room.Room
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import se.optiqon.voice.data.api.ApiClientFactory
import se.optiqon.voice.data.db.OptiqonVoiceDatabase
import se.optiqon.voice.data.preferences.PreferencesDataStore
import se.optiqon.voice.data.preferences.testPreferencesDataStore
import se.optiqon.voice.data.repository.ProcessingRepository
import se.optiqon.voice.data.repository.ProfileRepository
import se.optiqon.voice.domain.processing.TextProcessor
import se.optiqon.voice.domain.transcription.NetworkMonitor
import se.optiqon.voice.domain.transcription.TranscriptionManager
import se.optiqon.voice.domain.transcription.WhisperEngine
import java.io.File

/**
 * A real [TranscriptionManager] — real Room, real preferences, real HTTP client — wired to the
 * access fixture and pointed at a local TLS server.
 *
 * Nothing is stubbed on the path an assertion depends on. The question these tests ask is
 * whether a request reaches the network at all, so a fake transcriber would answer a different
 * question: `server.requestCount` is the evidence, and it only means something if the manager
 * is the one the app builds.
 */
class TranscriptionFixture(val context: Context) {
    /**
     * Deliberately not the test's scope. DataStore and the access repository keep collectors
     * running on whatever scope they are given, and putting them on the test scheduler makes a
     * blocking assertion — the only way to observe a refusal from a server dispatcher thread —
     * wait for a scheduler that the same thread is blocking. Real dispatchers, no deadlock.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val tls = TlsMockServer()
    val access = AccessFixture(context, scope)
    val preferences: PreferencesDataStore = testPreferencesDataStore(context)
    val database: OptiqonVoiceDatabase = Room
        .inMemoryDatabaseBuilder(context, OptiqonVoiceDatabase::class.java)
        .allowMainThreadQueries()
        .build()

    init {
        // Robolectric's default device reports no active capabilities, so `NetworkMonitor`
        // would answer "offline" and the manager would refuse before reaching the gate at all.
        // Every refusal test here asserts that nothing was sent; without this the assertion
        // would hold on a device that could not send anything in the first place, and would
        // keep holding if the gate were deleted.
        markDeviceOnline(context)
    }

    private val clientFactory = ApiClientFactory(tls.client)
    val profiles = ProfileRepository(
        database.profileDao(),
        database.postProcessingPromptDao(),
        preferences
    )

    val manager = TranscriptionManager(
        context,
        WhisperEngine(clientFactory, preferences, NetworkMonitor(context)),
        TextProcessor(
            clientFactory,
            preferences,
            ProcessingRepository(database.textReplacementRuleDao(), database.postProcessingPromptDao())
        ),
        database.dictationDao(),
        database.lifetimeStatsDao(),
        preferences,
        profiles,
        access.guard
    )

    /** Configures an endpoint that exists, so that a refusal cannot be blamed on configuration. */
    suspend fun configureAsr() {
        preferences.updateAsrConfig(tls.baseUrl, "not-a-real-key", "whisper-1")
    }

    /** Some audio on disk. Contents are irrelevant; only whether it is sent is. */
    fun audioFile(): File = File.createTempFile("dictation", ".m4a", context.cacheDir).apply {
        writeBytes(ByteArray(64) { it.toByte() })
    }

    private fun markDeviceOnline(context: Context) {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return
        val capabilities = ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(manager).setNetworkCapabilities(network, capabilities)
    }

    fun shutdown() {
        tls.shutdown()
        database.close()
        scope.cancel()
    }
}
