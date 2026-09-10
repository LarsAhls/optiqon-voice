package se.optiqon.voice.domain.access

import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Raised when authorisation has lapsed between being granted and being used.
 *
 * A checked exception rather than a boolean because the places it is thrown from are deep
 * inside a pipeline whose callers all treat a failure the same way. Returning false there would
 * mean every intermediate step had to remember to propagate it, and forgetting once is how a
 * revoked account gets its text injected anyway.
 */
class AccessRevokedException(val reason: BlockReason) :
    IllegalStateException("Access no longer permitted: $reason")

/**
 * Permission to perform one dictation, valid only while the identity that was granted it is
 * still the identity in force and still approved.
 *
 * The lease exists because a check at the top of a suspend function proves nothing about what
 * happens at the bottom of it. Recording, uploading audio, waiting for a language model and
 * injecting text are separated by seconds and by suspension points; an account can be revoked,
 * signed out or replaced inside any of those gaps. So authorisation is carried as a value and
 * re-asked immediately before each thing that would actually be visible outside the app.
 */
class AccessLease internal constructor(
    val epoch: IdentityEpoch,
    private val activeIdentity: ActiveIdentity,
    private val accessRepository: AccessRepository
) {
    /** True while this lease would still be granted. Cheap; no network. */
    suspend fun isValid(): Boolean =
        activeIdentity.isCurrent(epoch) && accessRepository.currentDecision() is AccessDecision.Allowed

    /**
     * Re-validates, throwing if the world moved.
     *
     * Call this immediately before the effect, not at the entry of the function containing it.
     * "Immediately" is meant literally: between this returning and the effect happening there
     * should be nothing that can suspend.
     */
    suspend fun requireValid() {
        if (!activeIdentity.isCurrent(epoch)) throw AccessRevokedException(BlockReason.NOT_REGISTERED)
        val decision = accessRepository.currentDecision()
        if (decision is AccessDecision.Blocked) throw AccessRevokedException(decision.reason)
    }
}

/**
 * The single place that decides whether dictation may proceed.
 *
 * Before this, four entry points each had their own check and one of them — the history retry —
 * had none at all, which left a revoked account one saved failure away from dictating. The
 * duplication was the bug: a fifth entry point would have had to remember, and remembering is
 * not a mechanism.
 */
@Singleton
class AccessGuard @Inject constructor(
    private val accessRepository: AccessRepository,
    private val activeIdentity: ActiveIdentity,
    private val refresher: AccessRefresher
) {

    /**
     * Grants a lease, or explains why not.
     *
     * A stale verdict is refreshed first, but with a short cap. Making every dictation wait on
     * a network round trip to catch a rare revocation would be a constant cost for an
     * occasional benefit; a timeout is therefore treated as a failure to learn anything, which
     * leaves a user inside their grace window free to carry on. Being offline is not a reason
     * to refuse a dictation *on account grounds* — it just means the transcription itself will
     * not reach anywhere.
     */
    suspend fun authorize(trigger: RefreshTrigger = RefreshTrigger.POINT_OF_ACTION): AccessGrant {
        val epoch = activeIdentity.current.value
            ?: return AccessGrant.Denied(BlockReason.NOT_REGISTERED)

        withTimeoutOrNull(REFRESH_BUDGET_MS) { refresher.refresh(trigger) }

        // Re-read the identity: the refresh may have taken long enough for it to change.
        if (!activeIdentity.isCurrent(epoch)) {
            return AccessGrant.Denied(BlockReason.NOT_REGISTERED)
        }
        return when (val decision = accessRepository.currentDecision()) {
            is AccessDecision.Allowed ->
                AccessGrant.Granted(AccessLease(epoch, activeIdentity, accessRepository))
            is AccessDecision.Blocked -> AccessGrant.Denied(decision.reason)
        }
    }

    private companion object {
        /** Long enough for a healthy round trip, short enough not to be felt as a stall. */
        const val REFRESH_BUDGET_MS = 3_000L
    }
}

sealed interface AccessGrant {
    data class Granted(val lease: AccessLease) : AccessGrant
    data class Denied(val reason: BlockReason) : AccessGrant
}
