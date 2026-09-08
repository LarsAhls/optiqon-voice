package se.optiqon.voice.domain.access

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
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
 */
@Singleton
class AccessRepository @Inject constructor(
    private val authGateway: AuthGateway,
    private val accessStateStore: AccessStateStore,
    private val clock: Clock,
    private val config: AccessConfig,
    scope: CoroutineScope
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val snapshots: Flow<AccessSnapshot?> = authGateway.uidChanges()
        .flatMapLatest { uid -> if (uid == null) flowOf(null) else accessStateStore.snapshot(uid) }

    val decision: StateFlow<AccessDecision> = snapshots
        .map { evaluate(it) }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = AccessDecision.Blocked(BlockReason.NOT_REGISTERED)
        )

    /**
     * The decision as of this instant.
     *
     * [decision] recomputes when the uid or the stored verdict changes, but grace also expires
     * with the passage of time alone, and nothing emits for that. A caller about to allow
     * something must therefore ask rather than read the cached value.
     */
    suspend fun currentDecision(): AccessDecision = evaluate(snapshots.first())

    /** Records a verdict that just came back from the server, stamping both clocks. */
    suspend fun record(uid: String, status: AccountStatus) {
        accessStateStore.record(
            AccessSnapshot(
                uid = uid,
                status = status,
                verifiedAtWallMs = clock.wallMs(),
                verifiedAtElapsedMs = clock.elapsedMs()
            )
        )
    }

    private fun evaluate(snapshot: AccessSnapshot?): AccessDecision = AccessGate.evaluate(
        snapshot = snapshot,
        activeUid = authGateway.currentUid,
        nowWallMs = clock.wallMs(),
        nowElapsedMs = clock.elapsedMs(),
        graceMs = config.graceMs
    )
}
