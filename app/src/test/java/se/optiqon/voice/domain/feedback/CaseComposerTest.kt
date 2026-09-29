package se.optiqon.voice.domain.feedback

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.optiqon.voice.data.db.entity.OutboxEntry
import se.optiqon.voice.data.db.entity.OutboxState
import se.optiqon.voice.testing.MemoryOutboxDao
import se.optiqon.voice.testing.SwitchableAuth

class CaseComposerTest {

    private val outbox = MemoryOutboxDao()
    private val auth = SwitchableAuth("uid-a")
    private var approved = true
    private var gen: Long? = 4L
    private var scheduled = 0
    private var ids = 0

    private val composer = CaseComposer(
        outbox = outbox,
        auth = auth,
        approval = { approved },
        generation = { gen },
        scheduler = { scheduled++ },
        build = FeedbackBuildInfo("1.2.3", 34, "Pixel Test"),
        now = { 1_000L },
        newId = { "id-${ids++}" }
    )

    private fun image(name: String = "x.png", bytes: Int = 100) = PreparedImage(name, "image/png", bytes)

    private fun decoded() = outbox.rows.map { CaseOutboxPayloads.decode(it.kind, it.payload) }

    @Test
    fun `every case, message and screenshot row carries the generation it was written under`() = runTest {
        val caseId = (composer.createCase("hej", listOf(image("a.png"))) as ComposeOutcome.Queued).caseId
        composer.addMessage(caseId, "svar", listOf(image("b.png")))

        val stamps = decoded().map { (it as CasePayload.Stamped).generation }
        assertEquals(listOf(4L, 4L, 4L, 4L), stamps)
    }

    @Test
    fun `a generation survives the trip through the queue, and a row without one reads back as none`() {
        val msg = CasePayload.Message("c", "m", "b", 9L)
        assertEquals(msg, CaseOutboxPayloads.decode(CaseOutboxPayloads.KIND_MESSAGE, CaseOutboxPayloads.encode(msg)))
        val old = CasePayload.CreateCase("c", "t", "b")
        val back = CaseOutboxPayloads.decode(CaseOutboxPayloads.KIND_CREATE, CaseOutboxPayloads.encode(old))
        assertEquals(null, (back as CasePayload.Stamped).generation)
    }

    @Test
    fun `a new case is queued before its screenshots, one millisecond apart`() = runTest {
        val outcome = composer.createCase("Knappen svarar inte\nDetaljer", listOf(image("a.png"), image("b.png")))

        val caseId = (outcome as ComposeOutcome.Queued).caseId
        val payloads = decoded()
        assertTrue(payloads[0] is CasePayload.CreateCase)
        assertEquals(listOf(null, null), payloads.drop(1).map { (it as CasePayload.Upload).messageId })
        assertTrue(payloads.all { it!!.caseId == caseId })
        assertEquals(listOf(1_000L, 1_001L, 1_002L), outbox.rows.map { it.createdAtMs })
        assertTrue(outbox.rows.all { it.ownerUid == "uid-a" && it.state == OutboxState.PENDING })
        assertEquals(1, scheduled)
    }

    @Test
    fun `the title is the first line and the body names the build`() = runTest {
        composer.createCase("Första raden\nandra", emptyList())
        val create = decoded().single() as CasePayload.CreateCase
        assertEquals("Första raden", create.title)
        assertTrue(create.body.startsWith("Första raden\nandra"))
        assertTrue(create.body.contains("1.2.3 · Android 34 · Pixel Test"))
    }

    @Test
    fun `a long first line is cut to the title ceiling`() = runTest {
        composer.createCase("x".repeat(200), emptyList())
        val title = (decoded().single() as CasePayload.CreateCase).title
        assertEquals(FeedbackLimits.MAX_TITLE_CHARS, title.length)
    }

    @Test
    fun `an account that is not approved queues nothing`() = runTest {
        approved = false
        assertEquals(ComposeOutcome.NotApproved, composer.createCase("hej", listOf(image())))
        assertEquals(ComposeOutcome.NotApproved, composer.addMessage("c", "hej", emptyList()))
        assertEquals(ComposeOutcome.NotApproved, composer.deleteScreenshot("c", "a"))
        assertTrue(outbox.rows.isEmpty())
        assertEquals(0, scheduled)
    }

