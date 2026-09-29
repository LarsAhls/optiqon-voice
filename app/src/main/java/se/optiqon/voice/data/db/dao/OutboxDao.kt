package se.optiqon.voice.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import se.optiqon.voice.data.db.entity.OutboxEntry
import se.optiqon.voice.data.db.entity.OutboxState

@Dao
interface OutboxDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: OutboxEntry)

    /** All or nothing: Room runs a list insert in one transaction. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(entries: List<OutboxEntry>)

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
     * Records how a send ended, but only if the row is still waiting to be sent. Returns the rows
     * changed: 0 when it was held or taken back while it was inside the sender.
     *
     * One statement, so SQLite orders it against [holdPendingFor] and [discard] as a whole:
     * whichever commits first wins, and a success or failure arriving after a hold cannot
     * overwrite it. Reading the row first and writing afterwards would leave a gap between the
     * two for the hold to land in.
     */
    @Query(
        "UPDATE outbox SET state = :state, attempts = :attempts, lastError = :error " +
            "WHERE id = :id AND state = 'PENDING'"
    )
    suspend fun completeIfPending(id: String, state: OutboxState, attempts: Int, error: String?): Int

    /**
     * Parks the account's waiting case rows when its approval is withdrawn: see
     * [OutboxState.HELD]. Screenshot removals are left alone — they are the owner's word already
     * given, and holding one would keep a picture readable. Rows of the earlier text-only screen
     * are left alone too; they never move without their owner's word in the first place.
     */
    @Query(
        "UPDATE outbox SET state = 'HELD' WHERE ownerUid = :ownerUid AND state = 'PENDING' " +
            "AND kind IN ('case_create', 'case_message', 'attachment_upload')"
    )
    suspend fun holdPendingFor(ownerUid: String): Int

    /**
     * Rewrites a held row's payload -- its approval generation, at its owner's word to send it
     * again. A row that is no longer held is left alone.
     */
    @Query("UPDATE outbox SET payload = :payload WHERE id = :id AND state = 'HELD'")
    suspend fun updateHeldPayload(id: String, payload: String): Int

    /**
     * Rewrites a waiting row's payload -- a withdrawal's reconciled outcome, recorded just
     * before the row is marked sent. A row no longer waiting is left alone.
     */
    @Query("UPDATE outbox SET payload = :payload WHERE id = :id AND state = 'PENDING'")
    suspend fun updatePendingPayload(id: String, payload: String): Int

    /** Every row of one kind, sent ones included, for the few surfaces that show a sent row. */
    @Query("SELECT * FROM outbox WHERE kind = :kind ORDER BY createdAtMs ASC")
    fun observeKind(kind: String): Flow<List<OutboxEntry>>

    /** The owner chose to send what was held. Only ever called at their word. */
    @Query("UPDATE outbox SET state = 'PENDING' WHERE ownerUid = :ownerUid AND state = 'HELD'")
    suspend fun releaseHeldFor(ownerUid: String): Int

    @Query("SELECT * FROM outbox WHERE ownerUid = :ownerUid AND state = 'HELD' ORDER BY createdAtMs ASC")
    suspend fun heldFor(ownerUid: String): List<OutboxEntry>

    /**
     * Deleting a row is a user decision, never a side effect of an error. The worker marks
     * rows [OutboxState.SENT] or [OutboxState.BLOCKED]; only an explicit discard removes one.
     */
    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun discard(id: String)

    /**
     * A discard that leaves something behind to send -- a withdrawal intent, a screenshot
     * removal -- in one transaction: [added] is written first, then the [discarded] rows go.
     * A process that dies part-way leaves both or neither, never a taken-back row with nothing
     * queued to take it back on the server.
     */
    @Transaction
    suspend fun enqueueAndDiscard(added: List<OutboxEntry>, discarded: List<String>) {
        insertAll(added)
        discarded.forEach { discard(it) }
    }
}
