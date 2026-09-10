package se.optiqon.voice.data.access

import com.google.firebase.firestore.FirebaseFirestoreException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.optiqon.voice.domain.access.AccountStatus
import se.optiqon.voice.domain.access.RefreshOutcome
import java.io.IOException

/**
 * A refresh that did not learn anything must not be able to pass for one that did.
 *
 * The distinction is load-bearing in both directions. Reading a failure as a verdict would let
 * a flaky connection lock an approved user out; reading it as approval would let an unreachable
 * server keep a revoked one in. The two readings this pins down are the ones that were
 * previously collapsed: an unreachable server is not a verdict, and a denied read is not a
 * revocation.
 */
class RefreshOutcomeTest {

    private fun firestore(code: FirebaseFirestoreException.Code) =
        FirebaseFirestoreException("simulated", code)

    @Test
    fun `an unavailable server is not a verdict`() {
        assertEquals(
            RefreshOutcome.NoNetwork,
            classifyRegistrationFailure(firestore(FirebaseFirestoreException.Code.UNAVAILABLE), true)
        )
    }

    @Test
    fun `any failure while offline is not a verdict`() {
        assertEquals(
            RefreshOutcome.NoNetwork,
            classifyRegistrationFailure(IOException("socket"), false)
        )
    }

    @Test
    fun `permission denied is a failure, never a revocation`() {
        val outcome = classifyRegistrationFailure(
            firestore(FirebaseFirestoreException.Code.PERMISSION_DENIED),
            true
        )

        assertTrue("A denied read must not be read as a verdict", outcome is RefreshOutcome.Failed)
        assertTrue(
            "A denied read must never produce a status at all",
            outcome !is RefreshOutcome.Confirmed
        )
    }

    @Test
    fun `a backend failure with a working network is distinguishable from being offline`() {
        val failed = classifyRegistrationFailure(IllegalStateException("backend"), true)

        assertTrue(failed is RefreshOutcome.Failed)
        assertTrue(failed != RefreshOutcome.NoNetwork)
    }

    @Test
    fun `an unknown or missing status is never read as approval`() {
        assertEquals(AccountStatus.NEW, parseStatus(null))
        assertEquals(AccountStatus.NEW, parseStatus(""))
        assertEquals(AccountStatus.NEW, parseStatus("APPROVED"))
        assertEquals(AccountStatus.NEW, parseStatus("something-new"))
    }

    @Test
    fun `the four server values map to themselves`() {
        assertEquals(AccountStatus.PENDING, parseStatus("pending"))
        assertEquals(AccountStatus.APPROVED, parseStatus("approved"))
        assertEquals(AccountStatus.REJECTED, parseStatus("rejected"))
        assertEquals(AccountStatus.REVOKED, parseStatus("revoked"))
    }
}
