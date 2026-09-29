package se.optiqon.voice.domain.feedback

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.optiqon.voice.data.db.entity.OutboxEntry
import se.optiqon.voice.data.db.entity.OutboxState
import se.optiqon.voice.di.FeedbackStorageModule
import se.optiqon.voice.domain.access.AccountStatus
import se.optiqon.voice.domain.access.ServerVerdictListener
import se.optiqon.voice.domain.sync.OutboxFlush
import se.optiqon.voice.domain.sync.OutboxSender
import se.optiqon.voice.domain.sync.SendFailure
import se.optiqon.voice.testing.AccessFixture
import se.optiqon.voice.testing.MemoryOutboxDao

/**
 * Feedback queued by an approved account that then loses approval is not sent just because the
 * account is approved again. It waits for its owner to choose.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class FeedbackReapprovalTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val outbox = MemoryOutboxDao()
    private val sent = mutableListOf<String>()
    private var failNext: SendFailure? = null
    private var duringSend: suspend (OutboxEntry) -> Unit = {}
    private val sender = OutboxSender { entry ->
        duringSend(entry)
        failNext?.also { failNext = null } ?: run { sent += entry.id; null }
    }
    private var schedules = 0

    /** What the app wires in. Swappable, so a device from before the hold can be played. */
    private var listener: ServerVerdictListener = FeedbackStorageModule.provideServerVerdictListener(outbox)

    private fun fixture(scope: CoroutineScope) = AccessFixture(
        context, scope,
        verdictListener = object : ServerVerdictListener {
            override suspend fun beforeRecord(uid: String, previous: AccountStatus?, status: AccountStatus) =
                listener.beforeRecord(uid, previous, status)

            override suspend fun beforeRecord(
                uid: String, previous: AccountStatus?, status: AccountStatus, previousGeneration: Long?, generation: Long
            ) = listener.beforeRecord(uid, previous, status, previousGeneration, generation)
        }
    )

    private fun composer(f: AccessFixture) = CaseComposer(
        outbox, f.auth, FeedbackStorageModule.provideApprovalCheck(f.repository),
        FeedbackStorageModule.provideApprovalGeneration(f.repository),
        { schedules++ }, FeedbackBuildInfo("1", 34, "x")
    )

    /** An unrelated run, as WorkManager would start one: nothing here knows about the hold. */
    private fun flush(f: AccessFixture, remoteEnabled: Boolean = true) =
        FeedbackStorageModule.provideOutboxFlush(
            outbox, f.auth, FeedbackStorageModule.provideApprovalCheck(f.repository),
            sender, FeedbackConfig("gs://b", remoteEnabled = remoteEnabled)
        )

    private fun image(name: String) = PreparedImage(name, "image/png", 100)

    private fun payloads(state: OutboxState? = null) = outbox.rows
        .filter { state == null || it.state == state }
        .mapNotNull { CaseOutboxPayloads.decode(it.kind, it.payload) }

    private fun legacyRow(owner: String) = OutboxEntry(
        id = "legacy-$owner",
        ownerUid = owner,
        kind = FeedbackPayloads.KIND,
        payload = FeedbackPayloads.encode(FeedbackPayload("Gammalt", null, "1.0", 33, "P", 5L)),
        createdAtMs = 1L,
        state = OutboxState.PENDING
    )

    private suspend fun approvedWithCase(f: AccessFixture, uid: String = "uid-a"): String {
        f.signIn(uid)
        f.recordServerVerdict(AccountStatus.APPROVED)
        return (composer(f).createCase("hej", listOf(image("$uid.png"))) as ComposeOutcome.Queued).caseId
    }

    // 1–2: the gap, and an unrelated run after reapproval.

    @Test
    fun `queued feedback is not sent after revoke and reapproval`() = runTest {
        val f = fixture(backgroundScope)
        approvedWithCase(f)

        f.recordServerVerdict(AccountStatus.REVOKED)
        f.recordServerVerdict(AccountStatus.APPROVED)
        assertEquals(OutboxFlush.Result.DONE, flush(f).run())

        assertTrue("nothing may leave without the owner's word, sent=$sent", sent.isEmpty())
        assertTrue(outbox.rows.none { it.state == OutboxState.SENT })
        assertTrue(outbox.rows.all { it.state == OutboxState.HELD })
    }

    @Test
    fun `every withdrawing verdict holds, and repeated runs still send nothing`() = runTest {
        for (withdrawn in listOf(AccountStatus.PENDING, AccountStatus.REJECTED, AccountStatus.REVOKED)) {
            val f = fixture(backgroundScope)
            approvedWithCase(f, "uid-$withdrawn")
            f.recordServerVerdict(withdrawn)
            f.recordServerVerdict(AccountStatus.APPROVED)
            repeat(3) { flush(f).run() }
            f.recordServerVerdict(AccountStatus.APPROVED)
            flush(f).run()
        }
        assertTrue("sent=$sent", sent.isEmpty())
    }

    // 3: restart between revoke and reapproval.

    @Test
    fun `a process restart between revoke and reapproval does not release the hold`() = runTest {
        val before = fixture(backgroundScope)
        approvedWithCase(before)
        before.recordServerVerdict(AccountStatus.REVOKED)

        // A new process: new access state, nothing carried over but the outbox on disk.
        val after = fixture(backgroundScope)
        after.signIn("uid-a")
        after.recordServerVerdict(AccountStatus.APPROVED)
        flush(after).run()

        assertTrue("sent=$sent", sent.isEmpty())
    }

    @Test
    fun `a device that stored the revocation before the hold existed holds on reapproval`() = runTest {
        listener = ServerVerdictListener.NONE
        val f = fixture(backgroundScope)
        approvedWithCase(f)
        f.recordServerVerdict(AccountStatus.REVOKED)
        assertTrue(outbox.rows.all { it.state == OutboxState.PENDING })

        listener = FeedbackStorageModule.provideServerVerdictListener(outbox)
        f.recordServerVerdict(AccountStatus.APPROVED)
        flush(f).run()

        assertTrue("sent=$sent", sent.isEmpty())
        assertTrue(outbox.rows.all { it.state == OutboxState.HELD })
    }

    @Test
    fun `an approval that stays approved holds nothing`() = runTest {
        val f = fixture(backgroundScope)
        approvedWithCase(f)
        f.recordServerVerdict(AccountStatus.APPROVED)
        flush(f).run()

        assertEquals(2, sent.size)
    }

    // 3b: a new approval generation the device only sees as approved -> approved.

    @Test
    fun `a reapproval seen only as a new generation holds just the same`() = runTest {
        val f = fixture(backgroundScope)
        approvedWithCase(f)
        // The revocation came and went while this device was not looking.
        f.recordServerVerdict(AccountStatus.APPROVED, generation = 1L)
        flush(f).run()

        assertTrue("sent=$sent", sent.isEmpty())
        assertTrue(outbox.rows.all { it.state == OutboxState.HELD })
    }

    @Test
    fun `the same generation again holds nothing`() = runTest {
        val f = fixture(backgroundScope)
        f.signIn("uid-a")
        f.recordServerVerdict(AccountStatus.APPROVED, generation = 2L)
        composer(f).createCase("hej", listOf(image("a.png")))
        f.recordServerVerdict(AccountStatus.APPROVED, generation = 2L)
        flush(f).run()

        assertEquals(2, sent.size)
    }

    @Test
    fun `send restamps held rows with the current generation, once, and only held ones`() = runTest {
        val f = fixture(backgroundScope)
        approvedWithCase(f)
        f.recordServerVerdict(AccountStatus.REVOKED)
        f.recordServerVerdict(AccountStatus.APPROVED, generation = 1L)
        assertEquals(listOf(0L, 0L), payloads(OutboxState.HELD).map { (it as CasePayload.Stamped).generation })

        composer(f).releaseHeld()
        assertEquals(listOf(1L, 1L), payloads(OutboxState.PENDING).map { (it as CasePayload.Stamped).generation })

        // A second Send finds nothing held and moves nothing a second time.
        assertEquals(HeldOutcome.NothingHeld, composer(f).releaseHeld())
        assertEquals(listOf(1L, 1L), payloads().map { (it as CasePayload.Stamped).generation })
    }

    @Test
    fun `an old row reaches the server only after send, and then under the new generation`() = runTest {
        val f = fixture(backgroundScope)
        val remote = se.optiqon.voice.testing.FakeCaseRemote()
        val real = se.optiqon.voice.data.feedback.CaseOutboxSender(
            remote, se.optiqon.voice.testing.FakeAttachmentStore(),
            se.optiqon.voice.data.storage.UserScopedStorage(context.cacheDir.resolve("reapproval")),
            f.auth, FeedbackStorageModule.provideApprovalGeneration(f.repository)
        )
        fun realFlush() = FeedbackStorageModule.provideOutboxFlush(
            outbox, f.auth, FeedbackStorageModule.provideApprovalCheck(f.repository), real, FeedbackConfig("gs://b", remoteEnabled = true)
        )
        f.signIn("uid-a")
        f.recordServerVerdict(AccountStatus.APPROVED)
        val caseId = (composer(f).createCase("hej", emptyList()) as ComposeOutcome.Queued).caseId

        // Revoked and approved again: the server is at generation 1, the row still says 0.
        remote.generations["uid-a"] = 1L
        f.recordServerVerdict(AccountStatus.REVOKED)
        f.recordServerVerdict(AccountStatus.APPROVED, generation = 1L)
        // Even a row a missed hold left pending is never tried under its old stamp.
        outbox.releaseHeldFor("uid-a")
        realFlush().run()
        assertTrue("an old stamp never reaches the server: ${remote.calls}", remote.calls.isEmpty())
        assertTrue(outbox.rows.all { it.state == OutboxState.HELD })

        composer(f).releaseHeld()
        realFlush().run()

        assertEquals("accepted", remote.caseStates[caseId])
        assertEquals(1L, remote.stamps[caseId])
        assertTrue(outbox.rows.all { it.state == OutboxState.SENT })
    }

    @Test
    fun `a screenshot overtaken by a reapproval on its way is held, then sent once under the new generation`() = runTest {
        val f = fixture(backgroundScope)
        val remote = se.optiqon.voice.testing.FakeCaseRemote()
        val storage = se.optiqon.voice.data.storage.UserScopedStorage(context.cacheDir.resolve("race"))
        val bucket = se.optiqon.voice.testing.FakeAttachmentStore()
        val real = se.optiqon.voice.data.feedback.CaseOutboxSender(
            remote, bucket, storage, f.auth, FeedbackStorageModule.provideApprovalGeneration(f.repository)
        )
        fun realFlush() = FeedbackStorageModule.provideOutboxFlush(
            outbox, f.auth, FeedbackStorageModule.provideApprovalCheck(f.repository), real, FeedbackConfig("gs://b", remoteEnabled = true)
        )
        f.signIn("uid-a")
        f.recordServerVerdict(AccountStatus.APPROVED)
        val shot = java.io.File(storage.attachmentsDir("uid-a"), "a.png").apply { writeBytes(ByteArray(100)) }
        composer(f).createCase("hej", listOf(image("a.png")))

        // The case goes through; the server moves on between the local check and the commit.
        remote.onCommit = { remote.generations["uid-a"] = 1L }
        realFlush().run()

        val upload = outbox.rows.single { it.kind == CaseOutboxPayloads.KIND_UPLOAD }
        assertEquals(OutboxState.HELD, upload.state)
        assertEquals(0, upload.attempts)
        assertTrue("the local copy waits for the owner", shot.exists())

        // The device hears of the new approval, and the owner sends.
        remote.onCommit = {}
        f.recordServerVerdict(AccountStatus.APPROVED, generation = 1L)
        composer(f).releaseHeld()
        assertEquals(HeldOutcome.NothingHeld, composer(f).releaseHeld())
        realFlush().run()

        assertTrue(outbox.rows.all { it.state == OutboxState.SENT })
        assertEquals(1L, remote.stamps.entries.single { it.key.startsWith("${remote.cases.keys.single()}/") }.value)
        assertEquals("refused once, then committed once", 2, remote.calls.count { it.startsWith("commit") })
        assertTrue("sent, so the copy is gone", !shot.exists())
    }

    // 4: Send.

    @Test
    fun `send puts the held rows back and schedules a run that sends them in order`() = runTest {
        val f = fixture(backgroundScope)
        approvedWithCase(f)
        f.recordServerVerdict(AccountStatus.REVOKED)
        f.recordServerVerdict(AccountStatus.APPROVED)
        val order = outbox.rows.sortedBy { it.createdAtMs }.map { it.id }

        val scheduledBefore = schedules
        assertEquals(HeldOutcome.Done(emptyList()), composer(f).releaseHeld())
        assertEquals(scheduledBefore + 1, schedules)
        flush(f).run()

        assertEquals(order, sent)
    }

    @Test
    fun `send needs approval now and leaves the hold in place without it`() = runTest {
        val f = fixture(backgroundScope)
        approvedWithCase(f)
        f.recordServerVerdict(AccountStatus.REVOKED)

        assertEquals(HeldOutcome.NotApproved, composer(f).releaseHeld())
        assertTrue(outbox.rows.all { it.state == OutboxState.HELD })
    }

    // 5: Discard.

    @Test
    fun `discard removes a held case with its own screenshots and queues removal only where needed`() = runTest {
        val f = fixture(backgroundScope)
        f.signIn("uid-a")
        f.recordServerVerdict(AccountStatus.APPROVED)
        val c = composer(f)
        // A case already on the server, then a held message with a screenshot on it.
        val onServer = (c.createCase("först", emptyList()) as ComposeOutcome.Queued).caseId
        flush(f).run()
        c.addMessage(onServer, "mer", listOf(image("server.png")))
        // A case that never left the device.
        val local = (c.createCase("lokal", listOf(image("local.png"))) as ComposeOutcome.Queued).caseId
        sent.clear()

        f.recordServerVerdict(AccountStatus.REVOKED)
        f.recordServerVerdict(AccountStatus.APPROVED)
        val outcome = c.discardHeld()

        assertEquals(setOf("server.png", "local.png"), (outcome as HeldOutcome.Done).files.toSet())
        assertTrue(payloads().none { it.caseId == local })
        assertTrue(outbox.rows.none { it.state == OutboxState.HELD })
        // What is left unsent is exactly one removal for the screenshot of the case on the server.
        val left = payloads(OutboxState.PENDING)
        assertEquals(1, outbox.rows.count { it.state != OutboxState.SENT })
        assertTrue(left.single() is CasePayload.Tombstone && left.single().caseId == onServer)

        flush(f).run()
        assertEquals(1, sent.size)
    }

    // 9: idempotency.

    @Test
    fun `a second send or discard finds nothing held and changes nothing`() = runTest {
        val f = fixture(backgroundScope)
        approvedWithCase(f)
        f.recordServerVerdict(AccountStatus.REVOKED)
        f.recordServerVerdict(AccountStatus.APPROVED)
        val c = composer(f)

        c.releaseHeld()
        val afterSend = outbox.rows
        assertEquals(HeldOutcome.NothingHeld, c.releaseHeld())
        assertEquals(HeldOutcome.NothingHeld, c.discardHeld())
        assertEquals(afterSend, outbox.rows)

        f.recordServerVerdict(AccountStatus.REVOKED)
        f.recordServerVerdict(AccountStatus.APPROVED)
        assertTrue(c.discardHeld() is HeldOutcome.Done)
        assertEquals(HeldOutcome.NothingHeld, c.discardHeld())
        assertEquals(HeldOutcome.NothingHeld, c.releaseHeld())
        assertTrue(outbox.rows.isEmpty())
    }

    // 6: account isolation.

    @Test
    fun `a hold is per account and survives a switch in both directions`() = runTest {
        val f = fixture(backgroundScope)
        approvedWithCase(f, "uid-a")
        f.recordServerVerdict(AccountStatus.REVOKED)
        val heldA = outbox.rows.filter { it.ownerUid == "uid-a" }

        approvedWithCase(f, "uid-b")
        // B's own verdicts never touch A's rows, and B's choice cannot release them.
        f.recordServerVerdict(AccountStatus.APPROVED)
        assertEquals(HeldOutcome.NothingHeld, composer(f).releaseHeld())
        assertEquals(HeldOutcome.NothingHeld, composer(f).discardHeld())
        flush(f).run()
        assertEquals(outbox.rows.filter { it.ownerUid == "uid-b" }.map { it.id }.toSet(), sent.toSet())
        assertEquals(heldA, outbox.rows.filter { it.ownerUid == "uid-a" })

        // A comes back approved: still held, until A chooses.
        f.signIn("uid-a")
        f.recordServerVerdict(AccountStatus.APPROVED)
        sent.clear()
        flush(f).run()
        assertTrue("sent=$sent", sent.isEmpty())
        assertEquals(heldA.map { it.id }, outbox.rows.filter { it.state == OutboxState.HELD }.map { it.id })
    }

    // 7: legacy.

    @Test
    fun `a legacy row is neither held nor sent across revoke and reapproval`() = runTest {
        val f = fixture(backgroundScope)
        f.signIn("uid-a")
        f.recordServerVerdict(AccountStatus.APPROVED)
        val legacy = legacyRow("uid-a")
        outbox.insert(legacy)

        f.recordServerVerdict(AccountStatus.REVOKED)
        f.recordServerVerdict(AccountStatus.APPROVED)
        flush(f).run()
        assertEquals(HeldOutcome.NothingHeld, composer(f).releaseHeld())
        flush(f).run()

        assertTrue("sent=$sent", sent.isEmpty())
        assertEquals(listOf(legacy), outbox.rows)
    }

    // 8: BLOCKED is not held, and held is not BLOCKED.

    @Test
    fun `a refused row stays blocked and is neither held nor released`() = runTest {
        val f = fixture(backgroundScope)
        approvedWithCase(f)
        failNext = SendFailure.Permanent("PERMISSION_DENIED")
        flush(f).run()
        val blocked = outbox.rows.single { it.state == OutboxState.BLOCKED }

        f.recordServerVerdict(AccountStatus.REVOKED)
        f.recordServerVerdict(AccountStatus.APPROVED)
        composer(f).releaseHeld()

        assertEquals(blocked, outbox.byId(blocked.id))
        val listed = LocalFeedback.queuedCases(outbox.rows, "uid-a").single()
        assertTrue(listed.blocked)
    }

    @Test
    fun `a held case reads as held, not as refused`() = runTest {
        val f = fixture(backgroundScope)
        approvedWithCase(f)
        f.recordServerVerdict(AccountStatus.REVOKED)

        val listed = LocalFeedback.queuedCases(outbox.rows, "uid-a").single()
        assertTrue(listed.held)
        assertTrue(!listed.blocked)
        assertEquals(2, LocalFeedback.heldCount(outbox.rows, "uid-a"))
    }

    // An upload already inside the sender when the hold came.

    @Test
    fun `a held row that fails in flight stays held`() = runTest {
        val f = fixture(backgroundScope)
        approvedWithCase(f)
        duringSend = { outbox.holdPendingFor("uid-a"); duringSend = {} }
        failNext = SendFailure.Transient("timeout")

        flush(f).run()

        assertTrue("sent=$sent", sent.isEmpty())
        assertTrue(outbox.rows.all { it.state == OutboxState.HELD })
    }

    /**
     * The sender is paused on the case opening; a real server verdict revokes the account while
     * it is inside; then the sender returns success. The remote may well have taken the row, but
     * the hold came first locally and must not be overwritten by the late success.
     */
    @Test
    fun `a row that succeeds in flight after the hold landed stays held, and so does its case`() = runTest {
        val f = fixture(backgroundScope)
        val caseId = approvedWithCase(f)
        val order = outbox.rows.sortedBy { it.createdAtMs }.map { it.id }
        val opening = order.first()
        val inside = CompletableDeferred<String>()
        val release = CompletableDeferred<Unit>()
        duringSend = { entry -> duringSend = {}; inside.complete(entry.id); release.await() }

        val run = async { flush(f).run() }
        assertEquals(opening, inside.await())
        f.recordServerVerdict(AccountStatus.REVOKED)
        assertEquals(OutboxState.HELD, outbox.byId(opening)?.state)
        release.complete(Unit)
        run.await()

        assertEquals(listOf(opening), sent)
        assertEquals(order.map { OutboxState.HELD }, order.map { outbox.byId(it)?.state })

        // Reapproval, a message added to the same case, an unrelated run, a restart: nothing.
        f.recordServerVerdict(AccountStatus.APPROVED)
        composer(f).addMessage(caseId, "en till", emptyList())
        flush(f).run()
        val restarted = fixture(backgroundScope)
        restarted.signIn("uid-a")
        restarted.recordServerVerdict(AccountStatus.APPROVED)
        flush(restarted).run()
        assertEquals("only the row that was already inside the sender", listOf(opening), sent)
        assertTrue(outbox.rows.none { it.state == OutboxState.SENT })

        // The owner's word: everything goes, the opening again — the remote is idempotent on it.
        composer(restarted).releaseHeld()
        flush(restarted).run()
        assertEquals(listOf(opening) + outbox.rows.sortedBy { it.createdAtMs }.map { it.id }, sent)
        assertTrue(outbox.rows.all { it.state == OutboxState.SENT })
    }

    @Test
    fun `a screenshot that succeeds in flight after the hold landed stays held`() = runTest {
        val f = fixture(backgroundScope)
        approvedWithCase(f)
        val (opening, upload) = outbox.rows.sortedBy { it.createdAtMs }.map { it.id }
        val inside = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        duringSend = { entry ->
            if (entry.id == upload) { duringSend = {}; inside.complete(Unit); release.await() }
        }

        val run = async { flush(f).run() }
        inside.await()
        f.recordServerVerdict(AccountStatus.REVOKED)
        release.complete(Unit)
        run.await()

        assertEquals(OutboxState.SENT, outbox.byId(opening)?.state)
        assertEquals(OutboxState.HELD, outbox.byId(upload)?.state)
        f.recordServerVerdict(AccountStatus.APPROVED)
        flush(f).run()
        assertEquals(listOf(opening, upload), sent)
    }

    @Test
    fun `a message added after reapproval waits behind its held case`() = runTest {
        val f = fixture(backgroundScope)
        val caseId = approvedWithCase(f)
        f.recordServerVerdict(AccountStatus.REVOKED)
        f.recordServerVerdict(AccountStatus.APPROVED)

        composer(f).addMessage(caseId, "en till", emptyList())
        flush(f).run()
        assertTrue("sent=$sent", sent.isEmpty())

        composer(f).releaseHeld()
        flush(f).run()
        assertEquals(outbox.rows.sortedBy { it.createdAtMs }.map { it.id }, sent)
    }

    // H: remote off.

    @Test
    fun `with remote feedback off, even a released hold never leaves the device`() = runTest {
        val f = fixture(backgroundScope)
        approvedWithCase(f)
        f.recordServerVerdict(AccountStatus.REVOKED)
        f.recordServerVerdict(AccountStatus.APPROVED)
        composer(f).releaseHeld()

        assertEquals(OutboxFlush.Result.DONE, flush(f, remoteEnabled = false).run())
        assertTrue("sent=$sent", sent.isEmpty())
    }
}
