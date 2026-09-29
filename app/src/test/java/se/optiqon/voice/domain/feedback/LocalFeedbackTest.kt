package se.optiqon.voice.domain.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.optiqon.voice.data.db.entity.OutboxEntry
import se.optiqon.voice.data.db.entity.OutboxState

class LocalFeedbackTest {

    private var ids = 0

    private fun row(
        payload: CasePayload,
        owner: String = "uid-a",
        at: Long = 1L,
        state: OutboxState = OutboxState.PENDING
    ): OutboxEntry {
        val kind = when (payload) {
            is CasePayload.CreateCase -> CaseOutboxPayloads.KIND_CREATE
            is CasePayload.Message -> CaseOutboxPayloads.KIND_MESSAGE
            is CasePayload.Upload -> CaseOutboxPayloads.KIND_UPLOAD
            is CasePayload.Tombstone -> CaseOutboxPayloads.KIND_DELETE
            is CasePayload.Withdrawal -> CaseOutboxPayloads.KIND_WITHDRAW
        }
        return OutboxEntry("row-${ids++}", owner, kind, CaseOutboxPayloads.encode(payload), at, state)
    }

    private fun legacy(message: String, owner: String = "uid-a", at: Long = 1L, state: OutboxState = OutboxState.PENDING) =
        OutboxEntry(
            "legacy-${ids++}", owner, FeedbackPayloads.KIND,
            FeedbackPayloads.encode(FeedbackPayload(message, null, "1.0", 33, "P", at)), at, state
        )

    private fun upload(caseId: String, aid: String, messageId: String? = null) =
        CasePayload.Upload(caseId, messageId, aid, "$aid.png", "image/png", 10)

    @Test
    fun `a queued case carries only its opening screenshots, newest case first`() {
        val rows = listOf(
            row(CasePayload.CreateCase("c1", "Första", "b"), at = 1),
            row(upload("c1", "a1"), at = 2),
            row(upload("c1", "a2", messageId = "m1"), at = 3),
            row(CasePayload.CreateCase("c2", "Andra", "b"), at = 10, state = OutboxState.BLOCKED)
        )

        val cases = LocalFeedback.queuedCases(rows, "uid-a")

        assertEquals(listOf("c2", "c1"), cases.map { it.caseId })
        assertEquals(listOf("a1"), cases[1].images.map { it.aid })
        assertEquals(listOf(true, false), cases.map { it.blocked })
    }

    @Test
    fun `another account's rows and sent openings are not shown`() {
        val rows = listOf(
            row(CasePayload.CreateCase("mine", "t", "b")),
            row(CasePayload.CreateCase("theirs", "t", "b"), owner = "uid-b"),
            row(CasePayload.CreateCase("sent", "t", "b"), state = OutboxState.SENT),
            legacy("deras", owner = "uid-b")
        )

        assertEquals(listOf("mine"), LocalFeedback.queuedCases(rows, "uid-a").map { it.caseId })
        assertTrue(LocalFeedback.legacy(rows, "uid-a").isEmpty())
    }

    @Test
    fun `legacy notes are listed newest first and a broken one is skipped`() {
        val broken = OutboxEntry("broken", "uid-a", FeedbackPayloads.KIND, "{}", 7L, OutboxState.PENDING)
        val rows = listOf(legacy("  äldre ", at = 1), legacy("nyare", at = 5), broken, legacy("skickad", state = OutboxState.SENT))

        assertEquals(listOf("nyare", "äldre"), LocalFeedback.legacy(rows, "uid-a").map { it.message })
    }

    @Test
    fun `pending uploads are those of one case still on the device, openings and replies alike`() {
        val rows = listOf(
            row(upload("c1", "a1")),
            row(upload("c1", "a2", messageId = "m1")),
            row(upload("c1", "a3"), state = OutboxState.SENT),
            row(upload("c2", "b1")),
            row(upload("c1", "x1"), owner = "uid-b")
        )

        assertEquals(listOf("a1", "a2"), LocalFeedback.pendingUploads(rows, "uid-a", "c1").map { it.aid })
    }

    private fun attachment(aid: String, deleted: Boolean = false) = CaseAttachment(aid, null, deleted, null)

    private fun stateOf(rows: List<OutboxEntry>, aid: String, remote: List<CaseAttachment> = emptyList()) =
        LocalFeedback.shots(rows, "uid-a", "c1", remote).singleOrNull { it.aid == aid }?.state

