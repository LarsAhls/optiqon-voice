package se.optiqon.voice.domain.access

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import se.optiqon.voice.data.access.AccessStateStore
import javax.inject.Inject
import javax.inject.Singleton

/** Injected so tests can move time without waiting for it. */
interface Clock {
    fun wallMs(): Long
    fun elapsedMs(): Long
}

/**
 * How long an approved verdict survives without server contact.
 *
 * 72 hours is the value Mission 1 tests against and the value proposed to Lars; it is not an
 * approved live policy, which is why it sits in configuration rather than in an `if` somewhere.
 */
data class AccessConfig(val graceMs: Long = AccessGate.PROPOSED_GRACE_MS)

/**
 * Joins "who is signed in" with "what the server last said about them" and publishes the
 * verdict the rest of the app reads.
 *
 * The published value is deliberately conservative while things are unknown: before the first
 * snapshot arrives the decision is [AccessDecision.Blocked], never Allowed. Nothing here is a
 * security boundary — the boundary is `firestore.rules` — but this is what stops a pending or
 * revoked tester from dictating, which is a product requirement in its own right.
 *
 * Two invariants hold the design up, and both are enforced in [record]:
 *
 *  1. **The only writer of a snapshot is [record]**, and it is called only after a successful,
 *     current, *server* read. Everything downstream may therefore read a stored
 *     [AccountStatus] as "the server said so" rather than "the cache said so".
 *  2. **A slow answer never overwrites a newer one.** An answer carries the epoch it was asked
 *     under and its position in the read order; one that is stale in either respect is dropped.
 *     Both tests are made *at the moment of writing*, inside the store's own transaction —
 *     checking first and writing afterwards would let two answers pass the same check and then
 *     commit in the wrong order.
 */
