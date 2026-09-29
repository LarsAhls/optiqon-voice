package se.optiqon.voice.domain.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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
import se.optiqon.voice.data.feedback.CaseOutboxSender
import se.optiqon.voice.data.storage.UserScopedStorage
import se.optiqon.voice.domain.feedback.AttachmentStore
import se.optiqon.voice.domain.feedback.CaseComposer
import se.optiqon.voice.domain.feedback.CaseOutboxPayloads
import se.optiqon.voice.domain.feedback.CasePayload
import se.optiqon.voice.domain.feedback.CaseRemote
import se.optiqon.voice.domain.feedback.ComposeOutcome
import se.optiqon.voice.domain.feedback.FeedbackBuildInfo
import se.optiqon.voice.domain.feedback.RemoteResult
import se.optiqon.voice.domain.feedback.StoreResult
import se.optiqon.voice.testing.FakeAttachmentStore
import se.optiqon.voice.testing.FakeCaseRemote
import se.optiqon.voice.testing.MemoryOutboxDao
import se.optiqon.voice.testing.SwitchableAuth
import java.io.File

/**
 * The owner removes a screenshot while its upload is already inside the sender. Nothing may
 * leave it readable, and the removal must go out without waiting for some later, unrelated
 * piece of work to start the queue again.
 *
 * The real composer, flush and sender run against fakes that behave like the rules: a create
 * of an object whose attachment is tombstoned is refused, and a tombstoned object is not
 * readable. The upload is held at a gate — either in the attachment commit or in the bucket
 * write — and the removal happens while it waits.
 */
class DeleteDuringUploadTest {

    @get:Rule val tmp = TemporaryFolder()

    private val outbox = MemoryOutboxDao()
    private val auth = SwitchableAuth("uid-a")
    private val remote = FakeCaseRemote()
    private val bucket = FakeAttachmentStore()
    private val files by lazy { UserScopedStorage(tmp.root) }
    private var scheduled = 0

    private val composer = CaseComposer(
        outbox = outbox,
        auth = auth,
        approval = { true },
        generation = { 0L },
        scheduler = { scheduled++ },
        build = FeedbackBuildInfo("1", 34, "t"),
        now = { 10_000L },
        newId = { "tomb" }
    )

    /** Where the upload is held: inside the attachment commit, or inside the bucket write. */
    private enum class GatePoint { COMMIT, PUT }

    private val reached = CompletableDeferred<Unit>()
    private val release = CompletableDeferred<StoreResult?>()
    private var gatePoint = GatePoint.PUT
    private var gateUsed = false

    private suspend fun hold(at: GatePoint): StoreResult? {
        if (at != gatePoint || gateUsed) return null
        gateUsed = true
        reached.complete(Unit)
        return release.await()
    }

    private val gatedRemote = object : CaseRemote by remote {
        override suspend fun commitAttachment(
            uid: String, caseId: String, messageId: String?, aid: String, maxBytes: Int, generation: Long
        ): RemoteResult {
            hold(GatePoint.COMMIT)
            return remote.commitAttachment(uid, caseId, messageId, aid, maxBytes, generation)
        }
    }

    /** The bucket as the rules see it: no create over a tombstone, no read of one. */
    private val gatedBucket = object : AttachmentStore {
        override suspend fun put(path: String, bytes: ByteArray, mime: String): StoreResult {
            hold(GatePoint.PUT)?.let { return it }
            if (tombstonedAt(path)) return StoreResult.Denied
            return bucket.put(path, bytes, mime)
        }
        override suspend fun exists(path: String) = if (tombstonedAt(path)) false else bucket.exists(path)
        override suspend fun getBytes(path: String, maxBytes: Long) =
            if (tombstonedAt(path)) null else bucket.getBytes(path, maxBytes)
    }

    private fun tombstonedAt(path: String) = path.split("/").takeLast(2).joinToString("/") in remote.tombstoned

    private val sender by lazy { CaseOutboxSender(gatedRemote, gatedBucket, files, auth, { 0L }, outbox) }
    private fun flush() = OutboxFlush(outbox, auth, { true }, sender, remoteEnabled = true)

