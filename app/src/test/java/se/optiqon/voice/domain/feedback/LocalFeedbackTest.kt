package se.optiqon.voice.domain.feedback

import org.junit.Assert.assertEquals
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
}
