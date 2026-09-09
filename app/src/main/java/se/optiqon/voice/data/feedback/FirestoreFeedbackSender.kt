package se.optiqon.voice.data.feedback

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import se.optiqon.voice.data.db.entity.OutboxEntry
import se.optiqon.voice.domain.sync.OutboxSender
import se.optiqon.voice.domain.sync.SendFailure
import javax.inject.Inject

/**
 * Delivers a feedback row to Firestore.
 *
 * **Built, not switched on.** Nothing binds this class: `SyncModule` still provides the
 * placeholder sender, and nothing in the app calls `OutboxWorker.enqueue`, so no write is
 * ever attempted. Switching the channel on is exactly two steps, in this order:
 *
 *  1. Deploy a rule for the `feedback` collection. The proposal lives in
 *     `firestore.feedback.rules` and is deliberately not the file `firebase.json` names,
 *     so it cannot go live by accident.
 *  2. Bind this class in `SyncModule` in place of the placeholder, and have the feedback
 *     screen enqueue through `OutboxWorker`.
 *
 * Doing 2 before 1 is safe but useless: every write comes back PERMISSION_DENIED, which
 * [FeedbackSendFailures] classifies as permanent, so the rows park with that sentence
 * visible rather than retrying forever.
 */
class FirestoreFeedbackSender @Inject constructor(
    private val firestore: FirebaseFirestore
) : OutboxSender {

    override suspend fun send(entry: OutboxEntry): SendFailure? {
        val document = FeedbackDocument.of(entry)
            ?: return SendFailure.Permanent("This message could not be read and was not sent.")

        return try {
            firestore.collection(FeedbackDocument.COLLECTION)
                .document(entry.id)   // the row id, so a retry overwrites rather than duplicates
                .set(document)
                .await()
            null
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: FirebaseFirestoreException) {
            FeedbackSendFailures.classify(failure.code, failure.message ?: failure.code.name)
        } catch (failure: Exception) {
            // An unrecognised throwable is treated as the road being closed, not as a refusal:
            // keeping the row costs a retry, parking it wrongly costs somebody their message.
            SendFailure.Transient(failure.message ?: "Sending failed.")
        }
    }
}
