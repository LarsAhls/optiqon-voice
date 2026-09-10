package se.optiqon.voice.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One pending write to the cloud, owned by exactly one account.
 *
 * This is our own queue, not Firestore's. Firestore runs with an in-memory cache precisely
 * so that it holds no durable local data and its pending-write queue cannot outlive a
 * session and be flushed under a different identity. The consequence is that durability is
 * ours to provide, which is what this table does: a row survives sign-out, account switch,
 * revocation and process death, and moves only when [ownerUid] is the account signed in.
 */
@Entity(tableName = "outbox")
data class OutboxEntry(
    @PrimaryKey val id: String,

    /**
     * The Firebase uid that created this row. Never rewritten: a row belonging to a signed-out
     * account waits rather than being re-attributed to whoever signs in next.
     */
    val ownerUid: String,

    /** What the worker should do with the payload, e.g. `case_create` or `attachment_upload`. */
    val kind: String,

    /** JSON body, or a path into the owner's private directory for a file payload. */
    val payload: String,

    val createdAtMs: Long,

    val state: OutboxState,

    /** Attempts made so far; used for backoff, never for silent discard. */
    val attempts: Int = 0,

    /** Why the row is [OutboxState.BLOCKED], shown to the user verbatim. */
    val lastError: String? = null
)

enum class OutboxState {
    /** Waiting for its owner to be signed in and the network to cooperate. */
    PENDING,

    /**
     * The server refused permanently. The row stays, the original data stays, and no further
     * attempt is made until the user decides. A permission error is not a reason to delete
     * someone's unsent work.
     */
    BLOCKED,

    SENT
}