    @Test
    fun `a refused upload is shown as failed, never as waiting`() {
        val rows = listOf(
            row(upload("c1", "a1"), state = OutboxState.BLOCKED),
            row(upload("c1", "a2"))
        )

        assertEquals(ShotState.UPLOAD_FAILED, stateOf(rows, "a1"))
        assertEquals(ShotState.UPLOADING, stateOf(rows, "a2"))
        // The document may already exist; the parked upload still outranks it.
        assertEquals(ShotState.UPLOAD_FAILED, stateOf(rows, "a1", listOf(attachment("a1"))))
    }

    @Test
    fun `a removal is pending until its tombstone is confirmed`() {
        val remote = listOf(attachment("a1"))
        val pending = listOf(row(CasePayload.Tombstone("c1", "a1")))
        val refused = listOf(row(CasePayload.Tombstone("c1", "a1"), state = OutboxState.BLOCKED))
        val confirmed = listOf(row(CasePayload.Tombstone("c1", "a1"), state = OutboxState.SENT))

        assertEquals(ShotState.AVAILABLE, stateOf(emptyList(), "a1", remote))
        assertEquals(ShotState.REMOVING, stateOf(pending, "a1", remote))
        assertEquals(ShotState.REMOVE_FAILED, stateOf(refused, "a1", remote))
        assertNull(stateOf(confirmed, "a1", remote))
        assertNull(stateOf(emptyList(), "a1", listOf(attachment("a1", deleted = true))))
        // A retried tombstone still pending beside a refused one is still under way.
        assertEquals(ShotState.REMOVING, stateOf(refused + pending, "a1", remote))
    }

    @Test
    fun `a screenshot only on the device is removing while its tombstone waits`() {
        val rows = listOf(row(upload("c1", "a1")), row(CasePayload.Tombstone("c1", "a1")))
        assertEquals(ShotState.REMOVING, stateOf(rows, "a1"))
    }

    @Test
    fun `shots of another account or another case are not shown`() {
        val rows = listOf(
            row(upload("c1", "theirs"), owner = "uid-b"),
            row(upload("c2", "other")),
            row(CasePayload.Tombstone("c1", "x"), owner = "uid-b", state = OutboxState.SENT)
        )
        assertEquals(listOf("x"), LocalFeedback.shots(rows, "uid-a", "c1", listOf(attachment("x"))).map { it.aid })
    }

    @Test
    fun `a copy no unsent upload names is an orphan`() {
        val rows = listOf(
            row(upload("c1", "queued")),
            row(upload("c1", "parked"), state = OutboxState.BLOCKED),
            row(upload("c1", "done"), state = OutboxState.SENT),
            row(upload("c1", "theirs"), owner = "uid-b")
        )
        val present = listOf("queued.png", "parked.png", "done.png", "theirs.png", "draft.png")

        assertEquals(listOf("done.png", "theirs.png", "draft.png"), LocalFeedback.orphanedFiles(rows, "uid-a", present))
    }

    @Test
    fun `a screenshot is drawn only until its owner asks for it to go`() {
        assertEquals(
            setOf(ShotState.UPLOADING, ShotState.UPLOAD_FAILED, ShotState.AVAILABLE),
            ShotState.entries.filter { it.showsContent }.toSet()
        )
    }

    @Test
    fun `only the account's delivered withdrawals answered already-received are told`() {
        fun w(id: String, outcome: String?) = CasePayload.Withdrawal(id, id, CasePayload.Withdrawal.TARGET_CASE, outcome)
        val told = row(w("c1", WithdrawalOutcome.IGNORED_ACCEPTED), state = OutboxState.SENT)
        val rows = listOf(
            told,
            row(w("c2", WithdrawalOutcome.WITHDRAWN), state = OutboxState.SENT),
            row(w("c3", WithdrawalOutcome.ABSENT), state = OutboxState.SENT),
            row(w("c4", null)),
            row(w("c5", WithdrawalOutcome.IGNORED_ACCEPTED), owner = "uid-b", state = OutboxState.SENT)
        )
        assertEquals(listOf(told.id), LocalFeedback.alreadyReceived(rows, "uid-a"))
        assertTrue("a withdrawal is never shown as a queued case", LocalFeedback.queuedCases(rows, "uid-a").isEmpty())
    }
}
