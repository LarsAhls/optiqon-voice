package se.optiqon.voice.domain.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import se.optiqon.voice.data.db.entity.OutboxState
import se.optiqon.voice.data.feedback.CaseOutboxSender
import se.optiqon.voice.data.storage.UserScopedStorage
import se.optiqon.voice.domain.feedback.CaseComposer
import se.optiqon.voice.domain.feedback.CaseOutboxPayloads
import se.optiqon.voice.domain.feedback.CasePayload
import se.optiqon.voice.domain.feedback.CaseRemote
import se.optiqon.voice.domain.feedback.ComposeOutcome
import se.optiqon.voice.domain.feedback.FeedbackBuildInfo
import se.optiqon.voice.domain.feedback.HeldOutcome
import se.optiqon.voice.domain.feedback.RemoteResult
import se.optiqon.voice.domain.feedback.WithdrawalOutcome
import se.optiqon.voice.testing.FakeAttachmentStore
import se.optiqon.voice.testing.FakeCaseRemote
import se.optiqon.voice.testing.MemoryOutboxDao
import se.optiqon.voice.testing.SwitchableAuth

/**
 * The owner chooses Kasta while the first send of that case or message is already inside the
 * sender -- `attempts` still 0, because it is counted only when a send returns. Whatever the
 * send then does, the post it may leave on the server must end with a withdrawal behind it:
 * `withdrawn` if it was `submitted`, `ignored_accepted` ("Redan mottaget") if it was accepted,
 * `absent` if it never got there. Never an accepted post with nothing to reconcile it.
 *
 * The real composer, flush and sender run against a fake that behaves like the rules: once the
 * intent exists, a create or finalize under that id is refused. The send is held at a gate in
 * its create or its finalize, the discard happens there, and the gate then opens one of three
 * ways: the write lands, the network drops it, or another run delivers the intent first.
 */
class DiscardDuringFirstSendTest {

    @get:Rule val tmp = TemporaryFolder()

    private val outbox = MemoryOutboxDao()
    private val auth = SwitchableAuth("uid-a")
    private val remote = FakeCaseRemote()
    private val files by lazy { UserScopedStorage(tmp.root) }
    private var ids = 0

    private val composer = CaseComposer(
        outbox = outbox,
        auth = auth,
        approval = { true },
        generation = { 0L },
        scheduler = { },
        build = FeedbackBuildInfo("1", 34, "t"),
        now = { 10_000L + ids },
        newId = { "id-${ids++}" }
    )

    private enum class Gate { CREATE, FINALIZE }

    /** How the held write ends once the gate opens. */
    private enum class Then { LANDS, DROPPED, INTENT_FIRST }

    private val reached = CompletableDeferred<Unit>()
    private val release = CompletableDeferred<Unit>()
    private var gate = Gate.CREATE
    private var then = Then.LANDS
    private var gateUsed = false

    /** Holds the first write at [gate]; true when the write is then lost on the way. */
    private suspend fun hold(at: Gate): Boolean {
        if (at != gate || gateUsed) return false
        gateUsed = true
        reached.complete(Unit)
        release.await()
        return then == Then.DROPPED
    }

    private val dropped = RemoteResult.Failed(SendFailure.Transient("offline"))

    private val gatedRemote = object : CaseRemote by remote {
        override suspend fun createCase(uid: String, caseId: String, title: String, body: String, generation: Long) =
            if (hold(Gate.CREATE)) dropped else remote.createCase(uid, caseId, title, body, generation)

        override suspend fun finalizeCase(caseId: String, generation: Long) =
            if (hold(Gate.FINALIZE)) dropped else remote.finalizeCase(caseId, generation)

        override suspend fun addMessage(uid: String, caseId: String, messageId: String, body: String, generation: Long) =
            if (hold(Gate.CREATE)) dropped else remote.addMessage(uid, caseId, messageId, body, generation)

        override suspend fun finalizeMessage(caseId: String, messageId: String, generation: Long) =
            if (hold(Gate.FINALIZE)) dropped else remote.finalizeMessage(caseId, messageId, generation)
    }

    private val sender by lazy { CaseOutboxSender(gatedRemote, FakeAttachmentStore(), files, auth, { 0L }, outbox) }
    private fun flush() = OutboxFlush(outbox, auth, { true }, sender, remoteEnabled = true)

