package se.optiqon.voice.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.optiqon.voice.data.db.OptiqonVoiceDatabase
import se.optiqon.voice.data.preferences.testPreferencesDataStore
import se.optiqon.voice.domain.model.TranscriptionLanguages

/**
 * A first run has to end with exactly one usable profile: the bubble reads the active profile
 * on every dictation, and an install with none, or with two, is a broken install.
 */
@RunWith(RobolectricTestRunner::class)
class ProfileSeedingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var database: OptiqonVoiceDatabase
    private lateinit var repository: ProfileRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, OptiqonVoiceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ProfileRepository(
            profileDao = database.profileDao(),
            promptDao = database.postProcessingPromptDao(),
            preferencesDataStore = testPreferencesDataStore(context)
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `a fresh database is seeded with one active Standard profile`() = runTest {
        repository.ensureDefaults()

        val profiles = database.profileDao().let { dao ->
            assertEquals(1, dao.countProfiles())
            dao.getActiveProfile()
        }
        assertNotNull(profiles)
        assertEquals("Standard", profiles!!.name)
        assertTrue(profiles.isActive)
        // Onboarding runs after the seed, so the profile starts on the app default, not Auto.
        assertEquals(TranscriptionLanguages.DEFAULT_CODE, profiles.language)
    }

    @Test
    fun `seeding twice does not add a second profile`() = runTest {
        repository.ensureDefaults()
        repository.ensureDefaults()

        assertEquals(1, database.profileDao().countProfiles())
        assertEquals(1, database.profileDao().activeCount())
    }

    @Test
    fun `the built-in prompts are seeded alongside the profile`() = runTest {
        repository.ensureDefaults()

        assertTrue(database.postProcessingPromptDao().builtInCount() > 0)
    }

    @Test
    fun `the language chosen during onboarding lands on the active profile`() = runTest {
        repository.ensureDefaults()

        repository.applyLanguageToActiveProfile("en")

        assertEquals("en", database.profileDao().getActiveProfile()?.language)
    }

    @Test
    fun `Auto detect is stored as no language, not as a word`() = runTest {
        repository.ensureDefaults()

        repository.applyLanguageToActiveProfile(null)

        assertEquals(null, database.profileDao().getActiveProfile()?.language)
    }

    @Test
    fun `the verified provider lands on the active profile`() = runTest {
        repository.ensureDefaults()

        repository.applyProviderToActiveProfile(
            asrModel = "whisper-large-v3-turbo",
            llmModel = "llama-3.1-8b-instant",
            llmEnabled = true
        )

        val active = database.profileDao().getActiveProfile()!!
        assertEquals("whisper-large-v3-turbo", active.asrModel)
        assertEquals("llama-3.1-8b-instant", active.llmModel)
        assertTrue(active.llmEnabled)
    }
}
