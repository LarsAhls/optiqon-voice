package se.optiqon.voice.domain.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.optiqon.voice.data.db.entity.OutboxEntry
import se.optiqon.voice.data.db.entity.OutboxState

/**
 * The negative tests §3.19 and §3.22: an account switch must not send one person's work under
 * another person's identity, and a permanent refusal must not throw that work away.
 *
 * [se.optiqon.voice.data.db.OutboxPersistenceTest] proves the same rules survive Room and a
 * restart; these pin the decisions themselves.
 */
class OutboxPolicyTest {

    private fun entry(
        id: String,
        ownerUid: String,
        state: OutboxState = OutboxState.PENDING
    ) = OutboxEntry(
        id = id,
        ownerUid = ownerUid,
        kind = "case_create",
        payload = """{"title":"anteckning"}""",
        createdAtMs = 1_000L,
        state = state
    )

    private val a = entry("1", "uid-a")
    private val b = entry("2", "uid-b")

    // §3.19 — a pending write during an account switch.

    @Test
    fun `only the signed-in account's rows are sent`() {
        assertEquals(listOf(a), OutboxPolicy.flushable(listOf(a, b), activeUid = "uid-a"))
    }

    @Test
    fun `signing in as somebody else does not pick up the previous account's row`() {
        assertEquals(listOf(b), OutboxPolicy.flushable(listOf(a, b), activeUid = "uid-b"))
    }

    @Test
    fun `nothing is sent while nobody is signed in`() {
        assertTrue(OutboxPolicy.flushable(listOf(a, b), activeUid = null).isEmpty())
    }

    @Test
    fun `a row held back for its owner is still there, waiting`() {
        // Dormant, not discarded: the row A left behind reappears the moment A returns.
        val whileBIsSignedIn = OutboxPolicy.waitingForAnotherAccount(listOf(a, b), activeUid = "uid-b")

        assertEquals(listOf(a), whileBIsSignedIn)
        assertEquals(listOf(a), OutboxPolicy.flushable(listOf(a, b), activeUid = "uid-a"))
    }

    @Test
    fun `a sent row is neither flushed again nor reported as waiting`() {
        val sent = entry("3", "uid-a", state = OutboxState.SENT)

        assertTrue(OutboxPolicy.flushable(listOf(sent), activeUid = "uid-a").isEmpty())
        assertTrue(OutboxPolicy.waitingForAnotherAccount(listOf(sent), activeUid = "uid-b").isEmpty())
    }

    @Test
    fun `a blocked row is not retried behind the user's back`() {
        val blocked = entry("4", "uid-a", state = OutboxState.BLOCKED)

        assertTrue(OutboxPolicy.flushable(listOf(blocked), activeUid = "uid-a").isEmpty())
    }

    // §3.22 — a permanent PERMISSION_DENIED.

    @Test
    fun `a permanent refusal parks the row instead of deleting it`() {
        val parked = OutboxPolicy.afterFailure(a, SendFailure.Permanent("PERMISSION_DENIED"))

        assertEquals(OutboxState.BLOCKED, parked.state)
        assertEquals("PERMISSION_DENIED", parked.lastError)
        // The payload — the pointer to the user's draft or image — is untouched.
        assertEquals(a.payload, parked.payload)
        assertEquals(a.ownerUid, parked.ownerUid)
    }

    @Test
    fun `a permanent refusal stops the retries`() {
        assertFalse(OutboxPolicy.shouldRetry(SendFailure.Permanent("PERMISSION_DENIED")))
    }

    @Test
    fun `a timeout is not treated as a refusal`() {
        // A 502 or a dropped connection says nothing about permission. Parking the row here
        // would strand work that a later attempt would have delivered.
        val retried = OutboxPolicy.afterFailure(a, SendFailure.Transient("timeout"))

        assertEquals(OutboxState.PENDING, retried.state)
        assertEquals(1, retried.attempts)
        assertTrue(OutboxPolicy.shouldRetry(SendFailure.Transient("timeout")))
    }

    @Test
    fun `attempts are counted for both kinds of failure`() {
        val once = OutboxPolicy.afterFailure(a, SendFailure.Transient("timeout"))
        val twice = OutboxPolicy.afterFailure(once, SendFailure.Permanent("PERMISSION_DENIED"))

        assertEquals(2, twice.attempts)
    }
}