    @Test
    fun `signed out, empty, too long and too many screenshots are refused`() = runTest {
        assertEquals(ComposeOutcome.Empty, composer.createCase("   ", emptyList()))
        assertEquals(
            ComposeOutcome.TooLong,
            composer.createCase("x".repeat(FeedbackLimits.MAX_TEXT_CHARS + 1), emptyList())
        )
        assertEquals(ComposeOutcome.TooManyImages, composer.createCase("hej", List(4) { image("$it.png") }))
        assertEquals(
            ComposeOutcome.TooManyImages,
            composer.createCase("hej", listOf(image(bytes = FeedbackLimits.MAX_IMAGE_BYTES + 1)))
        )
        assertEquals(
            ComposeOutcome.TooManyImages,
            composer.createCase("hej", listOf(PreparedImage("x.gif", "image/gif", 10)))
        )
        auth.currentUid = null
        assertEquals(ComposeOutcome.SignedOut, composer.createCase("hej", emptyList()))
        assertTrue(outbox.rows.isEmpty())
    }

    @Test
    fun `a reply is a message followed by its own screenshots`() = runTest {
        composer.addMessage("case-1", "Mer info", listOf(image()))
        val (message, upload) = decoded()
        message as CasePayload.Message
        upload as CasePayload.Upload
        assertEquals("case-1", message.caseId)
        assertEquals(message.messageId, upload.messageId)
    }

    /** A case whose opening has reached the server, with its screenshot uploads still queued. */
    private suspend fun sentCase(vararg names: String): List<CasePayload.Upload> {
        composer.createCase("hej", names.map { image(it) })
        outbox.updateState(outbox.rows.first().id, OutboxState.SENT, 1, null)
        return decoded().filterIsInstance<CasePayload.Upload>()
    }

    @Test
    fun `taking a screenshot down drops its upload, waiting or refused, and queues the tombstone`() = runTest {
        val uploads = sentCase("a.png", "b.png", "c.png")
        val (waiting, refused, kept) = uploads
        outbox.updateState(outbox.rows[2].id, OutboxState.BLOCKED, 1, "no")

        composer.deleteScreenshot(waiting.caseId, waiting.aid)
        composer.deleteScreenshot(refused.caseId, refused.aid)

        val after = decoded()
        assertEquals(listOf(kept.aid), after.filterIsInstance<CasePayload.Upload>().map { it.aid })
        assertEquals(
            listOf(CasePayload.Tombstone(waiting.caseId, waiting.aid), CasePayload.Tombstone(refused.caseId, refused.aid)),
            after.filterIsInstance<CasePayload.Tombstone>()
        )
    }

    @Test
    fun `a second removal of the same screenshot replaces the first`() = runTest {
        val shot = sentCase("a.png").single()
        composer.deleteScreenshot(shot.caseId, shot.aid)
        outbox.updateState(outbox.rows.last().id, OutboxState.BLOCKED, 1, "offline too long")

        composer.deleteScreenshot(shot.caseId, shot.aid)

        val tombstones = outbox.rows.filter { it.kind == CaseOutboxPayloads.KIND_DELETE }
        assertEquals(OutboxState.PENDING, tombstones.single().state)
    }

    @Test
    fun `a screenshot of a case still on the device is dropped without a tombstone or approval`() = runTest {
        val caseId = (composer.createCase("hej", listOf(image("a.png"), image("b.png"))) as ComposeOutcome.Queued).caseId
        val (gone, kept) = decoded().filterIsInstance<CasePayload.Upload>()
        approved = false

        assertEquals(ComposeOutcome.Queued(caseId), composer.deleteScreenshot(caseId, gone.aid))

        val after = decoded()
        assertTrue(after.none { it is CasePayload.Tombstone })
        assertEquals(listOf(kept.aid), after.filterIsInstance<CasePayload.Upload>().map { it.aid })
    }

