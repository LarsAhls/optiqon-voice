package se.optiqon.voice.domain.access

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * The single authority on who is acting right now.
 *
 * Everything that can outlive the account that started it — a refresh in flight, a
 * transcription, a queued injection — carries the [IdentityEpoch] it began under and is
 * expected to check it again before it has any effect. That is the whole point: the uid on its
 * own cannot tell a caller whether the world moved while it was suspended.
 *
 * The epoch changes on every sign-in, every sign-out and every account switch. Signing out and
 * back in as the same person is a new epoch even though the uid is unchanged, because every
 * request issued before the sign-out is stale regardless of who signs in next.
 */
@Singleton
class ActiveIdentity @Inject constructor() {

    private val _current = MutableStateFlow<IdentityEpoch?>(null)

    /** The epoch in force now, or null when nobody is signed in. */
    val current: StateFlow<IdentityEpoch?> = _current.asStateFlow()

    private val sequence = AtomicLong(0L)

    /**
     * Records that [uid] is now the signed-in account, minting a new epoch if the identity
     * actually changed.
     *
     * Idempotent for a repeated report of the same uid: Firebase's auth-state listener can
     * re-emit, and minting an epoch for a non-event would invalidate work that is still
     * perfectly valid.
     *
     * @return true if a new epoch was minted, i.e. the identity really moved.
     */
    fun update(uid: String?): Boolean {
        val existing = _current.value
        if (existing?.uid == uid) return false
        _current.value = uid?.let { IdentityEpoch(it, Random.nextLong()) }
        return true
    }

    /** The next position in the global order of server reads. Monotone for the process. */
    fun nextSeq(): Long = sequence.incrementAndGet()

    /** True while [epoch] is still the epoch in force. */
    fun isCurrent(epoch: IdentityEpoch?): Boolean = epoch != null && _current.value == epoch
}
