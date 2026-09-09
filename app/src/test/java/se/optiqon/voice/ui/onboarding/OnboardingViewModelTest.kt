package se.optiqon.voice.ui.onboarding

import android.content.Context
import androidx.room.Room
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.optiqon.voice.data.api.ApiClientFactory
import se.optiqon.voice.data.db.OptiqonVoiceDatabase
import se.optiqon.voice.data.preferences.PreferencesDataStore
import se.optiqon.voice.data.preferences.UserPreferences
import se.optiqon.voice.data.preferences.testPreferencesDataStore
import se.optiqon.voice.data.repository.ProfileRepository
import se.optiqon.voice.domain.provider.ProviderPreset
import se.optiqon.voice.domain.provider.ProviderPresets
import se.optiqon.voice.domain.provider.ProviderVerifier
import se.optiqon.voice.testing.TlsMockServer

/**
 * First run, end to end minus the UI: nothing is saved until the endpoint has actually
 * transcribed something, because an endpoint that has never worked is a bubble that fails at
 * the moment it is first needed. The provider here is a local TLS server, never a real one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OnboardingViewModelTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var tls: TlsMockServer
    private lateinit var preferences: PreferencesDataStore
    private lateinit var database: OptiqonVoiceDatabase
    private lateinit var viewModel: OnboardingViewModel

    /** The second run of onboarding, when one is built. Cancelled with the first. */
    private var returning: OnboardingViewModel? = null

    @Before
    fun setUp() {
        // Unconfined, because the work being waited on is real: an HTTP round trip and a
        // DataStore write. Virtual time cannot advance either of them.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        tls = TlsMockServer()
        preferences = testPreferencesDataStore(context)
        database = Room.inMemoryDatabaseBuilder(context, OptiqonVoiceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        viewModel = buildViewModel()
    }

    /**
     * A view model on the same stores as the one built in [setUp] — which is what the next
     * launch of onboarding is: the same files, a fresh screen.
     */
    private fun buildViewModel() = OnboardingViewModel(
        preferencesDataStore = preferences,
        providerVerifier = ProviderVerifier(ApiClientFactory(tls.client)),
        profileRepository = ProfileRepository(
            database.profileDao(),
            database.postProcessingPromptDao(),
            preferences
        )
    )

    @After
    fun tearDown() {
        // Before the database and the server go away, or work still in flight lands on them
        // and the failure surfaces in whichever test happens to run next.
        viewModel.viewModelScope.cancel()
        returning?.viewModelScope?.cancel()
        database.close()
        tls.shutdown()
        Dispatchers.resetMain()
    }

    /** The recommended preset, pointed at the local server so no real provider is called. */
    private fun localPreset(): ProviderPreset = ProviderPresets.GROQ.copy(baseUrl = tls.baseUrl)

    /** For state that lands in the database, which no flow here exposes. */
    private suspend fun awaitValue(description: String, predicate: suspend () -> Boolean) {
        val settled = withContext(Dispatchers.Default) {
            withTimeoutOrNull(TIMEOUT_MS) {
                while (!predicate()) delay(10)
                true
            }
        }
        if (settled == null) fail("timed out waiting until $description")
    }

    private suspend fun awaitState(predicate: (OnboardingUiState) -> Boolean): OnboardingUiState =
        withContext(Dispatchers.Default) { withTimeout(TIMEOUT_MS) { viewModel.uiState.first(predicate) } }

    private suspend fun awaitPreferences(predicate: (UserPreferences) -> Boolean): UserPreferences =
        withContext(Dispatchers.Default) { withTimeout(TIMEOUT_MS) { preferences.preferences.first(predicate) } }

    @Test
    fun `it starts on the account step, with Swedish already chosen`() {
        assertEquals(OnboardingStep.ACCOUNT, viewModel.uiState.value.step)
        assertEquals(OnboardingUiState.DEFAULT_LANGUAGE, viewModel.uiState.value.language)
    }

    @Test
    fun `the chosen language reaches preferences and the active profile`() = runTest {
        viewModel.next()
        viewModel.selectLanguage("en")
        viewModel.next()

        assertEquals(OnboardingStep.CONNECT, viewModel.uiState.value.step)
        awaitPreferences { it.preferredLanguages == listOf("en") }
        awaitValue("the active profile speaks English") {
            database.profileDao().getActiveProfile()?.language == "en"
        }
    }

    @Test
    fun `Auto detect is carried through as no language at all`() = runTest {
        viewModel.next()
        viewModel.selectLanguage(null)
        viewModel.next()

        awaitPreferences { it.activeLanguage == null && it.preferredLanguages.isEmpty() }
        awaitValue("the active profile detects the language") {
            database.profileDao().countProfiles() == 1 && database.profileDao().getActiveProfile()?.language == null
        }
    }

    @Test
    fun `a verified endpoint is saved, and only then`() = runTest {
        tls.server.enqueue(MockResponse().setResponseCode(200).setBody("{\"text\":\"\"}"))
        tls.server.enqueue(MockResponse().setResponseCode(200).setBody(CHAT_OK))
        viewModel.selectPreset(localPreset())
        viewModel.updateApiKey("gsk_not-a-real-key")

        assertEquals("", preferences.preferences.first().asrBaseUrl)

        viewModel.verifyAndSave()

        awaitState { it.connection == ConnectionState.Verified }
        val saved = awaitPreferences { it.asrBaseUrl.isNotBlank() }
        assertEquals(tls.baseUrl, saved.asrBaseUrl)
        assertEquals(ProviderPresets.GROQ.asrModel, saved.asrModel)
        assertEquals(ProviderPresets.GROQ.id, saved.providerPresetId)
        // Cleanup is part of what the recommended provider is for, so it arrives switched on.
        assertTrue(saved.llmEnabled)
        awaitValue("the active profile carries the verified provider") {
            database.profileDao().getActiveProfile()?.llmModel == ProviderPresets.GROQ.llmModel
        }
    }

    /**
     * The failure F17 was: the preset's text model had been decommissioned, transcription kept
     * answering 200, and onboarding said "Connected" while every cleanup call 404'd in silence.
     * The user still gets through — dictation works without cleanup — but is told, and cleanup
     * is not left switched on pointing at a model the provider refuses.
     */
    @Test
    fun `a dead cleanup model is surfaced, left off, and does not block the user`() = runTest {
        tls.server.enqueue(MockResponse().setResponseCode(200).setBody("{\"text\":\"\"}"))
        tls.server.enqueue(MockResponse().setResponseCode(404).setBody("{\"error\":\"decommissioned\"}"))
        viewModel.selectPreset(localPreset())
        viewModel.updateApiKey("gsk_not-a-real-key")

        viewModel.verifyAndSave()

        val state = awaitState { it.connection is ConnectionState.VerifiedWithoutCleanup }
        assertTrue(
            (state.connection as ConnectionState.VerifiedWithoutCleanup).message.contains("404")
        )
        // Transcription still works, so the run is not blocked on the cleanup model.
        assertTrue(state.canLeaveConnectStep)
        val saved = awaitPreferences { it.asrBaseUrl.isNotBlank() }
        assertEquals(tls.baseUrl, saved.asrBaseUrl)
        assertFalse(saved.llmEnabled)
        awaitValue("the active profile has cleanup switched off") {
            database.profileDao().getActiveProfile()?.llmEnabled == false
        }
    }

    @Test
    fun `a refused key saves nothing and does not let the user move on`() = runTest {
        tls.server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":\"invalid\"}"))
        viewModel.selectPreset(localPreset())
        viewModel.updateApiKey("gsk_wrong")

        viewModel.verifyAndSave()

        val state = awaitState { it.connection is ConnectionState.Failed }
        assertEquals(
            "That key was refused. Check that you copied all of it.",
            (state.connection as ConnectionState.Failed).message
        )
        assertEquals("", preferences.preferences.first().asrBaseUrl)
        assertFalse(state.canLeaveConnectStep)
    }

    @Test
    fun `editing the key after a failure clears the verdict`() = runTest {
        tls.server.enqueue(MockResponse().setResponseCode(401))
        viewModel.selectPreset(localPreset())
        viewModel.updateApiKey("gsk_wrong")
        viewModel.verifyAndSave()
        awaitState { it.connection is ConnectionState.Failed }

        viewModel.updateApiKey("gsk_wrong-but-longer")

        assertEquals(ConnectionState.Untested, viewModel.uiState.value.connection)
    }

    @Test
    fun `the ordinary path never asks for a base URL`() {
        viewModel.selectPreset(ProviderPresets.GROQ)

        val state = viewModel.uiState.value
        assertFalse(state.isCustomPreset)
        assertFalse(state.showAdvanced)
        assertEquals(ProviderPresets.GROQ.baseUrl, state.effectiveBaseUrl)
        // A key is the only thing typed on the recommended path.
        assertEquals("", state.baseUrl)
    }

    /**
     * F19, from the G3 smoke: signing out clears only `onboarding_complete`, so onboarding runs
     * again — but the key it already verified stays on disk in the same root and never reaches
     * the field. The Connect step therefore opens empty, `canVerify` refuses a blank field, and
     * the person is stuck at a step they already completed unless they can produce the key a
     * second time. Nothing was lost; it was simply not offered back.
     *
     * A key only ever reaches storage after the provider accepted it — `verifyAndSave` writes
     * nothing otherwise — so a stored key is by construction one that has worked, and the step
     * has an answer without another paid round trip. No provider is called here: the mock
     * server is given no response to serve, so a verification attempt would fail the test.
     */
    @Test
    fun `a key this root already verified comes back to the step that asks for it`() = runTest {
        preferences.updateAsrConfig(
            baseUrl = ProviderPresets.GROQ.baseUrl,
            apiKey = "gsk_stored-by-an-earlier-run",
            model = ProviderPresets.GROQ.asrModel
        )
        preferences.updateProviderPreset(ProviderPresets.GROQ.id)

        val reopened = buildViewModel().also { returning = it }

        val state = withContext(Dispatchers.Default) {
            withTimeout(TIMEOUT_MS) { reopened.uiState.first { it.apiKey.isNotBlank() } }
        }
        assertEquals("gsk_stored-by-an-earlier-run", state.apiKey)
        assertEquals(ProviderPresets.GROQ.id, state.preset.id)
        assertEquals(ProviderPresets.GROQ.asrModel, state.asrModel)
        assertTrue("the step must not refuse a key it has already accepted", state.canVerify)
        assertTrue(
            "and must not hold the person at a step they have already completed",
            state.canLeaveConnectStep
        )
        assertEquals(
            "no provider was called to establish that",
            0,
            tls.server.requestCount
        )
    }

    @Test
    fun `finishing marks onboarding complete`() = runTest {
        viewModel.finish()

        awaitState { it.finished }
        awaitPreferences { it.onboardingComplete }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L

        /** A minimal OpenAI-compatible completion, enough for the cleanup probe to succeed. */
        const val CHAT_OK =
            "{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}"
    }
}
