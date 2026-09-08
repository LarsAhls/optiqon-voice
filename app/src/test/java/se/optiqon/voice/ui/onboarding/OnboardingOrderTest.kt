package se.optiqon.voice.ui.onboarding

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.optiqon.voice.data.api.ApiClientFactory
import se.optiqon.voice.data.db.OptiqonVoiceDatabase
import se.optiqon.voice.data.preferences.PreferencesDataStore
import se.optiqon.voice.data.preferences.testPreferencesDataStore
import se.optiqon.voice.data.repository.ProfileRepository
import se.optiqon.voice.domain.provider.ProviderVerifier
import se.optiqon.voice.testing.TlsMockServer
import se.optiqon.voice.ui.navigation.RootViewModel
import se.optiqon.voice.ui.navigation.StartDestination

/**
 * Where the account question sits in the first run, and who is sent through the first run at all.
 *
 * Both halves are about the same failure. Asking for a provider key before the account exists
 * means the very first outgoing request of a fresh install is made on behalf of somebody the
 * server has not approved — and sending an install that has already been onboarded back to the
 * start means an existing user is asked to redo work that is already done, which is exactly
 * what the upgrade path must not do.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OnboardingOrderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var tls: TlsMockServer
    private lateinit var preferences: PreferencesDataStore
    private lateinit var database: OptiqonVoiceDatabase
    private lateinit var viewModel: OnboardingViewModel

    @Before
    fun setUp() {
        // Unconfined: the language step writes to DataStore and the database, neither of which
        // virtual time can advance.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        tls = TlsMockServer()
        preferences = testPreferencesDataStore(context)
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
        database.close()
        tls.shutdown()
        Dispatchers.resetMain()
    }

    private fun step() = viewModel.uiState.value.step

    @Test
    fun `the account step comes between language and the speech service`() = runTest {
        assertEquals(OnboardingStep.LANGUAGE, step())
        viewModel.next()
        assertEquals(OnboardingStep.ACCOUNT, step())
        viewModel.next()
        assertEquals(OnboardingStep.CONNECT, step())
    }

    /**
     * The structural half of the claim: by the time the account question has been reached, the
     * app has made no outgoing provider request at all. There is no key to make one with yet,
     * and that is the point — the ordering removes the call rather than guarding it.
     */
    @Test
    fun `nothing is sent to a provider before the account step`() = runTest {
        viewModel.next()
        assertEquals(OnboardingStep.ACCOUNT, step())
        assertEquals(0, tls.server.requestCount)
    }

    @Test
    fun `going back from the speech service returns to the account step`() = runTest {
        viewModel.next()
        viewModel.next()
        assertEquals(OnboardingStep.CONNECT, step())

        viewModel.back()
        assertEquals(OnboardingStep.ACCOUNT, step())
        viewModel.back()
        assertEquals(OnboardingStep.LANGUAGE, step())
    }

    /** An unverified endpoint still cannot be left behind; moving the account did not relax it. */
    @Test
    fun `the speech service step still cannot be left unverified`() = runTest {
        viewModel.next()
        viewModel.next()
        viewModel.next()
        assertEquals(OnboardingStep.CONNECT, step())
    }

    @Test
    fun `an install that has finished onboarding opens the app, not the first run`() = runTest {
        preferences.setOnboardingComplete(true)
        val root = RootViewModel(preferences)
        assertEquals(
            StartDestination.MAIN,
            root.startDestination.first { it != null }
        )
    }

    /** The control: a fresh install does go through the first run. */
    @Test
    fun `a fresh install goes through the first run`() = runTest {
        val root = RootViewModel(preferences)
        assertEquals(
            StartDestination.ONBOARDING,
            root.startDestination.first { it != null }
        )
    }
}
