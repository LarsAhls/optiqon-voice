package se.optiqon.voice.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
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
import se.optiqon.voice.data.preferences.PreferencesDataStore
import se.optiqon.voice.data.preferences.testPreferencesDataStore
import se.optiqon.voice.data.repository.HistoryRepository
import se.optiqon.voice.data.repository.ProcessingRepository
import se.optiqon.voice.data.repository.ProfileRepository
import se.optiqon.voice.domain.processing.TextProcessor
import se.optiqon.voice.domain.provider.ProviderPresets
import se.optiqon.voice.domain.provider.ProviderVerifier
import se.optiqon.voice.domain.transcription.NetworkMonitor
import se.optiqon.voice.domain.transcription.TranscriptionManager
import se.optiqon.voice.domain.transcription.WhisperEngine
import se.optiqon.voice.testing.AccessFixture
import se.optiqon.voice.testing.TlsMockServer
import se.optiqon.voice.ui.history.HistoryViewModel
import se.optiqon.voice.ui.home.HomeScreen
import se.optiqon.voice.ui.home.HomeViewModel
import se.optiqon.voice.ui.onboarding.OnboardingScreen
import se.optiqon.voice.ui.onboarding.OnboardingViewModel
import se.optiqon.voice.ui.profiles.ProfilesScreen
import se.optiqon.voice.ui.profiles.ProfilesViewModel
import se.optiqon.voice.ui.settings.SettingsScreen
import se.optiqon.voice.ui.settings.SettingsViewModel
import se.optiqon.voice.ui.theme.OptiqonVoiceTheme

