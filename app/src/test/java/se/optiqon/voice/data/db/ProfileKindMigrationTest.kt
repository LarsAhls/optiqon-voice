package se.optiqon.voice.data.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.optiqon.voice.data.db.entity.ProfileEntity
import se.optiqon.voice.di.DatabaseModule
import se.optiqon.voice.domain.model.ProfileKind

/**
 * Mission 2's upgrade step, 8 → 9: a tester who installs the typed-profiles build keeps every
 * profile, and every one of them lands on [ProfileKind.GENERAL].
 *
 * GENERAL is load-bearing rather than merely tidy: it is the one kind whose system prompt is
 * unchanged from before the column existed (asserted in
 * `se.optiqon.voice.domain.processing.ProfileKindPromptTest`). A migration that guessed instead —
 * a profile named "Mail" becoming EMAIL — would silently change what the tester's next dictation
 * comes out as, with no event they could connect it to.
 *
 * The version 8 file is built from the exported schema, so it is what the previous release actually
 * shipped, and Room opens the result and validates it against the compiled version 9 schema. A
 * wrong `ALTER` fails there rather than in an assertion someone remembered to write.
 */
@RunWith(RobolectricTestRunner::class)
class ProfileKindMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var name: String

    @Before
    fun setUp() {
        name = "profilekind-migration-${System.nanoTime()}.db"
    }

    @After
    fun tearDown() {
        context.deleteDatabase(name)
    }

    @Test
    fun `upgrading from 8 to 9 gives every existing profile the GENERAL kind`() = runTest {
        createVersion8 { db ->
            // Names that a heuristic would be tempted by, which is exactly why they are here.
            db.insert("profiles", CONFLICT_ABORT, profileValues(name = "Mail", active = 1))
            db.insert("profiles", CONFLICT_ABORT, profileValues(name = "Slack-chat"))
            db.insert("profiles", CONFLICT_ABORT, profileValues(name = "Anteckningar"))
        }

        val db = openAtVersion9()
        val profiles = db.profileDao().observeProfiles().first()

        assertEquals("no profile may be lost by the upgrade", 3, profiles.size)
        profiles.forEach { entity ->
            assertEquals(
                "${entity.name} was reclassified by the migration",
                ProfileKind.GENERAL.name,
                entity.profileKind
            )
            assertEquals(
                "${entity.name} does not read back as GENERAL in the domain model",
                ProfileKind.GENERAL,
                entity.toDomain().profileKind
            )
        }
        db.close()
    }

    @Test
    fun `the rest of each profile survives the new column untouched`() = runTest {
        createVersion8 { db ->
            db.insert(
                "profiles",
                CONFLICT_ABORT,
                profileValues(name = "Lars arbetsprofil", active = 1).apply {
                    put("language", "sv")
                    put("llmEnabled", 1)
                    put("profilePrompt", "Skriv kort.")
                    put("outputStyle", "CONCISE")
                    put("rewriteMode", "POLISH")
                    put("summarizeMode", "LIGHT")
                    put("emojiAllowed", 1)
                }
            )
        }

        val db = openAtVersion9()
        val profile = db.profileDao().observeProfiles().first().single().toDomain()

        assertEquals("Lars arbetsprofil", profile.name)
        assertEquals("sv", profile.language)
        assertTrue(profile.llmEnabled)
        assertEquals("Skriv kort.", profile.profilePrompt)
        assertTrue(profile.isActive)
        assertTrue(profile.emojiAllowed)
        assertEquals(ProfileKind.GENERAL, profile.profileKind)
        db.close()
    }

    /**
     * The column is `NOT NULL` with a DEFAULT only on the `ALTER`. A write through Room after the
     * migration must therefore supply a value, and a declared kind must survive the round trip —
     * otherwise the field would be writable in the editor and lost on the next read.
     */
    @Test
    fun `a kind written after the migration is stored and read back`() = runTest {
        createVersion8()

        val db = openAtVersion9()
        val dao = db.profileDao()
        val id = dao.insert(ProfileEntity(name = "E-post", profileKind = ProfileKind.EMAIL.name))

        val stored = requireNotNull(dao.getProfile(id)) { "the inserted profile must be readable" }
        assertEquals(ProfileKind.EMAIL.name, stored.profileKind)
        assertEquals(ProfileKind.EMAIL, stored.toDomain().profileKind)
        db.close()
    }

    /**
     * A value no build knows — a profile written by a later release, or a row edited by hand —
     * must read as GENERAL rather than crash the profile list. That is what `enumValueOrDefault`
     * is for, and it only protects the read path if the column really is plain text.
     */
    @Test
    fun `an unknown kind in the column degrades to GENERAL instead of throwing`() = runTest {
        createVersion8 { db ->
            db.insert("profiles", CONFLICT_ABORT, profileValues(name = "Framtid", active = 1))
        }

        val db = openAtVersion9()
        db.openHelper.writableDatabase.execSQL("UPDATE profiles SET profileKind = 'LETTERPRESS'")
        val profile = db.profileDao().observeProfiles().first().single()

        assertEquals("LETTERPRESS", profile.profileKind)
        assertEquals(ProfileKind.GENERAL, profile.toDomain().profileKind)
        db.close()
    }

    /** Writes the version 8 file exactly as the previous release left it, then fills it. */
    private fun createVersion8(fill: (SupportSQLiteDatabase) -> Unit = {}) =
        ExportedSchema.createDatabase(context, name, version = 8, fill = fill)

    /** Opening through Room runs the real migration chain and validates the result. */
    private fun openAtVersion9(): OptiqonVoiceDatabase =
        Room.databaseBuilder(context, OptiqonVoiceDatabase::class.java, name)
            .addMigrations(*DatabaseModule.ALL_MIGRATIONS)
            .build()
            .also { assertNotNull(it.openHelper.writableDatabase) }

    /** A version 8 `profiles` row: every column the schema requires, and no `profileKind`. */
    private fun profileValues(name: String, active: Int = 0) = ContentValues().apply {
        put("name", name)
        put("isActive", active)
        put("asrModel", "whisper-large-v3-turbo")
        putNull("language")
        put("llmEnabled", 0)
        put("llmModel", "gpt-4o-mini")
        put("profilePrompt", "")
        put("selectedRuleIds", "")
        put("selectedPromptIds", "")
        put("createdAt", 1L)
        put("updatedAt", 2L)
        put("outputStyle", "STANDARD")
        put("rewriteMode", "FIX")
        put("summarizeMode", "NONE")
        put("emojiAllowed", 0)
    }
}
