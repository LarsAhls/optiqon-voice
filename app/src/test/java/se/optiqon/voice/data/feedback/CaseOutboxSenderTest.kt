package se.optiqon.voice.data.feedback

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import se.optiqon.voice.data.db.entity.OutboxEntry
import se.optiqon.voice.data.db.entity.OutboxState
import se.optiqon.voice.data.storage.UserScopedStorage
import se.optiqon.voice.domain.feedback.AttachmentStore
import se.optiqon.voice.domain.feedback.CaseOutboxPayloads
import se.optiqon.voice.domain.feedback.CasePayload
import se.optiqon.voice.domain.feedback.ApprovalGeneration
import se.optiqon.voice.domain.feedback.RemoteResult
import se.optiqon.voice.domain.feedback.RemoteState
import se.optiqon.voice.domain.feedback.StoreResult
import se.optiqon.voice.domain.sync.SendFailure
import se.optiqon.voice.testing.FakeAttachmentStore
import se.optiqon.voice.testing.FakeCaseRemote
import se.optiqon.voice.testing.SwitchableAuth
import se.optiqon.voice.testing.TRANSIENT
import java.io.File

/**
 * A retry is the normal case: the worker can die between a write landing and its row being
 * marked sent. Every step here is run twice, or refused, and must end in the same place.
 */
class CaseOutboxSenderTest {

    @get:Rule val tmp = TemporaryFolder()

    private val remote = FakeCaseRemote()
    private val store = FakeAttachmentStore()
    private val auth = SwitchableAuth("uid-a")
    private val files by lazy { UserScopedStorage(tmp.root) }
    private var currentGen: Long? = 0L
    private val generation = ApprovalGeneration { currentGen }
    private val sender by lazy { CaseOutboxSender(remote, store, files, auth, generation) }

    private fun entry(payload: CasePayload, owner: String = "uid-a") = OutboxEntry(
        id = "row",
        ownerUid = owner,
        kind = CaseOutboxPayloads.kindOf(payload),
        payload = CaseOutboxPayloads.encode(payload),
        createdAtMs = 0,
        state = OutboxState.PENDING
    )

    private fun screenshot(name: String = "a1.png", size: Int = 16): File =
        File(files.attachmentsDir("uid-a"), name).apply { writeBytes(ByteArray(size) { it.toByte() }) }

    private val create = CasePayload.CreateCase("c1", "t", "b", 0L)

    @Test
    fun `a case create sent twice is success both times`() = runTest {
        assertNull(sender.send(entry(create)))
        assertNull(sender.send(entry(create)))
        assertEquals("uid-a", remote.cases["c1"])
    }

    @Test
    fun `a refusal is held for the owner when the case is not ours, and unknown when we cannot ask`() = runTest {
        remote.cases["c1"] = "uid-b"
        assertTrue(sender.send(entry(create)) is SendFailure.Held)

        remote.stateUnknown = true
        remote.scripted["createCase"] = ArrayDeque(listOf(RemoteResult.Denied("no")))
        assertTrue(sender.send(entry(create)) is SendFailure.Transient)
    }

    @Test
    fun `a case goes in two phases and is accepted once both are done`() = runTest {
        assertNull(sender.send(entry(create)))
        assertEquals(listOf("createCase:c1", "finalizeCase:c1"), remote.calls)
        assertEquals("accepted", remote.caseStates["c1"])
        assertEquals(0L, remote.activityRev["c1"])
    }

    @Test
    fun `a lost acknowledgement after phase one finishes phase two on the retry`() = runTest {
        // Phase one landed, the answer did not: the worker died before finalizing.
        remote.createCase("uid-a", "c1", "t", "b", 0L)
        remote.calls.clear()

        assertNull(sender.send(entry(create)))
        assertEquals(listOf("createCase:c1", "finalizeCase:c1"), remote.calls)
        assertEquals("accepted", remote.caseStates["c1"])
    }

    @Test
    fun `a retry after both phases landed is success and writes nothing more`() = runTest {
        assertNull(sender.send(entry(create)))
        remote.calls.clear()
        assertNull(sender.send(entry(create)))
        assertEquals("an accepted case is never written again", listOf("createCase:c1"), remote.calls)
    }

    @Test
    fun `a refused phase two with the submitted copy standing is held, not dropped`() = runTest {
        remote.scripted["finalizeCase"] = ArrayDeque(listOf(RemoteResult.Denied("withdrawn")))
        assertTrue(sender.send(entry(create)) is SendFailure.Held)
        assertEquals("submitted", remote.caseStates["c1"])
    }

