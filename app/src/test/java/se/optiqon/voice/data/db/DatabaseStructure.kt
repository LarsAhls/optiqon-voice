package se.optiqon.voice.data.db

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * What a database actually looks like, read back from SQLite itself.
 *
 * Columns are keyed by name rather than held in order, because `ALTER TABLE ADD COLUMN` appends
 * while a fresh `CREATE TABLE` uses the order the entity declares — a migrated database and a
 * freshly created one of the same version differ in column order and in nothing else. Room's own
 * validator ignores the order for the same reason.
 *
 * Only indices SQLite reports as explicitly created are compared; the ones it builds for primary
 * keys and unique constraints are consequences of the columns, which are compared already.
 */
internal data class DatabaseStructure(val tables: Map<String, TableStructure>)

internal data class TableStructure(
    val columns: Map<String, ColumnStructure>,
    val indices: Map<String, IndexStructure>
)

internal data class ColumnStructure(
    val type: String,
    val notNull: Boolean,
    val defaultValue: String?,
    val primaryKeyPosition: Int
)

internal data class IndexStructure(val unique: Boolean, val columns: List<String>)

/**
 * Drops the default of every column that carries none in [reference].
 *
 * SQLite demands a default when a NOT NULL column is added to a table that already has rows, so
 * every column a migration appends carries one even though no entity in this app declares any —
 * which means a migrated database and a freshly created one of the same version differ there, and
 * always will. Room's own validator compares a default only where the entity declares one; this
 * does the same. The defaults that decide what the tester ends up seeing are pinned by behaviour
 * instead, by migrating real rows and reading them back.
 */
internal fun DatabaseStructure.ignoringDefaultsNotIn(reference: DatabaseStructure) =
    DatabaseStructure(
        tables.mapValues { (name, table) ->
            val referenceColumns = reference.tables[name]?.columns ?: emptyMap()
            table.copy(
                columns = table.columns.mapValues { (column, structure) ->
                    if (referenceColumns[column]?.defaultValue == null) {
                        structure.copy(defaultValue = null)
                    } else {
                        structure
                    }
                }
            )
        }
    )

/**
 * Reads the structure of every table the app owns. Room's bookkeeping table and the one SQLite
 * writes for its own locale are left out: neither is part of the schema a migration produces.
 */
internal fun SupportSQLiteDatabase.readStructure(): DatabaseStructure =
    DatabaseStructure(
        tableNames().associateWith { table ->
            TableStructure(columnsOf(table), indicesOf(table))
        }
    )

private fun SupportSQLiteDatabase.tableNames(): Set<String> =
    query(
        "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' " +
            "AND name NOT IN ('room_master_table', 'android_metadata') ORDER BY name"
    ).use { cursor ->
        buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }

private fun SupportSQLiteDatabase.columnsOf(table: String): Map<String, ColumnStructure> =
    query("PRAGMA table_info(`$table`)").use { cursor ->
        buildMap {
            while (cursor.moveToNext()) {
                put(
                    cursor.getString(1),
                    ColumnStructure(
                        type = cursor.getString(2).uppercase(),
                        notNull = cursor.getInt(3) == 1,
                        defaultValue = cursor.getString(4)?.trim(),
                        primaryKeyPosition = cursor.getInt(5)
                    )
                )
            }
        }
    }

private fun SupportSQLiteDatabase.indicesOf(table: String): Map<String, IndexStructure> {
    val created = query("PRAGMA index_list(`$table`)").use { cursor ->
        // Older SQLite builds — the one behind Robolectric among them — report only seq, name and
        // unique, with no `origin` column, so the columns are read by name and the indices SQLite
        // created for itself are recognised by their reserved name prefix instead.
        val nameColumn = cursor.getColumnIndexOrThrow("name")
        val uniqueColumn = cursor.getColumnIndexOrThrow("unique")
        val originColumn = cursor.getColumnIndex("origin")
        buildList {
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameColumn)
                val declared = if (originColumn >= 0) {
                    cursor.getString(originColumn) == "c"
                } else {
                    !name.startsWith("sqlite_")
                }
                if (declared) add(name to (cursor.getInt(uniqueColumn) == 1))
            }
        }
    }
    return created.associate { (name, unique) ->
        val columns = query("PRAGMA index_info(`$name`)").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(2)) }
        }
        name to IndexStructure(unique, columns)
    }
}
