package se.optiqon.voice.data.access

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import se.optiqon.voice.domain.access.AccessRefresher
import se.optiqon.voice.domain.access.AccessRepository
import se.optiqon.voice.domain.access.AccountRegistrar
import se.optiqon.voice.domain.access.AccountStatus
import se.optiqon.voice.domain.access.ActiveIdentity
import se.optiqon.voice.domain.access.AuthGateway
import se.optiqon.voice.domain.access.RefreshOutcome
import se.optiqon.voice.domain.transcription.NetworkMonitor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Creates the `users/{uid}` document and reads back what the server decided about it.
 *
 * Registration is a claim, not a grant: the rules only accept `status: 'pending'` from a
 * client, so nothing written here can approve anybody. The value the app then displays comes
 * from reading the document back, never from what it just tried to write.
 *
 * Throttling, single-flight and the refresh triggers live in `AccessRefresher`. This class
 * performs one read and classifies it honestly; it has no opinion about when to be called.
 */
@Singleton
class RegistrationRepository @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val authGateway: AuthGateway,
    private val activeIdentity: ActiveIdentity,
    private val accessRepository: AccessRepository,
    private val networkMonitor: NetworkMonitor
) : AccessRefresher.RegistrationReader, AccountRegistrar {

    /**
     * Registers the signed-in account if it has no document yet, then records whatever status
     * the server reports.
     */
    override suspend fun registerAndRefresh(displayName: String?): RefreshOutcome {
        val epoch = activeIdentity.current.value ?: return RefreshOutcome.NoAccount
        if (authGateway.currentUid != epoch.uid) return RefreshOutcome.NoAccount
        authGateway.reload()
        if (!authGateway.isEmailVerified) return RefreshOutcome.NoAccount
        val email = authGateway.currentEmail ?: return RefreshOutcome.NoAccount
        val document = firestore.collection("users").document(epoch.uid)

        val existing = readFromServer(document)
        val existingDoc = when (existing) {
            is ServerRead.Answered -> existing.snapshot
            is ServerRead.Unreachable -> return existing.outcome
        }

        if (!existingDoc.exists()) {
            val created = runCatching {
                document.set(
                    mapOf(
                        "uid" to epoch.uid,
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
                if (cause is CancellationException) throw cause
                return classifyFailure(cause)
            }
        }

        return refresh()
    }

    /**
     * Reads the current verdict without writing anything to Firestore.
     *
     * The read is pinned to the server for a reason that is easy to miss. A plain `get()` is
     * served out of Firestore's offline cache when the device cannot reach the network: no
     * error is raised, the previous status comes back, and — before this — it was recorded with
     * a *fresh* pair of timestamps. A device that never talked to a server again could renew
     * its own grace indefinitely, which makes "the server said so" unprovable for every stored
     * verdict. [Source.SERVER] plus an explicit `isFromCache` rejection is what closes that.
     */
    override suspend fun refresh(): RefreshOutcome {
        val epoch = activeIdentity.current.value ?: return RefreshOutcome.NoAccount
        if (authGateway.currentUid != epoch.uid) return RefreshOutcome.NoAccount

        // Taken before the read, so two reads of the same account are ordered by when they
        // were asked rather than by when they happened to come back.
        val seq = activeIdentity.nextSeq()

        val read = readFromServer(firestore.collection("users").document(epoch.uid))
        val snapshot = when (read) {
            is ServerRead.Answered -> read.snapshot
            is ServerRead.Unreachable -> {
                accessRepository.invalidate()
                return read.outcome
            }
        }

        // A missing document is an answer: the server looked and there is no such user.
        val status = if (snapshot.exists()) parseStatus(snapshot.getString("status")) else AccountStatus.NEW
        accessRepository.record(epoch, seq, status)
        return RefreshOutcome.Confirmed(status)
    }

    private sealed interface ServerRead {
        data class Answered(val snapshot: DocumentSnapshot) : ServerRead
        data class Unreachable(val outcome: RefreshOutcome) : ServerRead
    }

    private suspend fun readFromServer(
        document: com.google.firebase.firestore.DocumentReference
    ): ServerRead {
        val result = runCatching { document.get(Source.SERVER).await() }
        val snapshot = result.getOrElse { cause ->
            if (cause is CancellationException) throw cause
            return ServerRead.Unreachable(classifyFailure(cause))
        }
        // Belt and braces. Source.SERVER is documented to fail rather than fall back, but a
        // cached document reaching this point would be indistinguishable from a server answer
        // and would be stamped as one, so it is refused explicitly rather than trusted.
        if (snapshot.metadata.isFromCache) {
            return ServerRead.Unreachable(RefreshOutcome.NoNetwork)
        }
        return ServerRead.Answered(snapshot)
    }

    private fun classifyFailure(cause: Throwable?): RefreshOutcome =
        classifyRegistrationFailure(cause, networkMonitor.isOnline.value)
}

/**
 * Turns a failure into an honest statement about what is and is not known.
 *
 * Two readings are deliberately refused. An unreachable server is [RefreshOutcome.NoNetwork]
 * and never a verdict, so grace continues to run rather than being cut short by a flaky
 * connection. And `PERMISSION_DENIED` is *not* read as revocation: rules deny for reasons
 * that have nothing to do with an account's status, and the counterpart of "a valid token is
 * not approval" is that a denied read is not proof of the opposite.
 *
 * A free function of `(cause, isOnline)` so that the classification — the part that decides
 * whether grace keeps running — can be tested without a Firestore instance.
 */
internal fun classifyRegistrationFailure(cause: Throwable?, isOnline: Boolean): RefreshOutcome {
    val code = (cause as? FirebaseFirestoreException)?.code
    if (code == FirebaseFirestoreException.Code.UNAVAILABLE) return RefreshOutcome.NoNetwork
    if (!isOnline) return RefreshOutcome.NoNetwork
    return RefreshOutcome.Failed(cause)
}

/** An unknown or missing value is [AccountStatus.NEW]; it is never read as approval. */
internal fun parseStatus(raw: String?): AccountStatus = when (raw) {
    "pending" -> AccountStatus.PENDING
    "approved" -> AccountStatus.APPROVED
    "rejected" -> AccountStatus.REJECTED
    "revoked" -> AccountStatus.REVOKED
    else -> AccountStatus.NEW
}
