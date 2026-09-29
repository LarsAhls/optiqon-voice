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

    // FR-1 — a hold outlives the process, and stays with the account it was placed on.

    @Test
    fun `a hold survives a restart and the worker's query does not return held rows`() = runTest {
        val dao = db.outboxDao()
        dao.insert(entry("1", "uid-a"))
        dao.insert(entry("2", "uid-b"))

        assertEquals(1, dao.holdPendingFor("uid-a"))
        restartProcess()

        assertEquals(OutboxState.HELD, db.outboxDao().byId("1")!!.state)
        assertTrue(db.outboxDao().pendingFor("uid-a").isEmpty())
        assertEquals(listOf("1"), db.outboxDao().heldFor("uid-a").map { it.id })
        // Another account's row is neither held nor released by it.
        assertEquals(OutboxState.PENDING, db.outboxDao().byId("2")!!.state)
        assertEquals(0, db.outboxDao().releaseHeldFor("uid-b"))
    }

    @Test
    fun `only waiting case rows are held, never a removal, a legacy row or a blocked one`() = runTest {
        val dao = db.outboxDao()
        dao.insert(entry("1", "uid-a"))
        dao.insert(entry("2", "uid-a").copy(kind = "attachment_delete"))
        dao.insert(entry("3", "uid-a").copy(kind = "feedback"))
        dao.insert(entry("4", "uid-a"))
        dao.updateState("4", OutboxState.BLOCKED, attempts = 1, error = "PERMISSION_DENIED")

        assertEquals(1, dao.holdPendingFor("uid-a"))

        assertEquals(OutboxState.HELD, dao.byId("1")!!.state)
        assertEquals(OutboxState.PENDING, dao.byId("2")!!.state)
        assertEquals(OutboxState.PENDING, dao.byId("3")!!.state)
        assertEquals(OutboxState.BLOCKED, dao.byId("4")!!.state)
    }

    @Test
    fun `releasing puts held rows back in their order, once`() = runTest {
        val dao = db.outboxDao()
        dao.insert(entry("1", "uid-a"))
        dao.insert(entry("2", "uid-a"))
        dao.holdPendingFor("uid-a")

        assertEquals(2, dao.releaseHeldFor("uid-a"))
        assertEquals(0, dao.releaseHeldFor("uid-a"))
        assertEquals(listOf("1", "2"), dao.pendingFor("uid-a").map { it.id })
    }

    @Test
    fun `a send's outcome is written only over a row still pending, never over a hold`() = runTest {
        val dao = db.outboxDao()
        dao.insert(entry("1", "uid-a"))
        dao.insert(entry("2", "uid-a"))
        dao.insert(entry("3", "uid-a"))
        // Row 2 was inside the sender when the hold landed; row 3 was taken back meanwhile.
        dao.holdPendingFor("uid-a")
        dao.releaseHeldFor("uid-a")
        dao.holdPendingFor("uid-a")
        dao.discard("3")
        dao.releaseHeldFor("uid-a")
        dao.holdPendingFor("uid-a")

        assertEquals(0, dao.completeIfPending("2", OutboxState.SENT, attempts = 1, error = null))
        assertEquals(0, dao.completeIfPending("2", OutboxState.BLOCKED, attempts = 1, error = "PERMISSION_DENIED"))
        assertEquals(0, dao.completeIfPending("3", OutboxState.SENT, attempts = 1, error = null))
        assertEquals(OutboxState.HELD, dao.byId("2")!!.state)
        assertEquals(0, dao.byId("2")!!.attempts)
        assertEquals(null, dao.byId("3"))

        dao.releaseHeldFor("uid-a")
        assertEquals(1, dao.completeIfPending("1", OutboxState.SENT, attempts = 1, error = null))
        assertEquals(0, dao.completeIfPending("1", OutboxState.PENDING, attempts = 2, error = "late"))
        assertEquals(OutboxState.SENT, dao.byId("1")!!.state)
        assertEquals(1, dao.byId("1")!!.attempts)
    }

    // FS-S34: a discard and the withdrawal it leaves behind, as one.

    private fun intent(id: String) = entry(id, "uid-a").copy(kind = "feedback_withdrawal", payload = "{}")

    /** Makes SQLite refuse to delete row [id]: a process dying right there, mid-transaction. */
    private fun dieDeleting(id: String) = db.openHelper.writableDatabase.execSQL(
        "CREATE TRIGGER die BEFORE DELETE ON outbox WHEN OLD.id = '$id' BEGIN SELECT RAISE(ABORT, 'died'); END"
    )

    /** The same for writing row [id]. */
    private fun dieWriting(id: String) = db.openHelper.writableDatabase.execSQL(
        "CREATE TRIGGER die BEFORE INSERT ON outbox WHEN NEW.id = '$id' BEGIN SELECT RAISE(ABORT, 'died'); END"
    )

    @Test
    fun `a discard and its withdrawal land together and survive a restart`() = runTest {
        val dao = db.outboxDao()
        dao.insert(entry("1", "uid-a"))
        dao.insert(entry("2", "uid-a"))

        dao.enqueueAndDiscard(listOf(intent("9")), listOf("1", "2"))
        restartProcess()

        assertEquals(listOf("9"), db.outboxDao().all().map { it.id })
    }

    @Test
    fun `a discard that dies part-way leaves every row and no withdrawal`() = runTest {
        val dao = db.outboxDao()
        dao.insert(entry("1", "uid-a"))
        dao.insert(entry("2", "uid-a"))
        // The intent is written and row 1 deleted before row 2's delete dies.
        dieDeleting("2")

        runCatching { dao.enqueueAndDiscard(listOf(intent("9")), listOf("1", "2")) }
            .also { assertTrue("the delete did die", it.isFailure) }
        restartProcess()

        assertEquals("both or neither", listOf("1", "2"), db.outboxDao().all().map { it.id })
    }

    @Test
    fun `a withdrawal that cannot be written leaves the rows it would take back`() = runTest {
        val dao = db.outboxDao()
        dao.insert(entry("1", "uid-a"))
        dieWriting("9")

        runCatching { dao.enqueueAndDiscard(listOf(intent("9")), listOf("1")) }
            .also { assertTrue(it.isFailure) }
        restartProcess()

        assertEquals(listOf("1"), db.outboxDao().all().map { it.id })
    }
}
