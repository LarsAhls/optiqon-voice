package se.optiqon.voice.domain.sync

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.optiqon.voice.data.db.entity.OutboxEntry
import se.optiqon.voice.data.db.entity.OutboxState
import se.optiqon.voice.domain.feedback.CaseOutboxPayloads
import se.optiqon.voice.domain.feedback.CasePayload
import se.optiqon.voice.domain.feedback.FeedbackPayloads
import se.optiqon.voice.testing.MemoryOutboxDao
import se.optiqon.voice.testing.SwitchableAuth

class OutboxFlushTest {

    private val outbox = MemoryOutboxDao()
    private val auth = SwitchableAuth("uid-a")
    private var approved = true
    private val sent = mutableListOf<String>()
    private val failures = mutableMapOf<String, SendFailure>()
    private var onSend: (OutboxEntry) -> Unit = {}

    private val sender = OutboxSender { entry ->
        sent += entry.id
        onSend(entry)
        failures[entry.id]
    }

    private fun flush(remote: Boolean = true) = OutboxFlush(outbox, auth, { approved }, sender, remote)

    private var clock = 0L
    private suspend fun row(id: String, payload: CasePayload, owner: String = "uid-a") = outbox.insert(
        OutboxEntry(
            id = id,
            ownerUid = owner,
            kind = CaseOutboxPayloads.kindOf(payload),
            payload = CaseOutboxPayloads.encode(payload),
            createdAtMs = clock++,
            state = OutboxState.PENDING
        )
    )

    private suspend fun legacy(id: String) = outbox.insert(
        OutboxEntry(id, "uid-a", FeedbackPayloads.KIND, "{}", clock++, OutboxState.PENDING)
    )

    private fun state(id: String) = outbox.rows.single { it.id == id }.state

    @Test
    fun `nothing moves while the remote channel is off, signed out or not approved`() = runTest {
        row("r1", CasePayload.CreateCase("c1", "t", "b"))
        val before = outbox.rows

        assertEquals(OutboxFlush.Result.DONE, flush(remote = false).run())
        approved = false
        assertEquals(OutboxFlush.Result.DONE, flush().run())
        approved = true
        auth.currentUid = null
        assertEquals(OutboxFlush.Result.DONE, flush().run())

        assertTrue(sent.isEmpty())
        assertEquals(before, outbox.rows)
    }

    @Test
    fun `legacy rows are never sent and never changed`() = runTest {
        legacy("old")
        row("r1", CasePayload.CreateCase("c1", "t", "b"))

        flush().run()

        assertEquals(listOf("r1"), sent)
        assertEquals(OutboxState.PENDING, state("old"))
        assertEquals(0, outbox.rows.single { it.id == "old" }.attempts)
    }

    @Test
    fun `another account's rows wait`() = runTest {
        row("mine", CasePayload.CreateCase("c1", "t", "b"))
        row("theirs", CasePayload.CreateCase("c2", "t", "b"), owner = "uid-b")

        flush().run()

        assertEquals(listOf("mine"), sent)
        assertEquals(OutboxState.PENDING, state("theirs"))
    }

    @Test
    fun `a failed row holds the rest of its case but not other cases`() = runTest {
        row("create-1", CasePayload.CreateCase("c1", "t", "b"))
        row("upload-1", CasePayload.Upload("c1", null, "a1", "a1.png", "image/png", 10))
        row("create-2", CasePayload.CreateCase("c2", "t", "b"))
        failures["create-1"] = SendFailure.Transient("offline")

        val result = flush().run()

        assertEquals(OutboxFlush.Result.RETRY, result)
        assertEquals(listOf("create-1", "create-2"), sent)
        assertEquals(OutboxState.PENDING, state("upload-1"))
        assertEquals(OutboxState.SENT, state("create-2"))
        assertEquals(1, outbox.rows.single { it.id == "create-1" }.attempts)
    }

    @Test
    fun `a permanent refusal parks the row without a retry`() = runTest {
        row("r1", CasePayload.Tombstone("c1", "a1"))
        failures["r1"] = SendFailure.Permanent("no")

        assertEquals(OutboxFlush.Result.DONE, flush().run())
        assertEquals(OutboxState.BLOCKED, state("r1"))
        assertEquals("no", outbox.rows.single().lastError)
    }

    @Test
    fun `an account switch mid-run stops the run`() = runTest {
        row("r1", CasePayload.CreateCase("c1", "t", "b"))
        row("r2", CasePayload.CreateCase("c2", "t", "b"))
        onSend = { auth.currentUid = "uid-b" }

        flush().run()

        assertEquals(listOf("r1"), sent)
        assertEquals(OutboxState.PENDING, state("r2"))
    }

    @Test
    fun `a removal goes first and a failing row of its case does not hold it`() = runTest {
        row("message-1", CasePayload.Message("c1", "m1", "text"))
        row("upload-1", CasePayload.Upload("c1", "m1", "a2", "a2.png", "image/png", 10))
        row("tomb-1", CasePayload.Tombstone("c1", "a1"))
        failures["message-1"] = SendFailure.Transient("offline")

        flush().run()

        assertEquals(listOf("tomb-1", "message-1"), sent)
        assertEquals(OutboxState.SENT, state("tomb-1"))
        assertEquals(OutboxState.PENDING, state("upload-1"))
    }

    @Test
    fun `a failing removal does not hold the rest of its case`() = runTest {
        row("tomb-1", CasePayload.Tombstone("c1", "a1"))
        row("message-1", CasePayload.Message("c1", "m1", "text"))
        failures["tomb-1"] = SendFailure.Transient("offline")

        assertEquals(OutboxFlush.Result.RETRY, flush().run())
        assertEquals(listOf("tomb-1", "message-1"), sent)
        assertEquals(OutboxState.PENDING, state("tomb-1"))
    }

    @Test
    fun `a row taken back during the run is not sent from the stale list`() = runTest {
        row("message-1", CasePayload.Message("c1", "m1", "text"))
        row("upload-1", CasePayload.Upload("c1", "m1", "a1", "a1.png", "image/png", 10))
        onSend = { entry -> if (entry.id == "message-1") runBlocking { outbox.discard("upload-1") } }

        flush().run()

        assertEquals(listOf("message-1"), sent)
    }
}
