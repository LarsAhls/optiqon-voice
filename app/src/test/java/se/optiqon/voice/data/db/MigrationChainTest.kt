package se.optiqon.voice.data.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.optiqon.voice.data.storage.StorageRoot
import se.optiqon.voice.di.DatabaseModule

/**
 * The migrations are the one part of this app that can destroy data a tester cannot get back.
 * Eight schema versions shipped with seven hand-written migrations, and until now only the last
 * step — 7 to 8 — had a test (see [OutboxMigrationTest]). The six steps before it were carried by
 * nothing but the fact that nobody had reported losing anything.
 *
 * This is the missing half:
 *
 *  - every step is run on a database built from the schema Room exported for the version before
 *    it, and the result is compared against the schema exported for the version after it, so a
 *    migration that lands on a different column type, default, primary key or index fails here
 *    rather than on a phone;
 *  - a database written by version 1 is carried the whole way to 8 with rows in it, because a
 *    chain of individually correct steps can still lose data in the middle;
 *  - the only migration that moves data rather than shape — 6 to 7, which seeds the lifetime
 *    counters — is checked on its arithmetic, not just on its DDL.
 *
 * The migrations come from [DatabaseModule.ALL_MIGRATIONS] and run through `onUpgrade`, which is
 * where a phone runs them. A copy of the SQL kept in the test would let the app ship a migration
 * this test never sees.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationChainTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val names = mutableListOf<String>()

    @After
    fun tearDown() {
        names.forEach { context.deleteDatabase(it) }
    }

    @Test
    fun `every step lands exactly on the schema its own version file describes`() {
        for (from in 1 until CURRENT_VERSION) {
            val to = from + 1

            val migrated = nameFor("step-$from-to-$to")
            ExportedSchema.createDatabase(context, migrated, from)
            val actual = migrate(migrated, to).use { it.readStructure() }

            val reference = nameFor("reference-$to")
            ExportedSchema.createDatabase(context, reference, to)
            val expected = open(reference, to).use { it.readStructure() }

            assertEquals(
                "migrating $from to $to must produce the schema in $to.json",
                expected,
                actual.ignoringDefaultsNotIn(expected)
            )
        }
    }

    @Test
    fun `a dictation written by version 1 survives every step to version 8`() {
        val name = nameFor("chain-from-1")
        ExportedSchema.createDatabase(context, name, 1) { db ->
            // Version 1 knew nothing about status or visibility; those columns arrive at 4, and
            // their defaults decide whether this row is still history afterwards or quietly
            // disappears from the list the tester can see.
            db.insert(
                "dictations",
                CONFLICT_ABORT,
                values(
                    "text" to "En diktering från den allra första versionen",
                    "rawText" to "en diktering fran den allra forsta versionen",
                    "wordCount" to 7,
                    "timestamp" to 1_600_000_000_000L,
                    "durationMs" to 5_000L
                )
            )
            db.insert(
                "dictionary_words",
                CONFLICT_ABORT,
                values("word" to "Optiqon", "category" to "names")
            )
        }

        // Through Room, so the end of the chain is validated by Room's own schema check against
        // the compiled entities — the same check that runs on the phone.
        val db = Room.databaseBuilder(context, OptiqonVoiceDatabase::class.java, name)
            .addMigrations(*DatabaseModule.ALL_MIGRATIONS)
            .build()
        val raw = db.openHelper.writableDatabase
        assertNotNull(raw)

        try {
            raw.query("SELECT text, wordCount, durationMs, historyVisible, status FROM dictations")
                .use {
                    assertTrue("the version 1 dictation must still be there", it.moveToFirst())
                    assertEquals("En diktering från den allra första versionen", it.getString(0))
                    assertEquals(7, it.getInt(1))
                    assertEquals(5_000L, it.getLong(2))
                    assertEquals("a migrated row must stay visible in history", 1, it.getInt(3))
                    assertEquals("SUCCESS", it.getString(4))
                    assertFalse("exactly one row was written", it.moveToNext())
                }

            // The row predates the counters by six versions and still has to be counted in them,
            // because the home screen shows those figures and not a sum over the history table.
            raw.query("SELECT dictationCount, wordCount FROM lifetime_stats WHERE id = 0").use {
                assertTrue("version 7 must leave a counters row behind", it.moveToFirst())
                assertEquals(1, it.getInt(0))
                assertEquals(7, it.getInt(1))
            }

            // Migration 3 to 4 retires the old dictionary. Its rows are meant to go; what must
            // not happen is the table surviving as a ghost that later versions still read.
            assertFalse(
                "dictionary_words must be gone by version 8",
                tableExists(raw, "dictionary_words")
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun `the lifetime counters are seeded from the rows a version 6 database already had`() {
        val name = nameFor("seed-6-to-7")
        ExportedSchema.createDatabase(context, name, 6) { db ->
            insertVersion6Dictation(db, words = 4, durationMs = 3_000L, at = 1_700_000_000_000L, status = "SUCCESS")
            insertVersion6Dictation(db, words = 11, durationMs = 9_000L, at = 1_700_000_100_000L, status = "SUCCESS")
            // A failed dictation produced no text the tester can point at, so counting it would
            // overstate every figure on the home screen from the first launch after the upgrade.
            insertVersion6Dictation(db, words = 99, durationMs = 99_000L, at = 1_600_000_000_000L, status = "FAILED")
        }

        migrate(name, 7).use { db ->
            db.query(
                "SELECT dictationCount, wordCount, durationMs, firstDictationAt " +
                    "FROM lifetime_stats WHERE id = 0"
            ).use {
                assertTrue(
                    "the upgrade must seed a counters row, not leave the table empty",
                    it.moveToFirst()
                )
                assertEquals(2, it.getInt(0))
                assertEquals(15, it.getInt(1))
                assertEquals(12_000L, it.getLong(2))
                // The earliest *successful* dictation, not the earliest row: the failed one is
                // older, and letting it set the date would date the account before its first word.
                assertEquals(1_700_000_000_000L, it.getLong(3))
            }
        }
    }

    @Test
    fun `the counters start at zero when there is nothing to seed them from`() {
        val name = nameFor("seed-6-empty")
        ExportedSchema.createDatabase(context, name, 6)

        migrate(name, 7).use { db ->
            db.query("SELECT dictationCount, wordCount, durationMs, firstDictationAt FROM lifetime_stats WHERE id = 0")
                .use {
                    assertTrue("the row must exist even with no history to seed it", it.moveToFirst())
                    assertEquals(0, it.getInt(0))
                    assertEquals(0, it.getInt(1))
                    assertEquals(0L, it.getLong(2))
                    assertTrue("a fresh install has no first dictation yet", it.isNull(3))
                }
        }
    }

    @Test
    fun `every version from 1 to the one the database declares has a migration and a schema`() {
        // Read from the production database rather than from the annotation, which Room keeps at
        // binary retention: a version bump that forgets a migration has to fail here.
        val probe = StorageRoot("migrationprobe${System.nanoTime()}").also { names += it.databaseName }
        val probeDb = DatabaseModule.provideDatabase(context, probe)
        val declared = try {
            probeDb.openHelper.readableDatabase.version
        } finally {
            probeDb.close()
        }
        assertEquals("this test is written against the version the database declares", CURRENT_VERSION, declared)

        assertEquals(
            "a version bump without a migration is how a release erases a tester's history",
            (1 until CURRENT_VERSION).map { it to it + 1 },
            DatabaseModule.ALL_MIGRATIONS.map { it.startVersion to it.endVersion }
        )

        for (version in 1..CURRENT_VERSION) {
            assertTrue(
                "schema $version.json is missing, so version $version can no longer be reproduced",
                ExportedSchema.exists(version)
            )
        }
    }

    /** Opens [name] at [to] so the framework calls `onUpgrade`, exactly as a phone does. */
    private fun migrate(name: String, to: Int): SupportSQLiteDatabase =
        helperFor(name, to, upgrade = { db, from, target ->
            for (version in from until target) {
                DatabaseModule.ALL_MIGRATIONS.single { it.startVersion == version }.migrate(db)
            }
        }).writableDatabase

    /** Opens an existing database without migrating it. */
    private fun open(name: String, version: Int): SupportSQLiteDatabase =
        helperFor(name, version, upgrade = { _, from, to ->
            error("$name was expected to be at version $version, not $from on its way to $to")
        }).writableDatabase

    private fun helperFor(
        name: String,
        version: Int,
        upgrade: (SupportSQLiteDatabase, Int, Int) -> Unit
    ): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) =
                        error("the fixture $name must already exist before it is opened")

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                        upgrade(db, oldVersion, newVersion)
                })
                .build()
        )

    private fun insertVersion6Dictation(
        db: SupportSQLiteDatabase,
        words: Int,
        durationMs: Long,
        at: Long,
        status: String
    ) {
        db.insert(
            "dictations",
            CONFLICT_ABORT,
            values(
                "text" to "Diktering $at",
                "rawText" to "diktering $at",
                "wordCount" to words,
                "timestamp" to at,
                "durationMs" to durationMs,
                "historyVisible" to 1,
                "status" to status
            )
        )
    }

    private fun tableExists(db: SupportSQLiteDatabase, table: String): Boolean =
        db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table))
            .use(Cursor::moveToFirst)

    private fun nameFor(suffix: String): String =
        "migration-$suffix-${System.nanoTime()}.db".also { names += it }

    private fun values(vararg pairs: Pair<String, Any?>) = ContentValues().apply {
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
        const val CURRENT_VERSION = 8
    }
}
