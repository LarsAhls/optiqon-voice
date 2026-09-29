package se.optiqon.voice.data.feedback

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import se.optiqon.voice.domain.feedback.NotificationTokens
import se.optiqon.voice.domain.feedback.RemoteResult
import se.optiqon.voice.domain.sync.SendFailure
import javax.inject.Provider

/**
 * This installation's push registration, `users/{uid}/notificationTokens/{installationId}`
 * { token, platform, updatedAt } (FS-S468). One document per account and installation: a
 * refreshed token overwrites the same document, and removing it is a delete of that one id.
 *
 * The rules let an approved owner write it and a verified owner delete it, so removal still
 * works for an account whose approval was withdrawn. Failures come back neutral.
 */
class FirestoreNotificationTokens(
    private val firestoreProvider: Provider<FirebaseFirestore>
) : NotificationTokens {

    private fun doc(uid: String, installationId: String) = firestoreProvider.get()
        .collection("users").document(uid).collection("notificationTokens").document(installationId)

    override suspend fun register(uid: String, installationId: String, token: String): RemoteResult =
        guarded { doc(uid, installationId).set(TokenDocuments.registration(token, FieldValue.serverTimestamp())).await() }

    override suspend fun unregister(uid: String, installationId: String): RemoteResult =
        guarded { doc(uid, installationId).delete().await() }

    private suspend fun guarded(block: suspend () -> Unit): RemoteResult = try {
        withTimeout(TIMEOUT_MS) { block() }
        RemoteResult.Ok
    } catch (cancellation: CancellationException) {
        if (cancellation is TimeoutCancellationException) RemoteResult.Failed(SendFailure.Transient(NEUTRAL))
        else throw cancellation
    } catch (failure: FirebaseFirestoreException) {
        ReadMarkers.neutral(failure.code)
    } catch (_: Exception) {
        RemoteResult.Failed(SendFailure.Transient(NEUTRAL))
    }

    private companion object {
        const val TIMEOUT_MS = 15_000L
        const val NEUTRAL = "Not recorded."
    }
}

/** The registration document; `CaseReadsDocumentTest` holds its keys to the rules. */
object TokenDocuments {
    const val PLATFORM = "android"
    const val MAX_INSTALLATION_ID = 128

    fun registration(token: String, updatedAt: Any): Map<String, Any> =
        mapOf("token" to token, "platform" to PLATFORM, "updatedAt" to updatedAt)
}
