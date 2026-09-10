package se.optiqon.voice.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.json.JSONObject
import java.io.File

/**
 * The schema files Room exports under `app/schemas`, used as test data.
 *
 * Room's own `MigrationTestHelper` reads these through the asset manager, which means having them
 * in the asset path of the build under test. For unit tests that would be the debug variant's
 * assets, so every debug install would carry the app's whole schema history. The schemas are on
 * disk in the checkout already, so they are read from there instead.
 *
 * Creating a database from the exported `createSql` — rather than from a `CREATE TABLE` copied by
 * hand into a test — is what makes these fixtures the schema a previous release actually shipped.
 */
internal object ExportedSchema {

    private const val DIR = "schemas/se.optiqon.voice.data.db.OptiqonVoiceDatabase"
    private const val TABLE_NAME = "\${TABLE_NAME}"

    /** The `database` object of `<version>.json`. */
    fun of(version: Int): JSONObject =
        JSONObject(file(version).readText()).getJSONObject("database")

    fun exists(version: Int): Boolean =
        candidates(version).any { it.isFile }

    /**
     * Writes a database at [version] exactly as the release that shipped that schema left it —
     * tables, indices and the identity hash Room checks on open — then hands it to [fill].
     *
     * [stampedVersion] exists for the downgrade guard, which needs the current schema carrying a
     * version number no build can migrate from.
     */
    fun createDatabase(
        context: Context,
        name: String,
        version: Int,
        stampedVersion: Int = version,
        fill: (SupportSQLiteDatabase) -> Unit = {}
    ) {
        val schema = of(version)
        val entities = schema.getJSONArray("entities")

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(stampedVersion) {
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

    /** The working directory is either the module or the root of the checkout. */
    private fun file(version: Int): File =
        candidates(version).firstOrNull { it.isFile }
            ?: error("Exported schema $version.json not found from ${File(".").absolutePath}")

    private fun candidates(version: Int): List<File> =
        listOf(File("$DIR/$version.json"), File("app/$DIR/$version.json"))
}
