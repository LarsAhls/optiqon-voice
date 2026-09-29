package se.optiqon.voice.data.feedback

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreException.Code
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import se.optiqon.voice.domain.feedback.CaseReads
import se.optiqon.voice.domain.feedback.RemoteResult
import se.optiqon.voice.domain.sync.SendFailure
import javax.inject.Provider

/**
 * The owner's read markers, `users/{uid}/caseReads/{caseId}` { seenPublicRev, readAt }
 * (FS-S468). `firestore.rules` lets only the approved owner read or write them, only forward,
 * and never past the case's `publicRev`.
 *
 * [markSeen] reads the stored marker in a transaction and writes only when it moves forward, so
 * an older view that lands late costs nothing and never lowers the marker, and a retry of the
 * same view writes nothing. Failures come back neutral: the provider's own message is never
 * passed on, so a refusal says nothing about the case it was refused for.
 */
class FirestoreCaseReads(
    private val firestoreProvider: Provider<FirebaseFirestore>
) : CaseReads {

    private val firestore get() = firestoreProvider.get()

    private fun reads(uid: String) = firestore.collection("users").document(uid).collection("caseReads")

    override suspend fun seen(uid: String): Map<String, Long>? = try {
        val docs = withTimeout(TIMEOUT_MS) { reads(uid).get().await() }
        docs.documents.associate { it.id to (it.getLong("seenPublicRev") ?: 0L) }
    } catch (cancellation: CancellationException) {
        if (cancellation is TimeoutCancellationException) null else throw cancellation
    } catch (_: Exception) {
        null
    }

    override suspend fun markSeen(uid: String, caseId: String, publicRev: Long): RemoteResult = try {
        withTimeout(TIMEOUT_MS) {
            val marker = reads(uid).document(caseId)
            firestore.runTransaction { tx ->
                val stored = tx.get(marker).getLong("seenPublicRev")
                if (!ReadMarkers.movesForward(stored, publicRev)) return@runTransaction RemoteResult.Ok
                tx.set(marker, ReadMarkers.marker(publicRev, FieldValue.serverTimestamp()))
                RemoteResult.Ok
            }.await()
        }
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

/** The marker document and its forward-only rule; `FeedbackPushPlatformTest` holds it to the rules. */
object ReadMarkers {
    fun marker(seenPublicRev: Long, readAt: Any): Map<String, Any> =
        mapOf("seenPublicRev" to seenPublicRev, "readAt" to readAt)

    /** Only a newer view writes. Zero is never worth a document: nothing public has happened. */
    fun movesForward(stored: Long?, publicRev: Long): Boolean = publicRev > (stored ?: 0L)

    /** A provider failure, told without the provider's words. */
    fun neutral(code: Code): RemoteResult =
        if (code == Code.PERMISSION_DENIED) RemoteResult.Denied("Refused.")
        else RemoteResult.Failed(FeedbackSendFailures.classify(code, code.name))
}
