package se.optiqon.voice.domain.feedback

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.optiqon.voice.data.db.dao.OutboxDao
import se.optiqon.voice.data.db.entity.OutboxEntry
import se.optiqon.voice.data.db.entity.OutboxState
import se.optiqon.voice.domain.access.AuthGateway

/**
 * Putting a feedback message on the queue — and, just as importantly, not sending it.
 *
 * This is the "built but not switched on" boundary in test form. Submitting must leave a row
 * on the device and must not attempt any network write; the only thing standing between the
 * row and Firestore is that nothing runs the worker and nothing binds a real sender. If a
 * later change makes submit start a send, [nothing is dispatched when a message is queued]
 * fails.
 */
class FeedbackQueueTest {

    private class FakeDao : OutboxDao {
        val rows = mutableListOf<OutboxEntry>()
        override suspend fun insert(entry: OutboxEntry) { rows += entry }
        override suspend fun pendingFor(ownerUid: String) =
            rows.filter { it.ownerUid == ownerUid && it.state == OutboxState.PENDING }
        override fun observeUnsent(): Flow<List<OutboxEntry>> = flowOf(rows.toList())
        override suspend fun all() = rows.toList()
        override suspend fun byId(id: String) = rows.firstOrNull { it.id == id }
        override suspend fun updateState(id: String, state: OutboxState, attempts: Int, error: String?) = Unit
        override suspend fun discard(id: String) { rows.removeAll { it.id == id } }
    }

    private class FakeAuth(override val currentUid: String?) : AuthGateway {
        override fun uidChanges(): Flow<String?> = flowOf(currentUid)
        override val currentEmail: String? = null
        override val currentDisplayName: String? = null
        override val isEmailVerified: Boolean = true
        override suspend fun reload() = Unit
        override suspend fun signOut() = Unit
    }

    private val build = FeedbackBuildInfo(
        appVersion = "1.4.2",
        androidSdk = 33,
        deviceModel = "OnePlus IN2023"
    )

    private fun queueOf(dao: OutboxDao, uid: String?) = FeedbackQueue(
        outbox = dao,
        auth = FakeAuth(uid),
        build = build,
        now = { 1_757_000_000_000 },
        newId = { "row-1" }
    )

    @Test
    fun `a message becomes one pending row owned by the account that wrote it`() = runTest {
        val dao = FakeDao()

        val outcome = queueOf(dao, "uid-abc").submit("the bubble vanishes", contact = "  ")

        assertEquals(FeedbackOutcome.Queued, outcome)
        assertEquals(1, dao.rows.size)
        val row = dao.rows.single()
        assertEquals("uid-abc", row.ownerUid)
        assertEquals(FeedbackPayloads.KIND, row.kind)
        assertEquals(OutboxState.PENDING, row.state)

        val payload = FeedbackPayloads.decode(row.payload)
        assertEquals("the bubble vanishes", payload.message)
        assertNull("a blank contact box is not a contact detail", payload.contact)
        assertEquals("1.4.2", payload.appVersion)
    }

    @Test
    fun `nothing is dispatched when a message is queued`() = runTest {
        // The queue is handed no sender and no worker: there is nothing here that could send.
        // The row is the whole effect, and it stays on the device until somebody deliberately
        // binds a sender and enqueues the worker.
        val dao = FakeDao()

        queueOf(dao, "uid-abc").submit("hello", contact = null)

        assertEquals(OutboxState.PENDING, dao.rows.single().state)
        assertEquals(0, dao.rows.single().attempts)
        assertNull(dao.rows.single().lastError)
    }

    @Test
    fun `an empty message is refused before anything is written`() = runTest {
        val dao = FakeDao()

        assertEquals(FeedbackOutcome.Empty, queueOf(dao, "uid-abc").submit("   ", contact = null))
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `a message too long for the box is refused rather than trimmed to fit`() = runTest {
        val dao = FakeDao()
        val tooLong = "a".repeat(FeedbackPayloads.MAX_MESSAGE_CHARS + 1)

        assertEquals(FeedbackOutcome.TooLong, queueOf(dao, "uid-abc").submit(tooLong, contact = null))
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `with nobody signed in the message is not written under some other owner`() = runTest {
        val dao = FakeDao()

        assertEquals(FeedbackOutcome.SignedOut, queueOf(dao, null).submit("hello", contact = null))
        assertTrue("a row with no owner could later be flushed by whoever signs in", dao.rows.isEmpty())
    }
}
