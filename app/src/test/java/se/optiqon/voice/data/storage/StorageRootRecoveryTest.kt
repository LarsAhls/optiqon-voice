package se.optiqon.voice.data.storage

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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.optiqon.voice.data.db.OptiqonVoiceDatabase
import se.optiqon.voice.data.db.entity.Dictation
import se.optiqon.voice.di.DatabaseModule
import java.io.File
import java.security.MessageDigest

/**
 * The other half of isolation: getting back in.
 *
 * Separation is only acceptable if it is reversible for the person it separates. An owner who
 * signs out, restarts the phone or installs a newer build must find exactly the data they had —
 * and the device data that nobody has claimed must still be there, unclaimed and unharmed, for
 * whoever turns out to own it.
 *
 * Recovery here is by construction rather than by repair: the binding uid → root is persisted,
 * so the same account resolves to the same files. These tests exercise that claim across the
 * three events that could break it — a sign-out, a new process, and a schema upgrade.
 *
 * Nothing in this class deletes or moves data, and neither does the code it tests.
 */
@RunWith(RobolectricTestRunner::class)
class StorageRootRecoveryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** A new instance reading the same preferences file, which is what the next start is. */
    private fun owner() = DeviceDataOwner(context)

    private fun database(root: StorageRoot): OptiqonVoiceDatabase =
        Room.databaseBuilder(context, OptiqonVoiceDatabase::class.java, root.databaseName)
            .addMigrations(*DatabaseModule.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()

    /** Room databases are not [AutoCloseable], so `use` is not available here. */
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

    private fun dictation(text: String) = Dictation(
        text = text,
        rawText = text,
        wordCount = 1,
        durationMs = 1_000L
    )

    private fun sha256(file: File): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes())

    @Test
    fun `the owner finds its data again after signing out and restarting`() = runTest {
        val first = owner()
        first.claimDefault("uid-a")
        first.setActiveUid("uid-a")
        withDatabase(first.rootFor("uid-a")) { it.dictationDao().insert(dictation("mitt privata")) }

        // Sign out, then a cold start: a new owner instance with nothing in memory.
        first.setActiveUid(null)
        val afterRestart = owner()
        afterRestart.setActiveUid("uid-a")

        val root = afterRestart.rootFor(afterRestart.activeUid())
        assertEquals(StorageRoot.DEFAULT, root)
        val rows = withDatabase(root) { it.dictationDao().getRecent().first() }
        assertEquals(listOf("mitt privata"), rows.map { it.text })
    }

    @Test
    fun `an account that started empty finds its own data again, not the device's`() = runTest {
        val first = owner()
        first.declineDefault("uid-b")
        val ownRoot = first.rootFor("uid-b")
        withDatabase(ownRoot) { it.dictationDao().insert(dictation("bara mitt")) }
        withDatabase(StorageRoot.DEFAULT) { it.dictationDao().insert(dictation("nagon annans")) }

        val afterRestart = owner()

        assertEquals(ownRoot, afterRestart.rootFor("uid-b"))
        val rows = withDatabase(afterRestart.rootFor("uid-b")) { it.dictationDao().getRecent().first() }
        assertEquals("the second account keeps its own history and only that", listOf("bara mitt"), rows.map { it.text })
    }

    @Test
    fun `declining leaves the device data on disk, untouched and still claimable`() = runTest {
        withDatabase(StorageRoot.DEFAULT) { it.dictationDao().insert(dictation("fanns redan")) }
        val defaultFile = context.getDatabasePath(StorageRoot.DEFAULT.databaseName)
        val before = sha256(defaultFile)

        val o = owner()
        o.declineDefault("uid-b")
        withDatabase(o.rootFor("uid-b")) { db ->
            db.dictationDao().insert(dictation("bara mitt"))
            db.dictationDao().getRecent().first()
        }

        assertTrue("start empty must not delete the file it declined", defaultFile.isFile)
        assertArrayEquals("nor change a byte of it", before, sha256(defaultFile))
        assertNull("and it stays unowned", owner().defaultOwner())
        assertTrue("so its real owner can still claim it", owner().canClaimDefault("uid-a"))

        // The claim, when it eventually comes, opens the same rows that were there all along.
        owner().claimDefault("uid-a")
        val rows = withDatabase(owner().rootFor("uid-a")) { it.dictationDao().getRecent().first() }
        assertEquals(listOf("fanns redan"), rows.map { it.text })
    }

    @Test
    fun `a compatible upgrade under a non-default root keeps that root's history`() = runTest {
        val o = owner()
        o.declineDefault("uid-b")
        val root = o.rootFor("uid-b")
        assertFalse("this leg is only meaningful off the default root", root == StorageRoot.DEFAULT)

        // The previous release, written from the exported schema rather than from a hand-copied
        // CREATE TABLE, so it is the file that build actually shipped — under this root's name.
        createVersion7(root.databaseName) { db ->
            db.insert(
                "dictations",
                CONFLICT_ABORT,
                contentValues(
                    "text" to "fore uppgraderingen",
                    "rawText" to "fore uppgraderingen",
                    "timestamp" to 1_700_000_000_000L,
                    "durationMs" to 4_000L,
                    "wordCount" to 2,
                    "historyVisible" to 1,
                    "status" to "SUCCESS"
                )
            )
        }

        // Opening through Room runs the real migration chain and validates the result against
        // the compiled schema: a migration that forgot this root would fail here.
        val db = database(root)
        assertNotNull(db.openHelper.writableDatabase)
        val rows = db.dictationDao().getRecent().first()
        db.close()

        assertEquals("migrations run per root, and lose nothing", listOf("fore uppgraderingen"), rows.map { it.text })
    }

    @Test
    fun `the default root upgrades the same way, so an existing install keeps its history`() = runTest {
        createVersion7(StorageRoot.DEFAULT.databaseName) { db ->
            db.insert(
                "dictations",
                CONFLICT_ABORT,
                contentValues(
                    "text" to "fanns fore konton",
                    "rawText" to "fanns fore konton",
                    "timestamp" to 1_700_000_000_000L,
                    "durationMs" to 4_000L,
                    "wordCount" to 3,
                    "historyVisible" to 1,
                    "status" to "SUCCESS"
                )
            )
        }

        // The account gate arrives and the device is asked whose data this is. Claiming must be
        // a preferences write and nothing else: the same file, the same rows.
        val o = owner()
        assertEquals(StorageRoot.DEFAULT, o.claimDefault("uid-a"))

        val rows = withDatabase(o.rootFor("uid-a")) { it.dictationDao().getRecent().first() }
        assertEquals(listOf("fanns fore konton"), rows.map { it.text })
    }

    /** Writes a version 7 file under [name] exactly as the previous release left it. */
    private fun createVersion7(name: String, fill: (SupportSQLiteDatabase) -> Unit) {
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
