package se.optiqon.voice.domain.sync

import se.optiqon.voice.data.db.entity.OutboxEntry
import se.optiqon.voice.data.db.entity.OutboxState

/**
 * What the outbox worker is allowed to do, expressed without Room, WorkManager or Firebase so
 * the interesting cases can be tested as plain functions.
 */
object OutboxPolicy {

    /**
     * The rows that may be sent right now.
     *
     * The `ownerUid` check is the whole point: a pending write must never travel under another
     * account's ID token. When nobody is signed in, or somebody else is, every row is dormant —
     * dormant, not discarded. It resumes the moment its owner returns.
     */
    fun flushable(entries: List<OutboxEntry>, activeUid: String?): List<OutboxEntry> {
        if (activeUid == null) return emptyList()
        return entries.filter { it.ownerUid == activeUid && it.state == OutboxState.PENDING }
    }

    /** Rows held back because their owner is not the account signed in, grouped for the UI. */
    fun waitingForAnotherAccount(entries: List<OutboxEntry>, activeUid: String?): List<OutboxEntry> =
        entries.filter { it.state != OutboxState.SENT && it.ownerUid != activeUid }

    /**
     * How a failed attempt is recorded.
     *
     * A permanent refusal parks the row and stops the retries; it never deletes the row and it
     * never touches the file or draft the row points at. A transient failure — a timeout, a 5xx,
     * an unreachable network — leaves the row pending so the next run picks it up. Conflating
     * the two is exactly how unsent work gets thrown away, so the distinction is made once,
     * here, rather than at each call site.
     */
    fun afterFailure(entry: OutboxEntry, failure: SendFailure): OutboxEntry = when (failure) {
        is SendFailure.Permanent -> entry.copy(
            state = OutboxState.BLOCKED,
            attempts = entry.attempts + 1,
            lastError = failure.message
        )
        is SendFailure.Transient -> entry.copy(
            state = OutboxState.PENDING,
            attempts = entry.attempts + 1,
            lastError = failure.message
        )
    }

    /** True when the worker should ask WorkManager to run it again. */
    fun shouldRetry(failure: SendFailure): Boolean = failure is SendFailure.Transient
}

sealed interface SendFailure {
    val message: String

    /** PERMISSION_DENIED, UNAUTHENTICATED, a rejected payload — retrying cannot help. */
    data class Permanent(override val message: String) : SendFailure

    /** A timeout, a 5xx or no network. Notably, these must not be reported as a refusal. */
    data class Transient(override val message: String) : SendFailure
}