/**
 * Baselines for the screens the redesign is judged on, at the size and theme the design was
 * drawn for. These are records of what the app looks like, kept so a change to a colour, a
 * radius or a type ramp shows up as a picture rather than as a surprise on a phone.
 *
 * Nothing here talks to a provider: the only endpoint any of these view models could reach is
 * a local TLS server, and no screen shot below sends a request.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night")
class ScreenshotTest {

    @get:Rule val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var tls: TlsMockServer
    private lateinit var preferences: PreferencesDataStore
    private lateinit var database: OptiqonVoiceDatabase
    private lateinit var accessScope: CoroutineScope
    private val viewModels = mutableListOf<androidx.lifecycle.ViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        accessScope = CoroutineScope(UnconfinedTestDispatcher())
        tls = TlsMockServer()
        preferences = testPreferencesDataStore(context)
        database = Room.inMemoryDatabaseBuilder(context, OptiqonVoiceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        // The in-memory database is left open on purpose: cancelling is cooperative, and
        // seeding already handed to Room would otherwise fail after the close.
        tls.shutdown()
        accessScope.cancel()
        Dispatchers.resetMain()
    }

    private fun profileRepository() =
        ProfileRepository(database.profileDao(), database.postProcessingPromptDao(), preferences)

    private fun processingRepository() =
        ProcessingRepository(database.textReplacementRuleDao(), database.postProcessingPromptDao())

    private fun <T : androidx.lifecycle.ViewModel> remember(viewModel: T): T {
        viewModels += viewModel
        return viewModel
    }

    /**
     * The first run with its account step drawn as nothing and reported approved.
     *
     * A baseline image of the account screen would be a baseline of the access graph, which a
     * Robolectric `ComponentActivity` has no Hilt component for; the account screens have their
     * own tests. Approving here decides nothing — the production default reads the server.
     */
    @Composable
    private fun onboarding(viewModel: OnboardingViewModel) {
        OnboardingScreen(
            onFinished = {},
            viewModel = viewModel,
            accountStep = {},
            accountApproved = { true }
        )
    }

    /** One capture, on the app's own background so the shot is not transparent behind. */
    private fun capture(name: String, content: @Composable () -> Unit) {
        composeRule.setContent {
            OptiqonVoiceTheme {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) { content() }
            }
        }
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test
    fun `home`() {
        val home = remember(
            HomeViewModel(
                database.dictationDao(),
                database.lifetimeStatsDao(),
                profileRepository(),
                preferences
            )
        )
        val history = remember(HistoryViewModel(historyRepository()))
        capture("home") {
            HomeScreen(
                outerPadding = PaddingValues(),
                viewModel = home,
                historyViewModel = history,
                // Drawn as nothing: the banner needs the access graph, and a baseline of a
                // healthy session shows no banner anyway.
                sessionBanner = {}
            )
        }
    }

    @Test
    fun `profiles`() {
        // The list, not the empty state: what this baseline is for is the profile card, and
        // the seeded Standard profile is exactly what a user has after the first run.
        runBlocking { profileRepository().ensureDefaults() }
        val profiles = remember(ProfilesViewModel(profileRepository(), processingRepository()))
        capture("profiles") { ProfilesScreen(outerPadding = PaddingValues(), viewModel = profiles) }
        composeRule.waitUntil(VERIFY_TIMEOUT_MS) {
            composeRule.onAllNodesWithText("Standard").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/profiles.png")
    }

    @Test
    fun `settings`() {
        val settings = remember(
            SettingsViewModel(
                preferences,
                ApiClientFactory(tls.client),
                processingRepository(),
                ProviderVerifier(ApiClientFactory(tls.client))
            )
        )
        capture("settings") {
            SettingsScreen(
                outerPadding = PaddingValues(),
                viewModel = settings,
                accountSection = {}
            )
        }
    }

    @Test
    fun `onboarding language step`() {
        capture("onboarding_1_language") {
            onboarding(onboardingViewModel())
        }
    }

    @Test
    fun `onboarding connect step`() {
        val viewModel = onboardingViewModel()
        capture("onboarding_2_connect") { onboarding(viewModel) }
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/onboarding_2_connect.png")
    }

    @Test
    fun `onboarding permissions step`() {
        val viewModel = onboardingViewModel()
        capture("onboarding_3_permissions") { onboarding(viewModel) }
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.onNodeWithText("Continue").performClick()

        // The third step is only reachable once an endpoint has actually transcribed
        // something, which is the whole point of the second one. The local server answers
        // twice: the transcription probe, then the cleanup-model probe behind it.
        tls.server.enqueue(MockResponse().setResponseCode(200).setBody("{\"text\":\"\"}"))
        tls.server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}")
        )
        viewModel.selectPreset(ProviderPresets.GROQ.copy(baseUrl = tls.baseUrl))
        viewModel.updateApiKey("gsk_not-a-real-key")
        viewModel.verifyAndSave()
        composeRule.waitUntil(VERIFY_TIMEOUT_MS) { viewModel.uiState.value.canLeaveConnectStep }
        viewModel.next()

        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/onboarding_3_permissions.png")
    }

    private fun onboardingViewModel(): OnboardingViewModel = remember(
        OnboardingViewModel(
            preferencesDataStore = preferences,
            providerVerifier = ProviderVerifier(ApiClientFactory(tls.client)),
            profileRepository = profileRepository()
        )
    )

    private fun historyRepository(): HistoryRepository {
        val clientFactory = ApiClientFactory(tls.client)
        val transcriptionManager = TranscriptionManager(
            context,
            WhisperEngine(clientFactory, preferences, NetworkMonitor(context)),
            TextProcessor(clientFactory, preferences, processingRepository()),
            database.dictationDao(),
            database.lifetimeStatsDao(),
            preferences,
            profileRepository(),
            // Never exercised: no screenshot below starts a dictation or a retry. It is here
            // because the gate is a constructor argument, which is the point — a new call site
            // cannot forget it.
            AccessFixture(context, accessScope).guard
        )
        return HistoryRepository(context, database.dictationDao(), transcriptionManager)
    }

    private companion object {
        const val VERIFY_TIMEOUT_MS = 10_000L
    }
}
