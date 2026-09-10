package se.optiqon.voice.data.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.optiqon.voice.data.db.OptiqonVoiceDatabase
import se.optiqon.voice.data.db.entity.Dictation
import se.optiqon.voice.data.preferences.PreferencesDataStore
import se.optiqon.voice.data.preferences.SecurePreferencesStore
import se.optiqon.voice.di.DatabaseModule
import java.io.File
import java.security.MessageDigest

/**
 * What separates two accounts on one device.
 *
 * A hidden list is not isolation. Filtering by an owner column is only as reliable as the next
 * query someone writes, and a background job, a migration or a repository added later cannot be
 * relied on to remember a rule it was never told about. So the separation is placed at the
 * storage handle: a second account opens a different database file, different preference files
 * and a different audio directory, and is empty for the same reason a fresh install is empty.
 *
 * These tests use the production names and the production builders. The first owner's data is
 * hashed before and after the second account writes, because "the second account cannot see it"
 * and "the second account cannot damage it" are two different promises.
 */
@RunWith(RobolectricTestRunner::class)
class StorageRootIsolationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val owner = StorageRoot.DEFAULT
    private val second = StorageRoot("u1")

    /** The production builder, including the real migration list. */
    private fun database(root: StorageRoot): OptiqonVoiceDatabase =
        Room.databaseBuilder(context, OptiqonVoiceDatabase::class.java, root.databaseName)
            .addMigrations(*DatabaseModule.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()

    /** Room's own `close` rather than `use`: [OptiqonVoiceDatabase] is not [AutoCloseable]. */
    private suspend fun <T> withDatabase(
        root: StorageRoot,
        block: suspend (OptiqonVoiceDatabase) -> T
    ): T {
        val db = database(root)
        try {
            return block(db)
        } finally {
            db.close()
        }
    }

    private fun databaseFile(root: StorageRoot): File = context.getDatabasePath(root.databaseName)

    private fun sha256(file: File): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes())

    private fun dictation(text: String) = Dictation(
        text = text,
        rawText = text,
        wordCount = 1,
        durationMs = 1_000L
    )

    @Test
    fun `a second account sees none of the first owner's history`() = runTest {
        withDatabase(owner) { it.dictationDao().insert(dictation("mitt privata")) }

        val visibleToSecond = withDatabase(second) { it.dictationDao().getRecent().first() }

        assertTrue("a different root is empty because it is different files", visibleToSecond.isEmpty())
    }

    @Test
    fun `writing under a second account leaves the first owner's database byte-identical`() = runTest {
        withDatabase(owner) { it.dictationDao().insert(dictation("mitt privata")) }
        val before = sha256(databaseFile(owner))

        withDatabase(second) { db ->
            db.dictationDao().insert(dictation("nagon annans"))
            db.dictationDao().getRecent().first()
        }

        assertArrayEquals(
            "the second account must not be able to touch the first owner's file at all",
            before,
            sha256(databaseFile(owner))
        )
        val ownerRows = withDatabase(owner) { it.dictationDao().getRecent().first() }
        assertEquals(1, ownerRows.size)
    }

    @Test
    fun `settings do not cross roots`() = runTest {
        val ownerPrefs = PreferencesDataStore(context, secureStore(owner), owner)
        val secondPrefs = PreferencesDataStore(context, secureStore(second), second)

        ownerPrefs.updateAsrConfig("https://owner.example", "owner-key", "whisper-1")

        assertEquals("https://owner.example", ownerPrefs.preferences.first().asrBaseUrl)
        assertNotEquals(
            "the active provider configuration is part of what must not leak",
            "https://owner.example",
            secondPrefs.preferences.first().asrBaseUrl
        )
    }

    @Test
    fun `api keys do not cross roots`() {
        val ownerKeys = secureStore(owner)
        val secondKeys = secureStore(second)

        ownerKeys.updateAsrApiKey("sk-owner")

        assertEquals("sk-owner", ownerKeys.getAsrApiKey())
        assertEquals("", secondKeys.getAsrApiKey())
    }

    @Test
    fun `retained audio directories are distinct`() {
        val filesDir = context.filesDir
        val ownerDir = owner.retainedAudioDir(filesDir)
        val secondDir = second.retainedAudioDir(filesDir)

        ownerDir.mkdirs()
        File(ownerDir, "clip.m4a").writeBytes(byteArrayOf(1, 2, 3))

        assertNotEquals(ownerDir, secondDir)
        assertFalse("audio is a recording of somebody's voice; it is the last thing that may be shared",
            File(secondDir, "clip.m4a").exists())
    }

    @Test
    fun `the default root keeps the names this app has always used`() {
        // The upgrade path in one assertion: an install that predates accounts must open the
        // files it already has, not a new empty set beside them.
        assertEquals("optiqon_voice.db", owner.databaseName)
        assertEquals("settings", owner.preferencesName)
        assertEquals("secure_settings", owner.securePreferencesName)
        assertEquals(File(context.filesDir, "retained_audio"), owner.retainedAudioDir(context.filesDir))
    }

    /**
     * The production store with only its encryption replaced: `EncryptedSharedPreferences`
     * needs the AndroidKeyStore, which does not exist off-device. The file *name* still comes
     * from the same root, which is the part these tests are about. Encryption at rest is not
     * exercised here and is not claimed.
     */
    private fun secureStore(root: StorageRoot): SecurePreferencesStore =
        object : SecurePreferencesStore(context, root) {
            override val sharedPreferences: SharedPreferences =
                context.getSharedPreferences(root.securePreferencesName, Context.MODE_PRIVATE)
        }
}
