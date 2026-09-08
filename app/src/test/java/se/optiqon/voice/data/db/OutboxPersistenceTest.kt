package se.optiqon.voice.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.optiqon.voice.data.db.entity.OutboxEntry
import se.optiqon.voice.data.db.entity.OutboxState
import se.optiqon.voice.domain.sync.OutboxPolicy
import se.optiqon.voice.domain.sync.SendFailure

/**
 * The durable half of §3.19, §3.21 and §3.22. [se.optiqon.voice.domain.sync.OutboxPolicyTest]
 * pins the decisions; this pins that they survive a real database and a process that dies
 * mid-queue.
 *
 * The database is a file, not an in-memory one, precisely because "the app was killed" is the
 * case under test — an in-memory database would vanish along with the thing we are trying to
 * prove outlives it.
 */
@RunWith(RobolectricTestRunner::class)
class OutboxPersistenceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var name: String
    private lateinit var db: OptiqonVoiceDatabase

    @Before
    fun setUp() {
        name = "outbox-test-${System.nanoTime()}.db"
        db = open()
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(name)
    }

    private fun open() = Room.databaseBuilder(context, OptiqonVoiceDatabase::class.java, name).build()

    /** Closing and reopening the database is what a process death looks like from here. */
    private fun restartProcess() {
        db.close()
        db = open()
    }

    private fun entry(id: String, ownerUid: String) = OutboxEntry(
        id = id,
        ownerUid = ownerUid,
        kind = "case_create",
        payload = """{"title":"anteckning"}""",
        createdAtMs = id.toLong(),
        state = OutboxState.PENDING
    )

    // §3.19 — an account switch with a pending write.

    @Test
    fun `the query the worker uses cannot return another account's rows`() = runTest {
        db.outboxDao().insert(entry("1", "uid-a"))
        db.outboxDao().insert(entry("2", "uid-b"))

        assertEquals(listOf("1"), db.outboxDao().pendingFor("uid-a").map { it.id })
        assertEquals(listOf("2"), db.outboxDao().pendingFor("uid-b").map { it.id })
    }

    @Test
    fun `a row left by A is untouched while B is signed in and flushes when A returns`() = runTest {
        val dao = db.outboxDao()
        dao.insert(entry("1", "uid-a"))

        // B signs in, the worker runs, and finds nothing of its own to do.
        assertTrue(dao.pendingFor("uid-b").isEmpty())

        val still = dao.byId("1")
        assertNotNull(still)
        assertEquals("uid-a", still!!.ownerUid)
        assertEquals(OutboxState.PENDING, still.state)

        // A signs back in.
        assertEquals(listOf("1"), dao.pendingFor("uid-a").map { it.id })
    }

    // §3.21 — process death mid-queue.

    @Test
    fun `rows survive a restart with their owner intact`() = runTest {
        db.outboxDao().insert(entry("1", "uid-a"))
        db.outboxDao().insert(entry("2", "uid-b"))

        restartProcess()

        val rows = db.outboxDao().all()
        assertEquals(listOf("1", "2"), rows.map { it.id })
        assertEquals(listOf("uid-a", "uid-b"), rows.map { it.ownerUid })
        assertTrue(rows.all { it.state == OutboxState.PENDING })
    }

    @Test
    fun `a row already marked sent is not sent again after a restart`() = runTest {
        db.outboxDao().insert(entry("1", "uid-a"))
        db.outboxDao().updateState("1", OutboxState.SENT, attempts = 1, error = null)

        restartProcess()

        assertTrue(db.outboxDao().pendingFor("uid-a").isEmpty())
        assertEquals(OutboxState.SENT, db.outboxDao().byId("1")!!.state)
    }

    // §3.22 — a permanent refusal.

    @Test
    fun `a permission error parks the row without deleting it or retrying forever`() = runTest {
        val dao = db.outboxDao()
        dao.insert(entry("1", "uid-a"))

        val failed = OutboxPolicy.afterFailure(
            dao.byId("1")!!,
            SendFailure.Permanent("PERMISSION_DENIED")
        )
        dao.updateState(failed.id, failed.state, failed.attempts, failed.lastError)

        restartProcess()

        val parked = db.outboxDao().byId("1")!!
        assertEquals(OutboxState.BLOCKED, parked.state)
        assertEquals("PERMISSION_DENIED", parked.lastError)
        assertEquals(1, parked.attempts)
        // The next run does not pick it up again, and the payload is still there for the user.
        assertTrue(db.outboxDao().pendingFor("uid-a").isEmpty())
        assertEquals("""{"title":"anteckning"}""", parked.payload)
    }

    @Test
    fun `a transient failure leaves the row queued for the next run`() = runTest {
        val dao = db.outboxDao()
        dao.insert(entry("1", "uid-a"))

        val failed = OutboxPolicy.afterFailure(dao.byId("1")!!, SendFailure.Transient("timeout"))
        dao.updateState(failed.id, failed.state, failed.attempts, failed.lastError)

        assertEquals(listOf("1"), dao.pendingFor("uid-a").map { it.id })
        assertEquals(1, dao.byId("1")!!.attempts)
    }

    @Test
    fun `discarding a row is possible, but only by asking for it`() = runTest {
        // Nothing in the failure path calls this; it exists for the user's own decision.
        val dao = db.outboxDao()
        dao.insert(entry("1", "uid-a"))

        dao.discard("1")

        assertTrue(dao.all().isEmpty())
    }
}
