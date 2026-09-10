package se.optiqon.voice.data.feedback

import com.google.firebase.firestore.FirebaseFirestoreException.Code
import se.optiqon.voice.domain.sync.SendFailure

/**
 * Turns a Firestore status code into the one distinction the outbox understands.
 *
 * Written as an exhaustive `when` with no `else` on purpose: if Firestore ever adds a code,
 * this stops compiling and somebody decides what it means, rather than the row being quietly
 * parked or quietly retried forever.
 */
object FeedbackSendFailures {

    /** @return null when [code] is [Code.OK], otherwise how the failure should be recorded. */
    fun classifyOrNull(code: Code, message: String): SendFailure? =
        if (code == Code.OK) null else classify(code, message)

    fun classify(code: Code, message: String): SendFailure = when (code) {
        // The server understood and said no. Trying again produces the same no.
        Code.PERMISSION_DENIED,
        Code.UNAUTHENTICATED,
        Code.INVALID_ARGUMENT,
        Code.NOT_FOUND,
        Code.ALREADY_EXISTS,
        Code.FAILED_PRECONDITION,
        Code.OUT_OF_RANGE,
        Code.UNIMPLEMENTED,
        Code.DATA_LOSS -> SendFailure.Permanent(message)

        // Nothing about the message is wrong; the road was closed.
        Code.UNAVAILABLE,
        Code.DEADLINE_EXCEEDED,
        Code.ABORTED,
        Code.INTERNAL,
        Code.RESOURCE_EXHAUSTED,
        Code.CANCELLED,
        Code.UNKNOWN -> SendFailure.Transient(message)

        // Not a failure at all. Reaching here means a caller asked the wrong question, and a
        // transient answer is the safe one: it keeps the row rather than parking it.
        Code.OK -> SendFailure.Transient(message)
    }
}
