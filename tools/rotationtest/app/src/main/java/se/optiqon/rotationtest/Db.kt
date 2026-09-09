package se.optiqon.rotationtest

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

/** One row that must survive every legitimate signer rotation. */
@Entity(tableName = "marker")
data class Marker(
    @PrimaryKey val id: Int = 1,
    val uuid: String,
    val createdAt: Long,
)

@Dao
interface MarkerDao {
    @Query("SELECT * FROM marker WHERE id = 1") fun get(): Marker?
    @Insert fun insert(marker: Marker)
}

@Database(entities = [Marker::class], version = 1, exportSchema = false)
abstract class RotationDb : RoomDatabase() {
    abstract fun markers(): MarkerDao
}