    @Test
    fun `a refused phase two that cannot be checked is retried`() = runTest {
        remote.scripted["finalizeCase"] = ArrayDeque(listOf(RemoteResult.Denied("no")))
        remote.stateUnknown = true
        assertTrue(sender.send(entry(create)) is SendFailure.Transient)
    }

    @Test
    fun `a refused phase two that another attempt already finished is success`() = runTest {
        remote.scripted["finalizeCase"] = ArrayDeque(listOf(RemoteResult.Denied("raced")))
        remote.stateAnswer = RemoteState.ACCEPTED
        assertNull(sender.send(entry(create)))
    }

    @Test
    fun `a row stamped with an earlier approval is held without a single remote call`() = runTest {
        currentGen = 1L
        assertTrue(sender.send(entry(create)) is SendFailure.Held)
        assertTrue(sender.send(entry(CasePayload.Message("c1", "m1", "svar", 0L))) is SendFailure.Held)
        assertTrue(sender.send(entry(CasePayload.Upload("c1", null, "a1", "a1.png", "image/png", 16, 0L))) is SendFailure.Held)
        assertTrue(remote.calls.isEmpty())
    }

    @Test
    fun `a row written before rows carried a generation is held too`() = runTest {
        assertTrue(sender.send(entry(CasePayload.CreateCase("c1", "t", "b"))) is SendFailure.Held)
        assertTrue(remote.calls.isEmpty())
    }

    @Test
    fun `with no verdict on the device the row waits and nothing is sent`() = runTest {
        currentGen = null
        assertTrue(sender.send(entry(create)) is SendFailure.Transient)
        assertTrue(remote.calls.isEmpty())
    }

    @Test
    fun `a stale stamp the device did not know about is refused by the server and held`() = runTest {
        // The server moved to generation 1; this device has not heard yet.
        remote.generations["uid-a"] = 1L
        assertTrue("never lost, never final on its own", sender.send(entry(create)) is SendFailure.Held)
        assertTrue("the stale case never came into being", "c1" !in remote.cases)

        // Phase one landed under 0, then the server moved before phase two.
        remote.generations["uid-a"] = 0L
        remote.createCase("uid-a", "c2", "t", "b", 0L)
        remote.generations["uid-a"] = 1L
        val second = CasePayload.CreateCase("c2", "t", "b", 0L)
        assertTrue(sender.send(entry(second)) is SendFailure.Held)
        assertEquals("submitted", remote.caseStates["c2"])
    }

    @Test
    fun `a message counts once on the case however often it is sent`() = runTest {
        sender.send(entry(create))
        val msg = CasePayload.Message("c1", "m1", "svar", 0L)
        assertNull(sender.send(entry(msg)))
        assertNull(sender.send(entry(msg)))
        // Phase one landed for a second message, the answer did not.
        remote.addMessage("uid-a", "c1", "m2", "igen", 0L)
        assertNull(sender.send(entry(CasePayload.Message("c1", "m2", "igen", 0L))))
        assertNull(sender.send(entry(CasePayload.Message("c1", "m2", "igen", 0L))))

        assertEquals(2L, remote.activityRev["c1"])
        assertEquals("accepted", remote.messageStates["c1/m1"])
        assertEquals("accepted", remote.messageStates["c1/m2"])
    }

    @Test
    fun `a screenshot is one step of activity and a take-down is none`() = runTest {
        sender.send(entry(create))
        screenshot()
        sender.send(entry(CasePayload.Upload("c1", null, "a1", "a1.png", "image/png", 16, 0L)))
        assertEquals(1L, remote.activityRev["c1"])

        sender.send(entry(CasePayload.Tombstone("c1", "a1")))
        assertEquals(1L, remote.activityRev["c1"])
        assertTrue("c1/a1" in remote.tombstoned)
    }

    @Test
    fun `a transient failure stays transient`() = runTest {
        remote.scripted["createCase"] = ArrayDeque(listOf(TRANSIENT))
        assertEquals(TRANSIENT.failure, sender.send(entry(create)))
    }

    @Test
    fun `a message sent twice is success both times`() = runTest {
        sender.send(entry(create))
        val msg = CasePayload.Message("c1", "m1", "svar", 0L)
        assertNull(sender.send(entry(msg)))
        assertNull(sender.send(entry(msg)))
    }

    @Test
    fun `an upload commits, puts, and removes the local copy`() = runTest {
        sender.send(entry(create))
        val file = screenshot()
        val upload = CasePayload.Upload("c1", null, "a1", file.name, "image/png", 16, 0L)

        assertNull(sender.send(entry(upload)))

        val path = AttachmentStore.path("uid-a", "c1", "a1")
        assertEquals(listOf("commit:a1"), remote.calls.filter { it.startsWith("commit") })
        assertEquals(16, store.objects[path]!!.size)
        assertFalse(file.exists())
    }