@Singleton
class AccessRepository @Inject constructor(
    private val authGateway: AuthGateway,
    private val accessStateStore: AccessStateStore,
    private val activeIdentity: ActiveIdentity,
    private val clock: Clock,
    private val config: AccessConfig,
    scope: CoroutineScope
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val snapshots: Flow<AccessSnapshot?> = authGateway.uidChanges()
        .flatMapLatest { uid -> if (uid == null) flowOf(null) else accessStateStore.snapshot(uid) }

    /**
     * Bumped whenever something outside the stored snapshot could change the verdict — chiefly
     * a refresh landing — so the deadline below is recomputed instead of waiting out a window
     * that is no longer the right one.
     */
    private val invalidations = MutableStateFlow(0L)

    /**
     * The verdict, re-emitted when grace expires as well as when the stored verdict changes.
     *
     * Grace runs out with the passage of time alone, and a flow driven only by stored state
     * emits nothing for that: the app would keep rendering "approved" until something else
     * happened to poke it. The instant of expiry is computable, though, so rather than ticking
     * — 4,320 wakeups per 72-hour grace, to learn one fact — this waits exactly as long as
     * remains and then emits again. In steady state that is zero wakeups.
     *
     * `delay` does not run while the device is in Doze, so a deadline falling during deep sleep
     * fires late. That is tolerable *here and nowhere else*: the consumers of this flow are the
     * UI, which nobody is looking at while the phone sleeps, and long-lived service state,
     * which is idle then. Anything about to have an actual effect re-reads
     * [currentDecision] against the live clocks instead.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val decision: StateFlow<AccessDecision> =
        combine(snapshots, invalidations) { snapshot, _ -> snapshot }
            .flatMapLatest { snapshot ->
                flow {
                    emit(evaluate(snapshot))
                    val remaining = remainingGraceMs(snapshot)
                    if (remaining != null && remaining > 0) {
                        delay(remaining)
                        emit(evaluate(snapshot))
                    }
                }
            }
            .stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = AccessDecision.Blocked(BlockReason.NOT_REGISTERED)
            )

    /**
     * The stored verdict as it changes, for callers that must tell "no answer yet" apart from
     * "an answer that says no". Cold: each collector reads the store itself.
     */
    val snapshotUpdates: Flow<AccessSnapshot?> = snapshots

    /**
     * The decision as of this instant, read from the live clocks.
     *
     * This is the authority. [decision] is merely kept from being wrong.
     */
    suspend fun currentDecision(): AccessDecision = evaluate(snapshots.first())

    /** The snapshot in force for the signed-in account, if any. */
    suspend fun currentSnapshot(): AccessSnapshot? = snapshots.first()

    /**
     * Records a verdict that just came back from the server, stamping both clocks.
     *
     * Internal, and there is a test asserting exactly one file calls it. The invariant that a
     * stored snapshot is always a server verdict is what every downstream reading of
     * [AccountStatus] rests on, so a second writer appearing should break a build, not a
     * assumption.
     *
     * @param epoch the identity this answer was asked under
     * @param seq where this read sat in the order of reads
     * @return true if the verdict was stored; false if it had been overtaken.
     */
    internal suspend fun record(
        epoch: IdentityEpoch,
        seq: Long,
        status: AccountStatus
    ): Boolean {
        // A cheap early exit for an answer that is already obsolete before it reaches the
        // store. It is *not* the check that matters: the identity can move again between here
        // and the commit, so the same question is asked once more inside the transaction.
        if (!activeIdentity.isCurrent(epoch)) return false

        val stored = accessStateStore.recordIfAccepted(
            AccessSnapshot(
                uid = epoch.uid,
                status = status,
                verifiedAtWallMs = clock.wallMs(),
                verifiedAtElapsedMs = clock.elapsedMs(),
                epochToken = epoch.token,
                seq = seq
            )
        ) { current ->
            // Re-runnable by contract: this reads its argument and the live identity, nothing
            // carried over from an earlier run of the transform.
            when {
                // The identity moved while this answer was in flight: a sign-out, a switch, or
                // a sign-in as the same person. Whoever is here now did not ask this question.
                !activeIdentity.isCurrent(epoch) -> false
                // Within one epoch the read order decides. An answer that started earlier and
                // finished later must not undo the newer one — which is what makes a revocation
                // stick instead of being resurrected by an approval that was already in the air
                // when it arrived.
                current != null && current.epochToken == epoch.token && current.seq >= seq -> false
                else -> true
            }
        }

        if (stored) invalidations.value = invalidations.value + 1
        return stored
    }

    /** Recomputes the deadline without a stored change; used when a refresh reports no verdict. */
    internal fun invalidate() {
        invalidations.value = invalidations.value + 1
    }

    /**
     * How long this snapshot may still be relied on, or null when grace does not apply.
     *
     * Mirrors [AccessGate]'s two-clock rule exactly: the *older* of the two ages decides how
     * much is left, because the gate takes the larger age when deciding whether the window has
     * closed. Anything else would schedule a wakeup after the gate had already started
     * refusing.
     */
    private fun remainingGraceMs(snapshot: AccessSnapshot?): Long? {
        if (snapshot == null || snapshot.status != AccountStatus.APPROVED) return null
        if (snapshot.uid != authGateway.currentUid) return null
        val wallAge = clock.wallMs() - snapshot.verifiedAtWallMs
        val elapsedAge = clock.elapsedMs() - snapshot.verifiedAtElapsedMs
        if (wallAge < 0 || elapsedAge < 0) return null
        val age = maxOf(wallAge, elapsedAge)
        return (config.graceMs - age).takeIf { it > 0 }
    }

    private fun evaluate(snapshot: AccessSnapshot?): AccessDecision = AccessGate.evaluate(
        snapshot = snapshot,
        activeUid = authGateway.currentUid,
        nowWallMs = clock.wallMs(),
        nowElapsedMs = clock.elapsedMs(),
        graceMs = config.graceMs
    )
}
