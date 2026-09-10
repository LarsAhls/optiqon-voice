package se.optiqon.voice.data.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.optiqon.voice.data.storage.StorageRoot
import se.optiqon.voice.di.DatabaseModule

/**
 * Mission L1, safety invariant 1: a build whose Room version is *lower* than the file it opens
 * must refuse, not erase.
 *
 * `fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)` used to be on, which meant
 * that installing an older APK over a newer one dropped every table — the tester's profiles,
 * rules and history — without a word. That is now removed. Room's own behaviour on a version
 * it cannot migrate to is an `IllegalStateException` from `RoomOpenHelper`, and the file is
 * left as it was.
 *
 * The "newer" file is the current version 8 schema with `user_version` set to 9. There is no
 * version 9 schema (that number is reserved for a later mission), and this test must not
 * invent one — what it proves is the *guard*, not a migration.
 */
@RunWith(RobolectricTestRunner::class)
class DowngradeGuardTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var root: StorageRoot
    private lateinit var name: String

    @Before
    fun setUp() {
        root = StorageRoot("downgradeguard${System.nanoTime()}")
        name = root.databaseName
    }

    @After
    fun tearDown() {
        context.deleteDatabase(name)
    }

    @Test
    fun `opening a newer database with this build fails closed and keeps every row`() {
        createNewerFile { db ->
            db.insert(
                "profiles",
                CONFLICT_ABORT,
                contentValues(
                    "name" to "Framtidsprofil",
                    "isActive" to 1,
                    "asrModel" to "whisper-large-v3-turbo",
                    "language" to "sv",
                    "llmEnabled" to 1,
                    "llmModel" to "claude",
                    "profilePrompt" to "Skriv kort.",
                    "selectedRuleIds" to "",
                    "selectedPromptIds" to "",
                    "createdAt" to 1L,
                    "updatedAt" to 2L,
                    "outputStyle" to "STANDARD",
                    "rewriteMode" to "FIX",
                    "summarizeMode" to "NONE",
                    "emojiAllowed" to 0
                )
            )
            db.insert(
                "dictations",
                CONFLICT_ABORT,
                contentValues(
                    "text" to "En diktering skriven av en nyare version",
                    "rawText" to "en diktering skriven av en nyare version",
                    "timestamp" to 1_700_000_000_000L,
                    "durationMs" to 4_000L,
                    "wordCount" to 7,
                    "historyVisible" to 1,
                    "status" to "SUCCESS"
                )
            )
        }

        // Through the production provider, not a builder of our own: the guard is a property
        // of how the app configures Room, and a test with its own builder would stay green
        // with the destructive fallback still in place (it did, before this line).
        val db = DatabaseModule.provideDatabase(context, root)
        try {
            db.openHelper.writableDatabase
            fail("a version 9 file must not be opened by a version 8 build")
        } catch (expected: IllegalStateException) {
            // Room: "A migration from 9 to 8 was required but not found" — the documented
            // fail-closed path, now that the destructive fallback is gone.
            assertTrue(expected.message.orEmpty(), expected.message.orEmpty().contains("9 to 8"))
        } finally {
            db.close()
        }

        // The refusal left the file exactly as the newer build wrote it.
        val raw = SQLiteDatabase.openDatabase(
            context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY
        )
        raw.use {
            assertEquals(9, it.version)
            assertEquals(1, count(it, "profiles"))
            assertEquals(1, count(it, "dictations"))
            val tables = it.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table'",
                null
            ).use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toSet() }
            for (expected in listOf("profiles", "dictations", "text_replacement_rules", "outbox")) {
                assertTrue("table $expected must survive a refused downgrade", expected in tables)
            }
        }
    }

    private fun count(db: SQLiteDatabase, table: String): Int =
        db.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getInt(0) }

    /** The current (version 8) schema, stamped as version 9, as a newer build would leave it. */
    private fun createNewerFile(fill: (SupportSQLiteDatabase) -> Unit) =
        ExportedSchema.createDatabase(context, name, version = 8, stampedVersion = 9, fill = fill)

    private fun contentValues(vararg pairs: Pair<String, Any?>) = ContentValues().apply {
        pairs.forEach { (key, value) ->
            when (value) {
                null -> putNull(key)
                is Int -> put(key, value)
                is Long -> put(key, value)
                is String -> put(key, value)
                else -> error("Unsupported column type for $key")
            }
        }
    }
}
