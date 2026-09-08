package se.optiqon.voice.data.access

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.tasks.await
import se.optiqon.voice.domain.access.AccessRepository
import se.optiqon.voice.domain.access.AccountStatus
import se.optiqon.voice.domain.access.AuthGateway
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Creates the `users/{uid}` document and reads back what the server decided about it.
 *
 * Registration is a claim, not a grant: the rules only accept `status: 'pending'` from a
 * client, so nothing written here can approve anybody. The value the app then displays comes
 * from reading the document back, never from what it just tried to write.
 */
@Singleton
class RegistrationRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val authGateway: AuthGateway,
    private val accessRepository: AccessRepository
) {

    /**
     * Registers the signed-in account if it has no document yet, then records whatever status
     * the server reports.
     *
     * @return the status now stored for this account, or null when the account is signed out,
     * its email is unverified, or the server could not be reached — none of which may be
     * confused with a decision.
     */
    suspend fun registerAndRefresh(displayName: String?): AccountStatus? {
        val uid = authGateway.currentUid ?: return null
        authGateway.reload()
        if (!authGateway.isEmailVerified) return null
        val email = authGateway.currentEmail ?: return null
        val document = firestore.collection("users").document(uid)

        val existing = runCatching { document.get().await() }.getOrNull() ?: return null
        if (!existing.exists()) {
            val created = runCatching {
                document.set(
                    mapOf(
                        "uid" to uid,
                        "email" to email,
                        "displayName" to (displayName ?: email.substringBefore('@')),
                        "status" to "pending",
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            // A denied create means the rules rejected the claim; it is not an approval and not
            // a crash. The caller is told nothing is known yet and can try again.
            if (created.isFailure) {
                val cause = created.exceptionOrNull()
                if (cause is FirebaseFirestoreException) return null
                throw cause ?: return null
            }
        }

        val fresh = runCatching { document.get().await() }.getOrNull() ?: return null
        val status = parseStatus(fresh.getString("status"))
        accessRepository.record(uid, status)
        return status
    }

    /**
     * Re-reads the verdict if the last attempt was long enough ago, and reports nothing.
     *
     * This is what makes "a revocation takes effect at the next server contact" true rather
     * than aspirational: without it the only server contact is a tester opening the account
     * screen and pressing a button, so a revoked account would keep its cached approval for
     * the whole grace period even with a working network.
     *
     * It deliberately does not block the caller. Making dictation wait for a network round
     * trip would trade a real, constant cost for a rare one; the refreshed verdict applies
     * from the next check instead.
     *
     * @param minIntervalMs how long to leave between attempts, so a burst of dictation does
     * not turn into a burst of billed reads.
     */
    suspend fun refreshIfStale(minIntervalMs: Long = CHECK_IN_INTERVAL_MS) {
        val now = System.currentTimeMillis()
        val last = lastCheckInMs.get()
        // Also refresh when the clock has moved backwards: `now < last` makes any interval
        // arithmetic meaningless, and the safe reading of a meaningless age is "stale".
        if (now in last..(last + minIntervalMs - 1)) return
        if (!lastCheckInMs.compareAndSet(last, now)) return
        refresh()
    }

    /** Reads the current verdict without writing anything. */
    suspend fun refresh(): AccountStatus? {
        val uid = authGateway.currentUid ?: return null
        val snapshot = runCatching {
            firestore.collection("users").document(uid).get().await()
        }.getOrNull() ?: return null
        val status = if (snapshot.exists()) parseStatus(snapshot.getString("status")) else AccountStatus.NEW
        accessRepository.record(uid, status)
        return status
    }

    /** When the last check-in was attempted, whether or not it reached the server. */
    private val lastCheckInMs = AtomicLong(Long.MIN_VALUE)

    /** An unknown or missing value is [AccountStatus.NEW]; it is never read as approval. */
    private fun parseStatus(raw: String?): AccountStatus = when (raw) {
        "pending" -> AccountStatus.PENDING
        "approved" -> AccountStatus.APPROVED
        "rejected" -> AccountStatus.REJECTED
        "revoked" -> AccountStatus.REVOKED
        else -> AccountStatus.NEW
    }

    private companion object {
        /** Fifteen minutes: often enough to matter, rare enough to be free in practice. */
        const val CHECK_IN_INTERVAL_MS = 15L * 60L * 1000L
    }
}
