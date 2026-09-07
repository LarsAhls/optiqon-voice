package se.optiqon.voice.data.preferences

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.optiqon.voice.domain.provider.ProviderPresets

/**
 * The flag that decides whether onboarding is shown at all: a fresh install must go through
 * setup, and an upgrade from a build that predates the flag must not be dragged back through
 * it. Each test gets a store of its own, so "fresh install" really is one.
 */
@RunWith(RobolectricTestRunner::class)
class OnboardingFlagTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: PreferencesDataStore

    @Before
    fun setUp() {
        store = testPreferencesDataStore(context)
    }

    @Test
    fun `a fresh install has not been onboarded`() = runTest {
        assertFalse(store.preferences.first().onboardingComplete)
    }

    @Test
    fun `an upgrade that already has an endpoint counts as onboarded`() = runTest {
        store.updateAsrConfig("https://api.groq.com/openai/", "", "whisper-large-v3-turbo")

        assertTrue(store.preferences.first().onboardingComplete)
    }

    @Test
    fun `an explicit answer beats the endpoint guess, in both directions`() = runTest {
        store.updateAsrConfig("https://api.groq.com/openai/", "", "whisper-large-v3-turbo")

        store.setOnboardingComplete(false)
        assertFalse(store.preferences.first().onboardingComplete)

        store.setOnboardingComplete(true)
        assertTrue(store.preferences.first().onboardingComplete)
    }

    @Test
    fun `finishing onboarding sticks`() = runTest {
        store.setOnboardingComplete(true)

        assertTrue(store.preferences.first().onboardingComplete)
    }

    @Test
    fun `a fresh install has no provider preset until one is chosen`() = runTest {
        assertEquals(ProviderPresets.CUSTOM.id, store.preferences.first().providerPresetId)

        store.updateProviderPreset(ProviderPresets.GROQ.id)

        assertEquals(ProviderPresets.GROQ.id, store.preferences.first().providerPresetId)
    }
}