    @Test
    fun `an upload retried after the put landed but before the row was marked sent is success`() = runTest {
        sender.send(entry(create))
        val file = screenshot()
        val upload = CasePayload.Upload("c1", null, "a1", file.name, "image/png", 16, 0L)
        sender.send(entry(upload))

        // The copy is gone; the object is there.
        assertNull(sender.send(entry(upload)))
    }

    @Test
    fun `an upload retried after the put landed but before the copy was removed is success`() = runTest {
        sender.send(entry(create))
        screenshot()
        val path = AttachmentStore.path("uid-a", "c1", "a1")
        store.objects[path] = ByteArray(16)
        remote.attachments["c1/a1"] = null
        val upload = CasePayload.Upload("c1", null, "a1", "a1.png", "image/png", 16, 0L)

        assertNull(sender.send(entry(upload)))
    }

    @Test
    fun `a refused put on a taken-down screenshot parks the row`() = runTest {
        sender.send(entry(create))
        val file = screenshot()
        store.scriptedPut += StoreResult.Denied
        val upload = CasePayload.Upload("c1", null, "a1", file.name, "image/png", 16, 0L)

        assertTrue(sender.send(entry(upload)) is SendFailure.Permanent)
        assertTrue("the local copy is kept when the bytes did not arrive", file.exists())
    }

    @Test
    fun `a refused put that cannot be checked is retried`() = runTest {
        sender.send(entry(create))
        screenshot()
        store.scriptedPut += StoreResult.Denied
        store.existsUnknown = true
        val upload = CasePayload.Upload("c1", null, "a1", "a1.png", "image/png", 16, 0L)

        assertTrue(sender.send(entry(upload)) is SendFailure.Transient)
    }

    @Test
    fun `a missing local copy is success only if the object is there`() = runTest {
        sender.send(entry(create))
        val upload = CasePayload.Upload("c1", null, "gone", "gone.png", "image/png", 16, 0L)

        assertTrue(sender.send(entry(upload)) is SendFailure.Permanent)
        store.existsUnknown = true
        assertTrue(sender.send(entry(upload)) is SendFailure.Transient)
        store.existsUnknown = false
        store.existsAnswer = true
        assertNull(sender.send(entry(upload)))
        assertTrue(remote.calls.none { it.startsWith("commit") })
    }

    @Test
    fun `a screenshot that changed size after queueing is not sent`() = runTest {
        sender.send(entry(create))
        screenshot(size = 20)
        val upload = CasePayload.Upload("c1", null, "a1", "a1.png", "image/png", 16, 0L)

        assertTrue(sender.send(entry(upload)) is SendFailure.Permanent)
        assertTrue(store.calls.isEmpty())
    }

    @Test
    fun `a file name that climbs out of the account's directory is refused`() = runTest {
        sender.send(entry(create))
        File(files.attachmentsDir("uid-b"), "x.png").writeBytes(ByteArray(16))
        val upload = CasePayload.Upload("c1", null, "a1", "../../uid-b/attachments/x.png", "image/png", 16, 0L)

        assertTrue(sender.send(entry(upload)) is SendFailure.Permanent)
        assertTrue(store.calls.isEmpty())
        assertTrue(remote.calls.none { it.startsWith("commit") })
    }

    @Test
    fun `an account switch between commit and put stops before the bytes leave`() = runTest {
        sender.send(entry(create))
        screenshot()
        remote.onCommit = { auth.currentUid = "uid-b" }
        val upload = CasePayload.Upload("c1", null, "a1", "a1.png", "image/png", 16, 0L)

        assertTrue(sender.send(entry(upload)) is SendFailure.Transient)
        assertTrue(store.calls.isEmpty())
    }

    @Test
    fun `a row is never sent under another account`() = runTest {
        auth.currentUid = "uid-b"
        assertTrue(sender.send(entry(create)) is SendFailure.Transient)
        assertTrue(remote.calls.isEmpty())
    }

    @Test
    fun `a tombstone is idempotent and a refused one is final`() = runTest {
        remote.attachments["c1/a1"] = null
        val tomb = CasePayload.Tombstone("c1", "a1")
        assertNull(sender.send(entry(tomb)))
        remote.scripted["tombstone"] = ArrayDeque(listOf(RemoteResult.Denied("not your case")))
        assertTrue(sender.send(entry(tomb)) is SendFailure.Permanent)
    }

    @Test
    fun `an unreadable row is parked`() = runTest {
        val bad = entry(create).copy(payload = "{")
        assertTrue(sender.send(bad) is SendFailure.Permanent)
    }
}