    @Test
    fun `a screenshot removed before it left is never uploaded`() = runTest {
        val shot = sentCase("a.png").single()
        composer.deleteScreenshot(shot.caseId, shot.aid)
        val sent = mutableListOf<String>()
        val flush = se.optiqon.voice.domain.sync.OutboxFlush(
            outbox, auth, { true }, { entry -> sent += entry.kind; null }, remoteEnabled = true
        )

        flush.run()

        assertEquals(listOf(CaseOutboxPayloads.KIND_DELETE), sent)
    }

    @Test
    fun `an action that cannot be written whole leaves nothing behind`() = runTest {
        val clash = CaseComposer(
            outbox = outbox, auth = auth, approval = { true }, generation = { 4L }, scheduler = { scheduled++ },
            build = FeedbackBuildInfo("1.2.3", 34, "Pixel Test"), now = { 1_000L },
            newId = { "same" }
        )

        runCatching { clash.createCase("hej", listOf(image("a.png"))) }

        assertTrue(outbox.rows.isEmpty())
        assertEquals(0, scheduled)
    }

    private suspend fun legacyRow(owner: String, id: String = "legacy-1") = OutboxEntry(
        id = id,
        ownerUid = owner,
        kind = FeedbackPayloads.KIND,
        payload = FeedbackPayloads.encode(FeedbackPayload("Gammalt meddelande", "mig@example.test", "1.0", 33, "P", 5L)),
        createdAtMs = 5L,
        state = OutboxState.PENDING
    ).also { outbox.insert(it) }

    @Test
    fun `a legacy row becomes a case only when its owner sends it`() = runTest {
        val row = legacyRow("uid-a")

        val outcome = composer.sendLegacy(row)

        assertTrue(outcome is ComposeOutcome.Queued)
        assertTrue(outbox.rows.none { it.id == row.id })
        val create = decoded().single() as CasePayload.CreateCase
        assertTrue(create.body.startsWith("Gammalt meddelande\n\nmig@example.test"))
    }

    @Test
    fun `a legacy row of another account or of an unapproved one stays exactly as it was`() = runTest {
        val other = legacyRow("uid-b", "legacy-b")
        assertEquals(ComposeOutcome.Empty, composer.sendLegacy(other))

        val mine = legacyRow("uid-a", "legacy-a")
        approved = false
        assertEquals(ComposeOutcome.NotApproved, composer.sendLegacy(mine))

        assertEquals(listOf(other, mine), outbox.rows)
    }

    @Test
    fun `a legacy row is deleted only by its own account`() = runTest {
        val other = legacyRow("uid-b", "legacy-b")
        val mine = legacyRow("uid-a", "legacy-a")

        assertEquals(false, composer.discardLegacy(other.id))
        assertEquals(false, composer.discardLegacy("missing"))
        assertEquals(true, composer.discardLegacy(mine.id))

        assertEquals(listOf(other), outbox.rows)
    }

    @Test
    fun `a legacy id never deletes a case row`() = runTest {
        composer.createCase("hej", emptyList())
        val create = outbox.rows.single()

        assertEquals(false, composer.discardLegacy(create.id))
        assertEquals(listOf(create), outbox.rows)
    }

    @Test
    fun `a queued case is taken back whole and hands back its screenshot files, even when not approved`() = runTest {
        val caseId = (composer.createCase("hej", listOf(image("a.png"), image("b.png"))) as ComposeOutcome.Queued).caseId
        composer.createCase("en annan", listOf(image("c.png")))
        approved = false

        val files = composer.discardQueuedCase(caseId)

        assertEquals(listOf("a.png", "b.png"), files)
        assertTrue(decoded().none { it!!.caseId == caseId })
        assertEquals(2, outbox.rows.size)
    }

    @Test
    fun `a case whose opening has left the device, or of another account, is not taken back`() = runTest {
        val caseId = (composer.createCase("hej", listOf(image("a.png"))) as ComposeOutcome.Queued).caseId
        val create = outbox.rows.first()
        outbox.updateState(create.id, OutboxState.SENT, 1, null)
        val before = outbox.rows.toList()

        assertEquals(null, composer.discardQueuedCase(caseId))

        auth.currentUid = "uid-b"
        assertEquals(null, composer.discardQueuedCase(caseId))
        assertEquals(before, outbox.rows)
    }
}
