package se.optiqon.voice.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import se.optiqon.voice.data.db.entity.OutboxEntry
import se.optiqon.voice.data.db.entity.OutboxState

@Dao
interface OutboxDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: OutboxEntry)

    /**
     * The only query the worker is allowed to use. Filtering by owner in SQL rather than in
     * Kotlin means a caller cannot forget to filter and pick up someone else's rows.
     */
    @Query("SELECT * FROM outbox WHERE ownerUid = :ownerUid AND state = 'PENDING' ORDER BY createdAtMs ASC")
    suspend fun pendingFor(ownerUid: String): List<OutboxEntry>

    /** Used for the "N saved entries are waiting for X" message; counts every owner. */
    @Query("SELECT * FROM outbox WHERE state != 'SENT' ORDER BY createdAtMs ASC")
    fun observeUnsent(): Flow<List<OutboxEntry>>

    @Query("SELECT * FROM outbox ORDER BY createdAtMs ASC")
    suspend fun all(): List<OutboxEntry>

    @Query("SELECT * FROM outbox WHERE id = :id")
    suspend fun byId(id: String): OutboxEntry?

    @Query("UPDATE outbox SET state = :state, attempts = :attempts, lastError = :error WHERE id = :id")
    suspend fun updateState(id: String, state: OutboxState, attempts: Int, error: String?)

    /**
     * Deleting a row is a user decision, never a side effect of an error. The worker marks
     * rows [OutboxState.SENT] or [OutboxState.BLOCKED]; only an explicit discard removes one.
     */
    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun discard(id: String)
}
