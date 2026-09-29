package se.optiqon.voice.domain.feedback

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.optiqon.voice.data.db.entity.OutboxEntry
import se.optiqon.voice.data.db.entity.OutboxState
import se.optiqon.voice.di.FeedbackStorageModule
import se.optiqon.voice.domain.access.AccountStatus
import se.optiqon.voice.domain.sync.OutboxSender
import se.optiqon.voice.domain.sync.SendFailure
import se.optiqon.voice.testing.AccessFixture
import se.optiqon.voice.testing.MemoryOutboxDao

/**
 * M6: a screenshot taken down while offline is a local-first pending delete. It is hidden on the
 * device at once and its intent is durable (an outbox row), but the device never says the copy
 * with Optiqon is gone until the server has acknowledged the tombstone. Once online the tombstone
 * makes the object unreadable; the purge itself (M5) is the server's business.
 *
 * M3: the owner's own removal needs no current approval. A revoked or pending owner may still
 * take a screenshot down, and a removal queued before a revocation is neither held nor lost.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ScreenshotPendingDeleteTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val outbox = MemoryOutboxDao()
    private val sent = mutableListOf<OutboxEntry>()
    private var offline = false
    private val sender = OutboxSender { entry ->
        if (offline) SendFailure.Transient("offline") else run { sent += entry; null }
    }

    private fun composer(f: AccessFixture) = CaseComposer(
        outbox, f.auth, FeedbackStorageModule.provideApprovalCheck(f.repository),
        FeedbackStorageModule.provideApprovalGeneration(f.repository),
        {}, FeedbackBuildInfo("1", 34, "x")
    )

    private fun flush(f: AccessFixture) = FeedbackStorageModule.provideOutboxFlush(
        outbox, f.auth, FeedbackStorageModule.provideApprovalCheck(f.repository),
        sender, FeedbackConfig("gs://b", remoteEnabled = true)
    )

    /** An approved account with one case and one screenshot, both delivered. */
    private suspend fun deliveredShot(f: AccessFixture, uid: String = "uid-a"): CasePayload.Upload {
        f.signIn(uid)
        f.recordServerVerdict(AccountStatus.APPROVED)
        composer(f).createCase("hej", listOf(PreparedImage("$uid.png", "image/png", 100)))
        flush(f).run()
        return outbox.rows.filter { it.ownerUid == uid }
            .mapNotNull { CaseOutboxPayloads.decode(it.kind, it.payload) as? CasePayload.Upload }
            .single()
    }

    private fun remote(aid: String, deleted: Boolean = false) = CaseAttachment(aid, null, deleted, 1L)

    private fun shot(uid: String, shot: CasePayload.Upload, remote: List<CaseAttachment> = listOf(remote(shot.aid))) =
        LocalFeedback.shots(outbox.rows, uid, shot.caseId, remote).singleOrNull { it.aid == shot.aid }

    private fun tombstones(uid: String = "uid-a") =
        outbox.rows.filter { it.ownerUid == uid && it.kind == CaseOutboxPayloads.KIND_DELETE }

    @Test
    fun `offline, the screenshot is hidden at once and the removal is only pending`() = runTest {
        val f = AccessFixture(context, backgroundScope)
        val s = deliveredShot(f)
        offline = true

        assertEquals(ComposeOutcome.Queued(s.caseId), composer(f).deleteScreenshot(s.caseId, s.aid))
        flush(f).run()

        val status = shot("uid-a", s)!!
        assertEquals(ShotState.REMOVING, status.state)
        assertFalse("never drawn once asked to go", status.state.showsContent)
        assertEquals(OutboxState.PENDING, tombstones().single().state)
    }

    @Test
    fun `the intent survives a restart and is confirmed only by the server's acknowledgement`() = runTest {
        val f = AccessFixture(context, backgroundScope)
        val s = deliveredShot(f)
        offline = true
        composer(f).deleteScreenshot(s.caseId, s.aid)
        repeat(3) { flush(f).run() }

        // A new process: new composer and flush over the same durable queue.
        assertEquals(ShotState.REMOVING, shot("uid-a", s)?.state)
        offline = false
        flush(f).run()

        assertEquals(listOf(CasePayload.Tombstone(s.caseId, s.aid)), sent.filter { it.kind == CaseOutboxPayloads.KIND_DELETE }
            .map { CaseOutboxPayloads.decode(it.kind, it.payload) })
        assertNull("gone once acknowledged", shot("uid-a", s))
    }

    @Test
    fun `a second tap while pending queues no second removal`() = runTest {
        val f = AccessFixture(context, backgroundScope)
        val s = deliveredShot(f)
        offline = true
        composer(f).deleteScreenshot(s.caseId, s.aid)
        composer(f).deleteScreenshot(s.caseId, s.aid)

        assertEquals(1, tombstones().size)
        offline = false
        flush(f).run()
        assertEquals(1, sent.count { it.kind == CaseOutboxPayloads.KIND_DELETE })
    }

    @Test
    fun `a document that shows up late never brings the screenshot back while the removal is pending`() = runTest {
        val f = AccessFixture(context, backgroundScope)
        val s = deliveredShot(f)
        offline = true
        composer(f).deleteScreenshot(s.caseId, s.aid)

        // The listing lags: the server still reports the attachment as live.
        assertEquals(ShotState.REMOVING, shot("uid-a", s, listOf(remote(s.aid)))?.state)
        // And a server that already reports it deleted does not wait for the device.
        assertNull(shot("uid-a", s, listOf(remote(s.aid, deleted = true))))
    }

    @Test
    fun `another account neither sees nor sends the pending removal`() = runTest {
        val f = AccessFixture(context, backgroundScope)
        val s = deliveredShot(f, "uid-a")
        offline = true
        composer(f).deleteScreenshot(s.caseId, s.aid)
        offline = false

        deliveredShot(f, "uid-b")
        sent.clear()
        flush(f).run()
        assertTrue("sent=$sent", sent.none { it.ownerUid == "uid-a" })
        // uid-b cannot list uid-a's case; its own queue says nothing about the screenshot.
        assertNull(shot("uid-b", s, emptyList()))

        f.signIn("uid-a")
        flush(f).run()
        assertEquals(listOf("uid-a"), sent.filter { it.kind == CaseOutboxPayloads.KIND_DELETE }.map { it.ownerUid })
    }

    @Test
    fun `a revoked owner can still take a screenshot down, and it goes while revoked`() = runTest {
        val f = AccessFixture(context, backgroundScope)
        val s = deliveredShot(f)
        f.recordServerVerdict(AccountStatus.REVOKED)
        sent.clear()

        assertEquals(ComposeOutcome.Queued(s.caseId), composer(f).deleteScreenshot(s.caseId, s.aid))
        flush(f).run()

        assertEquals(listOf(CaseOutboxPayloads.KIND_DELETE), sent.map { it.kind })
        assertNull(shot("uid-a", s))
    }

    @Test
    fun `a pending owner can take a screenshot down too`() = runTest {
        val f = AccessFixture(context, backgroundScope)
        val s = deliveredShot(f)
        f.recordServerVerdict(AccountStatus.PENDING)
        sent.clear()

        assertEquals(ComposeOutcome.Queued(s.caseId), composer(f).deleteScreenshot(s.caseId, s.aid))
        flush(f).run()
        assertEquals(listOf(CaseOutboxPayloads.KIND_DELETE), sent.map { it.kind })
    }

    @Test
    fun `a removal queued before a revocation is not held and is not sent twice after reapproval`() = runTest {
        val f = AccessFixture(context, backgroundScope)
        val s = deliveredShot(f)
        offline = true
        composer(f).deleteScreenshot(s.caseId, s.aid)

        f.recordServerVerdict(AccountStatus.REVOKED)
        assertEquals(OutboxState.PENDING, tombstones().single().state)
        offline = false
        flush(f).run()
        assertEquals(1, sent.count { it.kind == CaseOutboxPayloads.KIND_DELETE })

        f.recordServerVerdict(AccountStatus.APPROVED, generation = 1L)
        flush(f).run()
        assertEquals(1, sent.count { it.kind == CaseOutboxPayloads.KIND_DELETE })
        assertNull(shot("uid-a", s))
    }

    @Test
    fun `a revoked owner's other words still wait`() = runTest {
        val f = AccessFixture(context, backgroundScope)
        val s = deliveredShot(f)
        f.recordServerVerdict(AccountStatus.REVOKED)

        assertEquals(ComposeOutcome.NotApproved, composer(f).addMessage(s.caseId, "hej", emptyList()))
        assertEquals(ComposeOutcome.NotApproved, composer(f).createCase("ny", emptyList()))
    }
}