    private val path = AttachmentStore.path("uid-a", "c1", "a1")

    /** Readable by anyone the rules let in: an object is there and its attachment is live. */
    private suspend fun readable() = gatedBucket.getBytes(path, Long.MAX_VALUE) != null

    private suspend fun queuedUpload() {
        remote.cases["c1"] = "uid-a" // the case itself has already left the device
        File(files.attachmentsDir("uid-a"), "a1.png").writeBytes(ByteArray(16) { it.toByte() })
        val upload = CasePayload.Upload("c1", null, "a1", "a1.png", "image/png", 16, generation = 0L)
        outbox.insert(
            OutboxEntry(
                id = "up",
                ownerUid = "uid-a",
                kind = CaseOutboxPayloads.kindOf(upload),
                payload = CaseOutboxPayloads.encode(upload),
                createdAtMs = 1,
                state = OutboxState.PENDING
            )
        )
    }

    private fun tombstoneRow() = outbox.rows.single { it.kind == CaseOutboxPayloads.KIND_DELETE }

    private fun assertRemoved() {
        assertEquals(
            "the removal went out in the same run, without another schedule",
            OutboxState.SENT, tombstoneRow().state
        )
        assertTrue("c1/a1" in remote.tombstoned)
        assertTrue(outbox.rows.none { it.kind == CaseOutboxPayloads.KIND_UPLOAD })
    }

    private suspend fun deleteWhileHeld(at: GatePoint, finish: StoreResult? = null): OutboxFlush.Result {
        gatePoint = at
        queuedUpload()
        return kotlinx.coroutines.coroutineScope {
            val run = async { flush().run() }
            reached.await()
            assertEquals(ComposeOutcome.Queued("c1"), composer.deleteScreenshot("c1", "a1"))
            release.complete(finish)
            run.await()
        }
    }

    @Test
    fun `a removal made while the bytes are being written is sent after them, in the same run`() = runTest {
        deleteWhileHeld(GatePoint.PUT)

        assertRemoved()
        assertFalse("the finished upload is not readable", readable())
    }

    @Test
    fun `a removal made while the attachment is being committed is sent after the commit`() = runTest {
        deleteWhileHeld(GatePoint.COMMIT)

        assertRemoved()
        // The tombstone reached a committed attachment, not an empty place the commit then
        // filled in behind it.
        assertTrue("c1/a1" in remote.attachments)
        assertFalse(readable())
        val order = remote.calls.filter { it.startsWith("commit") || it.startsWith("tombstone") }
        assertEquals(listOf("commit:a1", "tombstone:a1"), order)
    }

    @Test
    fun `an upload that fails after its removal is not retried and does not hold the removal`() = runTest {
        val result = deleteWhileHeld(
            GatePoint.PUT,
            finish = StoreResult.Failed(SendFailure.Transient("offline"))
        )

        assertRemoved()
        assertFalse(readable())
        // The failed row is gone: nothing is left to retry, so the run does not ask for one.
        assertEquals(OutboxFlush.Result.DONE, result)
    }

    @Test
    fun `a later run never sends the dropped upload again`() = runTest {
        deleteWhileHeld(GatePoint.PUT)
        val putsBefore = bucket.calls.count { it.startsWith("put") }

        assertEquals(OutboxFlush.Result.DONE, flush().run())

        assertEquals(putsBefore, bucket.calls.count { it.startsWith("put") })
        assertEquals(1, remote.calls.count { it == "tombstone:a1" })
        assertFalse(readable())
    }

    @Test
    fun `a run killed mid-upload leaves only the removal for the next start`() = runTest {
        gatePoint = GatePoint.PUT
        queuedUpload()
        kotlinx.coroutines.coroutineScope {
            val run = async { flush().run() }
            reached.await()
            run.cancel() // the process dies with the bytes half-written
        }
        composer.deleteScreenshot("c1", "a1")

        // Next process: a fresh flush over the same, persisted queue.
        assertEquals(OutboxFlush.Result.DONE, flush().run())

        assertRemoved()
        assertNull(bucket.objects[path])
        assertTrue(bucket.calls.none { it.startsWith("put") })
    }
}
