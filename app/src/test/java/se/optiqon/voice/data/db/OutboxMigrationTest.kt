package se.optiqon.voice.data.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.optiqon.voice.di.DatabaseModule
import java.io.File

/**
 * The negative tests §3.23 and §3.24: upgrading to the build that introduces accounts must not
 * cost an existing tester their profile or their dictation history, and the rows they already
 * had must not be attributed to whichever account signs in afterwards.
 *
 * A destructive fallback would make this test unnecessary and the upgrade unacceptable, which
 * is why the outbox arrives through a real migration.
 *
 * The version 7 database is built from the exported schema rather than from a hand-copied
 * `CREATE TABLE`, so it is the schema the previous release actually shipped. Room then opens
 * the migrated file and validates it against the compiled version 8 schema — an incorrect
 * migration fails there, not in an assertion we remembered to write.
 */
@RunWith(RobolectricTestRunner::class)
class OutboxMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var name: String

    @Before
    fun setUp() {
        name = "migration-test-${System.nanoTime()}.db"
    }

    @After
    fun tearDown() {
        context.deleteDatabase(name)
    }

    @Test
    fun `upgrading from 7 to 8 keeps profiles, history and the rest of the tester's data`() = runTest {
        createVersion7 { db ->
            db.insert(
                "profiles",
                CONFLICT_ABORT,
                contentValues(
                    "name" to "Lars arbetsprofil",
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
                    "text" to "En diktering från före inloggningen",
                    "rawText" to "en diktering fran fore inloggningen",
                    "timestamp" to 1_700_000_000_000L,
                    "durationMs" to 4_000L,
                    "wordCount" to 6,
                    "historyVisible" to 1,
                    "status" to "SUCCESS"
                )
            )
            db.insert("text_replacement_rules", CONFLICT_ABORT, contentValues(
                "name" to "Optiqon",
                "pattern" to "optikon",
                "replacement" to "Optiqon",
                "isRegex" to 0,
                "createdAt" to 1L
            ))
        }

        val db = openAtVersion8()

        assertEquals("Lars arbetsprofil", db.profileDao().observeProfiles().first().single().name)
        assertEquals(1, db.dictationDao().getRecent().first().size)
        assertEquals(1, db.textReplacementRuleDao().getAll().size)
        db.close()
    }

    @Test
    fun `the outbox arrives empty, so no old row is attributed to a new account`() = runTest {
        createVersion7 { db ->
            db.insert(
                "dictations",
                CONFLICT_ABORT,
                contentValues(
                    "text" to "En diktering från före inloggningen",
                    "rawText" to "en diktering fran fore inloggningen",
                    "timestamp" to 1_700_000_000_000L,
                    "durationMs" to 4_000L,
                    "wordCount" to 6,
                    "historyVisible" to 1,
                    "status" to "SUCCESS"
                )
            )
        }

        val db = openAtVersion8()

        // Existing history is not queued for upload and belongs to no uid: it predates the
        // notion of one, and guessing an owner is how one tester's recordings end up on
        // another tester's account.
        assertTrue(db.outboxDao().all().isEmpty())
        assertEquals(1, db.dictationDao().getRecent().first().size)
        db.close()
    }

    @Test
    fun `the migrated database really is at version 8 and accepts outbox rows`() = runTest {
        createVersion7 { }

        val db = openAtVersion8()
        db.outboxDao().insert(
            se.optiqon.voice.data.db.entity.OutboxEntry(
                id = "1",
                ownerUid = "uid-a",
                kind = "case_create",
                payload = "{}",
                createdAtMs = 1L,
                state = se.optiqon.voice.data.db.entity.OutboxState.PENDING
            )
        )

        assertEquals(listOf("1"), db.outboxDao().pendingFor("uid-a").map { it.id })
        db.close()
    }

    /** Opening through Room runs the real migration chain and validates the result. */
    private fun openAtVersion8(): OptiqonVoiceDatabase =
        Room.databaseBuilder(context, OptiqonVoiceDatabase::class.java, name)
            .addMigrations(*DatabaseModule.ALL_MIGRATIONS)
            .build()
            .also { assertNotNull(it.openHelper.writableDatabase) }

    /** Writes the version 7 file exactly as the previous release left it, then fills it. */
    private fun createVersion7(fill: (SupportSQLiteDatabase) -> Unit) {
        val schema = JSONObject(schemaFile(7).readText()).getJSONObject("database")
        val entities = schema.getJSONArray("entities")

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(7) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        for (i in 0 until entities.length()) {
                            val entity = entities.getJSONObject(i)
                            val table = entity.getString("tableName")
                            db.execSQL(entity.getString("createSql").replace(TABLE_NAME, table))
                            val indices = entity.optJSONArray("indices") ?: continue
                            for (j in 0 until indices.length()) {
                                db.execSQL(
                                    indices.getJSONObject(j).getString("createSql")
                                        .replace(TABLE_NAME, table)
                                )
                            }
                        }
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS room_master_table " +
                                "(id INTEGER PRIMARY KEY, identity_hash TEXT)"
                        )
                        db.execSQL(
                            "INSERT OR REPLACE INTO room_master_table (id, identity_hash) " +
                                "VALUES(42, '${schema.getString("identityHash")}')"
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
                })
                .build()
        )

        helper.writableDatabase.use(fill)
        helper.close()
    }

    private fun schemaFile(version: Int): File {
        val relative = "schemas/se.optiqon.voice.data.db.OptiqonVoiceDatabase/$version.json"
        // Depending on how the tests are launched the working directory is either the module
        // or the root of the checkout.
        return listOf(File(relative), File("app/$relative")).firstOrNull { it.isFile }
            ?: error("Exported schema $version.json not found from ${File(".").absolutePath}")
    }

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

    private companion object {
        const val TABLE_NAME = "\${TABLE_NAME}"
    }
}
