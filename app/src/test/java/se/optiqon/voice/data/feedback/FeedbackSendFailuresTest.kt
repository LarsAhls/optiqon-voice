package se.optiqon.voice.data.feedback

import com.google.firebase.firestore.FirebaseFirestoreException.Code
import org.junit.Assert.assertEquals
import org.junit.Test
import se.optiqon.voice.domain.sync.SendFailure

/**
 * The only question the outbox asks a sender: is this failure permanent?
 *
 * Getting it wrong is not symmetric. Calling a transient failure permanent parks somebody's
 * unsent message behind a wrong explanation, and only a human can unpark it. Calling a
 * permanent failure transient spins the worker forever against a wall. So the mapping is a
 * plain function over the codes Firestore actually returns, decided here and testable
 * without a network, an emulator or a project.
 */
class FeedbackSendFailuresTest {

    @Test
    fun `a refusal the person cannot retry away is permanent`() {
        listOf(
            Code.PERMISSION_DENIED,
            Code.UNAUTHENTICATED,
            Code.INVALID_ARGUMENT,
            Code.NOT_FOUND,
            Code.ALREADY_EXISTS,
            Code.FAILED_PRECONDITION,
            Code.OUT_OF_RANGE,
            Code.UNIMPLEMENTED,
            Code.DATA_LOSS
        ).forEach { code ->
            val failure = FeedbackSendFailures.classify(code, "boom")
            assertEquals(
                "$code cannot be fixed by trying again",
                SendFailure.Permanent("boom"),
                failure
            )
        }
    }

    @Test
    fun `a wall the network put up is transient`() {
        listOf(
            Code.UNAVAILABLE,
            Code.DEADLINE_EXCEEDED,
            Code.ABORTED,
            Code.INTERNAL,
            Code.RESOURCE_EXHAUSTED,
            Code.CANCELLED,
            Code.UNKNOWN
        ).forEach { code ->
            val failure = FeedbackSendFailures.classify(code, "boom")
            assertEquals(
                "$code must not be reported to the person as a refusal",
                SendFailure.Transient("boom"),
                failure
            )
        }
    }

    @Test
    fun `every code Firestore can return is classified, none fall through`() {
        Code.values()
            .filter { it != Code.OK }
            .forEach { code ->
                // The assertion is that this does not throw and does not silently guess.
                FeedbackSendFailures.classify(code, "boom")
            }
    }

    @Test
    fun `success is not a failure`() {
        assertEquals(null, FeedbackSendFailures.classifyOrNull(Code.OK, "fine"))
    }
}
