package se.optiqon.voice.domain.access

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import se.optiqon.voice.data.storage.DeviceDataOwner
import se.optiqon.voice.data.storage.ProcessRestarter
import se.optiqon.voice.data.storage.StorageOwnership
import se.optiqon.voice.domain.transcription.NetworkMonitor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps [ActiveIdentity] in step with Firebase, drives the refresh triggers, and publishes the
 * one session state the UI renders.
 *
 * It exists so that nothing else has to remember to do those three things. Before this, the
 * only refresh in the app was launched from inside the bubble's gate and never awaited, which
 * meant a revocation was noticed at best by accident and at worst not at all.
 */
@Singleton
class AccessSession @Inject constructor(
    private val authGateway: AuthGateway,
    private val accessRepository: AccessRepository,
    private val refresher: AccessRefresher,
    private val activeIdentity: ActiveIdentity,
    private val networkMonitor: NetworkMonitor,
    private val deviceDataOwner: DeviceDataOwner,
    private val processRestarter: ProcessRestarter,
    private val storageOwnership: StorageOwnership,
    private val scope: CoroutineScope
) {

    private val _identityScope = MutableStateFlow(newIdentityScope())

    /**
     * A scope that lives exactly as long as the identity that owns it.
     *
     * Work started under it is cancelled the moment the identity moves, which is what makes
     * "stop the remaining work" a mechanism rather than a promise: a cancelled coroutine cannot
     * reach its next network call, and a scope that no longer exists cannot be launched into.
     */
    val identityScope: StateFlow<CoroutineScope> = _identityScope.asStateFlow()

    /** What to show: one state, derived once, instead of four screens each guessing. */
    val state: StateFlow<AccessSessionState> = combine(
        accessRepository.decision,
        refresher.lastOutcome,
        activeIdentity.current,
        accessRepository.snapshotUpdates
    ) { decision, outcome, identity, snapshot ->
        AccessSessionStates.evaluate(
            decision = decision,
            signedIn = identity != null,
            hasSnapshot = snapshot != null && snapshot.uid == identity?.uid,
            lastOutcome = outcome
        )
    }.stateIn(scope, SharingStarted.Eagerly, AccessSessionState.Verifying)

    /** Called once from the application object. Idempotent enough not to care if it is not. */
    fun start() {
        scope.launch {
            authGateway.uidChanges().collect { uid -> onIdentityChanged(uid) }
        }
        scope.launch {
            // Edge-only by construction: NetworkMonitor is a StateFlow, which drops repeats.
            // `drop(1)` skips the value that is already true at start-up, so a cold start does
            // not fire a connectivity refresh on top of the foreground one.
            networkMonitor.isOnline.drop(1).collect { online ->
                if (online) refresher.refresh(RefreshTrigger.CONNECTIVITY)
            }
        }
    }

    /**
     * Whether the signed-in account still has to say what should happen to the data already on
     * this device.
     */
    fun needsDataClaimDecision(): Boolean {
        val uid = activeIdentity.current.value?.uid ?: return false
        return deviceDataOwner.canClaimDefault(uid)
    }

    /**
     * Records the answer, and moves the process to the resulting root if it is a different one.
     *
     * @param claim true to open the data already on this device under this account, false to
     * start empty. Neither answer copies, moves or deletes anything: declining leaves the
     * existing files exactly where they are, unclaimed, and still claimable later.
     */
    fun answerDataClaim(claim: Boolean) {
        val uid = activeIdentity.current.value?.uid ?: return
        if (claim) deviceDataOwner.claimDefault(uid) else deviceDataOwner.declineDefault(uid)

        val resolved = deviceDataOwner.setActiveUid(uid)
        if (resolved != storageOwnership.root) {
            storageOwnership.seal()
            processRestarter.restart()
        }
    }

    /** Wired to `ProcessLifecycleOwner`; also the natural moment to notice a revocation. */
    fun onForeground() {
        scope.launch { refresher.refresh(RefreshTrigger.FOREGROUND) }
    }

    /** The account screen's "Försök igen". Unthrottled; the button has its own busy state. */
    suspend fun refreshNow(): RefreshOutcome = refresher.refresh(RefreshTrigger.MANUAL)

    private suspend fun onIdentityChanged(uid: String?) {
        val moved = activeIdentity.update(uid)
        if (!moved) return

        // Cancel first, ask second. Anything still running belongs to whoever just left, and it
        // must not be given the chance to finish under the new identity's storage or key.
        _identityScope.value.cancel("identity changed")
        _identityScope.value = newIdentityScope()

        if (uid != null && storageOwnership.root.isDefault && deviceDataOwner.canClaimDefault(uid)) {
            // This account has not yet been asked whether the data already on the device is
            // theirs. Binding it either way now would be the guess this whole mechanism exists
            // to avoid, so the process stays where it is and the question is put to the user.
            // Nothing is exposed by waiting: the account gate holds an unanswered account out of
            // the app, and dictation is refused until the gate opens.
            refresher.refresh(RefreshTrigger.UID_CHANGE)
            return
        }

        // Persisted before anything else, so that whatever happens next -- including the process
        // ending on the following line -- the next start opens the right files.
        val resolved = deviceDataOwner.setActiveUid(uid)
        if (resolved != storageOwnership.root) {
            // The database, both preference stores and any queued work in this process are open
            // on the previous account's files. Ending the process is the only way to be sure
            // none of them is still holding one; the next start resolves the new root from disk.
            //
            // Sealed first, and not merely as tidiness: if the restart does not happen — a
            // device that refuses to relaunch us, a test, a process that lingers — start-up work
            // that has not run yet must find the root retired rather than write into files that
            // have just stopped being this identity's.
            storageOwnership.seal()
            processRestarter.restart()
            return
        }

        if (uid != null) refresher.refresh(RefreshTrigger.UID_CHANGE)
    }

    /**
     * A child of the application scope, so the process shutting down takes it with it, but with
     * its own [SupervisorJob] so cancelling it does not cancel its parent — the session
     * machinery has to keep running in order to notice the *next* identity.
     */
    private fun newIdentityScope(): CoroutineScope =
        CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
}