    /**
     * Runs a flush into the gate, [discard]s there, opens the gate as [then] says, lets the
     * server reconcile, and runs the queue to its end.
     */
    private suspend fun raceDiscard(at: Gate, how: Then, discard: suspend () -> Unit) {
        gate = at
        then = how
        coroutineScope {
            val run = async { flush().run() }
            reached.await()
            assertEquals("the send is in flight untried", 0, outbox.rows.minOf { it.attempts })
            discard()
            // Another run -- a second worker, or the next start -- delivers the intent while
            // this one is still inside the sender.
            if (how == Then.INTENT_FIRST) flush().run()
            release.complete(Unit)
            run.await()
        }
        remote.reconcileWithdrawals()
        assertEquals(OutboxFlush.Result.DONE, flush().run())
    }

    private fun intents() = outbox.rows.filter { it.kind == CaseOutboxPayloads.KIND_WITHDRAW }
        .map { it to CaseOutboxPayloads.decode(it.kind, it.payload) as CasePayload.Withdrawal }

    /**
     * The invariant: the target has a delivered, reconciled intent whose verdict matches what
     * is on the server, and no content row of it is left to send.
     */
    private fun assertReconciled(targetId: String, onServer: () -> String?, expected: String) {
        val (row, intent) = intents().single { it.second.targetId == targetId }
        assertEquals(OutboxState.SENT, row.state)
        assertEquals(expected, intent.outcome)
        assertEquals(expected, remote.withdrawals["uid-a/$targetId"]?.outcome)
        val state = onServer()
        when (expected) {
            WithdrawalOutcome.IGNORED_ACCEPTED -> assertEquals("accepted stays", "accepted", state)
            else -> assertEquals("nothing of it is left on the server", null, state)
        }
        assertTrue(
            "no content row of it is left",
            outbox.rows.filter { it.kind != CaseOutboxPayloads.KIND_WITHDRAW }
                .none { CaseOutboxPayloads.decode(it.kind, it.payload).let { p -> p is CasePayload.CreateCase && p.caseId == targetId || p is CasePayload.Message && p.messageId == targetId } }
        )
    }

    // A: an opening in its first send.

    private suspend fun queuedCase(): String =
        (composer.createCase("hej", emptyList()) as ComposeOutcome.Queued).caseId

    private fun caseOnServer(caseId: String): () -> String? = {
        if (caseId in remote.cases) remote.caseStates[caseId] ?: "accepted" else null
    }

    private suspend fun caseRace(at: Gate, how: Then, expected: String) {
        val caseId = queuedCase()
        raceDiscard(at, how) { assertEquals(emptyList<String>(), composer.discardQueuedCase(caseId)) }
        assertReconciled(caseId, caseOnServer(caseId), expected)
    }

    @Test
    fun `case - discard during the create that lands leaves it received, not unaccounted for`() = runTest {
        caseRace(Gate.CREATE, Then.LANDS, WithdrawalOutcome.IGNORED_ACCEPTED)
    }

    @Test
    fun `case - discard during a create the network drops ends absent`() = runTest {
        caseRace(Gate.CREATE, Then.DROPPED, WithdrawalOutcome.ABSENT)
    }

    @Test
    fun `case - an intent delivered before the held create lands refuses the create`() = runTest {
        caseRace(Gate.CREATE, Then.INTENT_FIRST, WithdrawalOutcome.ABSENT)
    }

    @Test
    fun `case - discard during the finalize that lands leaves it received`() = runTest {
        caseRace(Gate.FINALIZE, Then.LANDS, WithdrawalOutcome.IGNORED_ACCEPTED)
    }

    @Test
    fun `case - discard during a finalize the network drops withdraws the submitted leftover`() = runTest {
        caseRace(Gate.FINALIZE, Then.DROPPED, WithdrawalOutcome.WITHDRAWN)
    }

    @Test
    fun `case - an intent delivered before the held finalize refuses it, and the leftover is withdrawn`() = runTest {
        caseRace(Gate.FINALIZE, Then.INTENT_FIRST, WithdrawalOutcome.WITHDRAWN)
    }

    // B: a message in its first send, discarded after its hold -- the explicit Kasta of a
    // message.

    private suspend fun queuedMessage(): Pair<String, String> {
        remote.cases["c1"] = "uid-a" // accepted, on the server already
        composer.addMessage("c1", "mer", emptyList())
        val message = CaseOutboxPayloads.decode(outbox.rows.single().kind, outbox.rows.single().payload) as CasePayload.Message
        return "c1" to message.messageId
    }

    private fun messageOnServer(messageId: String): () -> String? = {
        val key = "c1/$messageId"
        if (key in remote.messages) remote.messageStates[key] ?: "accepted" else null
    }

