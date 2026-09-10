package se.optiqon.voice.domain.access

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Decides when to ask the server again, and makes sure two triggers firing at once ask once.
 *
 * Split out of the reader on purpose: `RegistrationRepository` answers "what does the server
 * say", this answers "is it worth asking". Mixing them is how the old in-memory throttle came
 * to survive a process restart badly and to be invisible to the account screen.
 */
@Singleton
class AccessRefresher @Inject constructor(
    private val registrationRepository: Provider<RegistrationReader>,
    private val accessRepository: AccessRepository,
    private val activeIdentity: ActiveIdentity,
    private val clock: Clock,
    private val scope: CoroutineScope
) {
    /** What the refresher needs from the reader; an interface so tests need no Firebase. */
    interface RegistrationReader {
        suspend fun refresh(): RefreshOutcome
    }

    private val _lastOutcome = MutableStateFlow<RefreshOutcome?>(null)

    /**
     * What the most recent attempt learned, so the UI can distinguish "offline" from "the
     * backend is unwell" from "still pending" instead of rendering all three as waiting.
     * [RefreshOutcome.Throttled] is not published: it says nothing new happened.
     */
    val lastOutcome: StateFlow<RefreshOutcome?> = _lastOutcome.asStateFlow()

    private val mutex = Mutex()

    /** The read in flight, together with the identity that asked for it. */
    private var inFlight: Pair<IdentityEpoch, Deferred<RefreshOutcome>>? = null

    /** When a read was last *attempted*, successful or not. Backoff, not freshness. */
    private var lastAttemptElapsedMs: Long = Long.MIN_VALUE
    private var consecutiveFailures: Int = 0

    /**
     * Refreshes if the stored verdict is stale, and returns what was learned.
     *
     * [RefreshTrigger.UID_CHANGE] and [RefreshTrigger.MANUAL] are never throttled: a new
     * identity has no valid prior read of its own, and the button is already rate-limited by
     * the busy state of the screen it sits on.
     */
    suspend fun refresh(trigger: RefreshTrigger): RefreshOutcome {
        val epoch = activeIdentity.current.value ?: return RefreshOutcome.NoAccount
        val forced = trigger == RefreshTrigger.UID_CHANGE || trigger == RefreshTrigger.MANUAL

        if (!forced) {
            if (!isStale(epoch)) return RefreshOutcome.Throttled
            if (isBackingOff()) return RefreshOutcome.Throttled
        }

        val deferred = mutex.withLock {
            val existing = inFlight
            // Single-flight, but only within one identity. Handing account B the answer to a
            // question asked about account A would be exactly the confusion the epoch exists
            // to prevent, and it would be recorded against the wrong uid.
            if (existing != null && existing.first == epoch && existing.second.isActive) {
                existing.second
            } else {
                lastAttemptElapsedMs = clock.elapsedMs()
                val started = scope.async { registrationRepository.get().refresh() }
                inFlight = epoch to started
                started
            }
        }

        val outcome = runCatchingCancellable { deferred.await() }
            .getOrElse { RefreshOutcome.Failed(it) }

        if (outcome !is RefreshOutcome.Throttled) _lastOutcome.value = outcome

        mutex.withLock {
            if (inFlight?.second === deferred) inFlight = null
            consecutiveFailures = when (outcome) {
                is RefreshOutcome.Confirmed -> 0
                is RefreshOutcome.NoAccount, is RefreshOutcome.Throttled -> consecutiveFailures
                else -> (consecutiveFailures + 1).coerceAtMost(BACKOFF_MS.size)
            }
        }
        return outcome
    }

    /**
     * Whether the stored verdict is old enough to be worth re-reading.
     *
     * Derived from the persisted verification timestamps rather than from a counter in memory,
     * so it survives process death — the old in-memory value made the app forget, on every
     * cold start, that it had just checked. Both clocks are read the conservative way
     * [AccessGate] reads them: the larger age wins, and a clock that moved backwards makes the
     * age meaningless and therefore stale.
     */
    private suspend fun isStale(epoch: IdentityEpoch): Boolean {
        val snapshot = accessRepository.currentSnapshot() ?: return true
        if (snapshot.uid != epoch.uid) return true
        val wallAge = clock.wallMs() - snapshot.verifiedAtWallMs
        val elapsedAge = clock.elapsedMs() - snapshot.verifiedAtElapsedMs
        if (wallAge < 0 || elapsedAge < 0) return true
        return maxOf(wallAge, elapsedAge) >= CHECK_IN_INTERVAL_MS
    }

    /**
     * Keeps a dead network from turning four triggers into a retry storm.
     *
     * Connectivity and foreground events arrive in bursts precisely when things are going
     * wrong, which is the worst moment to multiply them.
     */
    private fun isBackingOff(): Boolean {
        if (consecutiveFailures == 0) return false
        val wait = BACKOFF_MS[(consecutiveFailures - 1).coerceAtMost(BACKOFF_MS.lastIndex)]
        val since = clock.elapsedMs() - lastAttemptElapsedMs
        return since in 0 until wait
    }

    private companion object {
        /** Fifteen minutes: often enough to matter, rare enough to be free in practice. */
        const val CHECK_IN_INTERVAL_MS = 15L * 60L * 1000L
        val BACKOFF_MS = longArrayOf(30_000L, 120_000L, 600_000L)
    }
}
