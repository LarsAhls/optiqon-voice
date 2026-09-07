package se.optiqon.voice.ui.onboarding

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import se.optiqon.voice.data.api.ApiClientFactory
import se.optiqon.voice.data.db.OptiqonVoiceDatabase
import se.optiqon.voice.data.preferences.testPreferencesDataStore
import se.optiqon.voice.data.repository.ProfileRepository
import se.optiqon.voice.domain.provider.ProviderPresets
import se.optiqon.voice.domain.provider.ProviderVerifier
import se.optiqon.voice.testing.TlsMockServer
import se.optiqon.voice.ui.theme.OptiqonVoiceTheme

/**
 * What the first run shows, rather than what it stores. The point of the recommended path is
 * that it asks for one thing — a key — so anything that reads as configuration (a base URL, a
 * model identifier, the words "OpenAI-compatible") must stay behind Advanced.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night")
class OnboardingScreenTest {

    @get:Rule val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var tls: TlsMockServer
    private lateinit var database: OptiqonVoiceDatabase
    private lateinit var viewModel: OnboardingViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        tls = TlsMockServer()
        val preferences = testPreferencesDataStore(context)
        database = Room.inMemoryDatabaseBuilder(context, OptiqonVoiceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        viewModel = OnboardingViewModel(
            preferencesDataStore = preferences,
            providerVerifier = ProviderVerifier(ApiClientFactory(tls.client)),
            profileRepository = ProfileRepository(
                database.profileDao(),
                database.postProcessingPromptDao(),
                preferences
            )
        )
    }

    @After
    fun tearDown() {
        viewModel.viewModelScope.cancel()
        // Deliberately not closed: cancelling is cooperative, and first-run seeding already
        // handed to Room lands after this returns. An in-memory database costs nothing to
        // leave behind, and closing it turns that race into a failure in the next test.
        tls.shutdown()
        Dispatchers.resetMain()
    }

    private fun showConnectStep() {
        composeRule.setContent {
            OptiqonVoiceTheme { OnboardingScreen(onFinished = {}, viewModel = viewModel) }
        }
        composeRule.onNodeWithText("Continue").performClick()
    }

    @Test
    fun `the first step asks for a language, in that language`() {
        composeRule.setContent {
            OptiqonVoiceTheme { OnboardingScreen(onFinished = {}, viewModel = viewModel) }
        }

        // Existence, not visibility: the step scrolls, and on a short screen the choices sit
        // below the heading that explains them.
        composeRule.onNodeWithText("Speak.", substring = true).assertIsDisplayed()
        // The eyebrow is drawn uppercase, so match how it reads rather than how it is typed.
        composeRule.onNodeWithText("Which language do you speak most?", ignoreCase = true)
            .assertExists()
        composeRule.onNodeWithText("Svenska").assertExists()
        composeRule.onNodeWithText("Let the app detect it").assertExists()
    }

    @Test
    fun `the connect step asks for a key and nothing that looks like configuration`() {
        showConnectStep()

        composeRule.onNodeWithText("API key").assertExists()
        composeRule.onNodeWithText("Groq").assertExists()

        composeRule.onNodeWithText("Base URL").assertDoesNotExist()
        composeRule.onNodeWithText("Transcription model").assertDoesNotExist()
        composeRule.onNodeWithText(ProviderPresets.GROQ.baseUrl, substring = true).assertDoesNotExist()
        composeRule.onNodeWithText(ProviderPresets.GROQ.asrModel, substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("OpenAI-compatible", substring = true).assertDoesNotExist()
    }

    @Test
    fun `Advanced is where the endpoint and the model live`() {
        showConnectStep()

        composeRule.onNodeWithText("Advanced").performClick()

        composeRule.onNodeWithText("Transcription model").assertExists()
        composeRule.onNodeWithText(ProviderPresets.CUSTOM.displayName).assertExists()
        // Still not a base URL: that field belongs to the custom preset, not to Advanced.
        composeRule.onNodeWithText("Base URL").assertDoesNotExist()

        composeRule.onNodeWithText(ProviderPresets.CUSTOM.displayName).performClick()

        composeRule.onNodeWithText("Base URL").assertExists()
        composeRule.onNodeWithText("HTTPS only. The app appends v1/audio/transcriptions.")
            .assertExists()
    }

    @Test
    fun `there is no way past the connect step until the key has been verified`() {
        showConnectStep()

        composeRule.onNodeWithText("Verify and save").assertExists()
        composeRule.onNodeWithText("Connected. Transcription is working.").assertDoesNotExist()
    }
}