    private suspend fun messageRace(at: Gate, how: Then, expected: String) {
        val (_, messageId) = queuedMessage()
        raceDiscard(at, how) {
            // The approval is withdrawn while the message is in flight, and the owner then
            // throws the held message away.
            assertEquals(1, outbox.holdPendingFor("uid-a"))
            assertTrue(composer.discardHeld() is HeldOutcome.Done)
        }
        assertReconciled(messageId, messageOnServer(messageId), expected)
        assertEquals("the case itself is never touched", "uid-a", remote.cases["c1"])
    }

    @Test
    fun `message - discard during the create that lands leaves it received`() = runTest {
        messageRace(Gate.CREATE, Then.LANDS, WithdrawalOutcome.IGNORED_ACCEPTED)
    }

    @Test
    fun `message - discard during a create the network drops ends absent`() = runTest {
        messageRace(Gate.CREATE, Then.DROPPED, WithdrawalOutcome.ABSENT)
    }

    @Test
    fun `message - an intent delivered before the held create lands refuses the create`() = runTest {
        messageRace(Gate.CREATE, Then.INTENT_FIRST, WithdrawalOutcome.ABSENT)
    }

    @Test
    fun `message - discard during the finalize that lands leaves it received`() = runTest {
        messageRace(Gate.FINALIZE, Then.LANDS, WithdrawalOutcome.IGNORED_ACCEPTED)
    }

    @Test
    fun `message - discard during a finalize the network drops withdraws the submitted leftover`() = runTest {
        messageRace(Gate.FINALIZE, Then.DROPPED, WithdrawalOutcome.WITHDRAWN)
    }

    @Test
    fun `message - an intent delivered before the held finalize refuses it, and the leftover is withdrawn`() = runTest {
        messageRace(Gate.FINALIZE, Then.INTENT_FIRST, WithdrawalOutcome.WITHDRAWN)
    }

    @Test
    fun `message - a message behind a refused opening is withdrawn with its case`() = runTest {
        // The opening's accept landed but its answer did not, and a later refusal parked it: the
        // case is accepted on the server while its row sits BLOCKED, so a message of it can be
        // in its first send when the owner takes the case back.
        val caseId = queuedCase()
        remote.cases[caseId] = "uid-a"
        outbox.updateState(outbox.rows.single().id, OutboxState.BLOCKED, 1, "refused")
        composer.addMessage(caseId, "mer", emptyList())
        val messageId = (intentsFree().last() as CasePayload.Message).messageId

        raceDiscard(Gate.CREATE, Then.LANDS) { assertEquals(emptyList<String>(), composer.discardQueuedCase(caseId)) }

        assertReconciled(messageId, { if ("$caseId/$messageId" in remote.messages) remote.messageStates["$caseId/$messageId"] else null }, WithdrawalOutcome.IGNORED_ACCEPTED)
        assertReconciled(caseId, caseOnServer(caseId), WithdrawalOutcome.IGNORED_ACCEPTED)
    }

    private fun intentsFree() = outbox.rows.filter { it.kind != CaseOutboxPayloads.KIND_WITHDRAW }
        .map { CaseOutboxPayloads.decode(it.kind, it.payload) }

    // D: never tried and never in flight.

    @Test
    fun `a case discarded before any send is absent, and none of its content is ever sent`() = runTest {
        val caseId = queuedCase()
        composer.discardQueuedCase(caseId)

        flush().run()
        remote.reconcileWithdrawals()
        assertEquals(OutboxFlush.Result.DONE, flush().run())

        assertReconciled(caseId, caseOnServer(caseId), WithdrawalOutcome.ABSENT)
        assertFalse(remote.calls.any { it.startsWith("createCase") || it.startsWith("finalizeCase") })
        assertEquals(listOf("withdraw:$caseId"), remote.calls)
    }

    // E: duplicates and restarts.

    @Test
    fun `a second discard and a restart deliver the one intent once`() = runTest {
        val caseId = queuedCase()
        raceDiscard(Gate.FINALIZE, Then.DROPPED) { composer.discardQueuedCase(caseId) }
        val after = outbox.rows.toList()

        assertEquals(null, composer.discardQueuedCase(caseId))
        assertEquals(OutboxFlush.Result.DONE, flush().run())

        assertEquals(after, outbox.rows)
        assertEquals(1, remote.calls.count { it == "withdraw:$caseId" })
        assertEquals(1, remote.withdrawals.size)
    }
}
