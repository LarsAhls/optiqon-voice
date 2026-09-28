package se.optiqon.voice.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import se.optiqon.voice.data.db.dao.OutboxDao
import se.optiqon.voice.data.db.entity.OutboxEntry
import se.optiqon.voice.data.db.entity.OutboxState
import se.optiqon.voice.domain.access.AuthGateway
import se.optiqon.voice.domain.feedback.AttachmentStore
import se.optiqon.voice.domain.feedback.CaseAttachment
import se.optiqon.voice.domain.feedback.CaseEvent
import se.optiqon.voice.domain.feedback.CaseRemote
import se.optiqon.voice.domain.feedback.FeedbackCase
import se.optiqon.voice.domain.feedback.RemoteResult
import se.optiqon.voice.domain.feedback.StoreResult
import se.optiqon.voice.domain.sync.SendFailure

/** The outbox table in a list, with Room's ordering and its ABORT-on-duplicate insert. */
class MemoryOutboxDao : OutboxDao {
    private val table = MutableStateFlow<List<OutboxEntry>>(emptyList())
    val rows: List<OutboxEntry> get() = table.value

    override suspend fun insert(entry: OutboxEntry) {
        check(rows.none { it.id == entry.id }) { "duplicate id ${entry.id}" }
        table.value = rows + entry
    }

    override suspend fun pendingFor(ownerUid: String) =
        rows.filter { it.ownerUid == ownerUid && it.state == OutboxState.PENDING }.sortedBy { it.createdAtMs }

    override fun observeUnsent(): Flow<List<OutboxEntry>> =
        table.map { all -> all.filter { it.state != OutboxState.SENT }.sortedBy { it.createdAtMs } }

    override suspend fun all() = rows
    override suspend fun byId(id: String) = rows.firstOrNull { it.id == id }

    override suspend fun updateState(id: String, state: OutboxState, attempts: Int, error: String?) {
        table.value = rows.map {
            if (it.id == id) it.copy(state = state, attempts = attempts, lastError = error) else it
        }
    }

    override suspend fun discard(id: String) {
        table.value = rows.filterNot { it.id == id }
    }
}

/** An account that can be switched under a running piece of code. */
class SwitchableAuth(override var currentUid: String?) : AuthGateway {
    override fun uidChanges(): Flow<String?> = MutableStateFlow(currentUid)
    override val currentEmail: String? = null
    override val currentDisplayName: String? = null
    override val isEmailVerified: Boolean = true
    override suspend fun reload() = Unit
    override suspend fun signOut() { currentUid = null }
}

/**
 * A server that behaves like the rules: a document written twice is refused the second time,
 * and the question "is it already there and mine?" is answered from what was written.
 */
class FakeCaseRemote : CaseRemote {
    val cases = mutableMapOf<String, String>()          // caseId -> ownerUid
    val messages = mutableMapOf<String, String>()       // caseId/messageId -> actorUid
    val attachments = mutableMapOf<String, String?>()   // caseId/aid -> messageId
    val tombstoned = mutableSetOf<String>()             // caseId/aid
    val calls = mutableListOf<String>()

    /** Answers to hand out before behaving normally, one per call, keyed by method. */
    val scripted = mutableMapOf<String, ArrayDeque<RemoteResult>>()
    var ownershipAnswer: Boolean? = null
    var onCommit: () -> Unit = {}

    private fun scriptedFor(method: String): RemoteResult? = scripted[method]?.removeFirstOrNull()

    override suspend fun createCase(uid: String, caseId: String, title: String, body: String): RemoteResult {
        calls += "createCase:$caseId"
        scriptedFor("createCase")?.let { return it }
        if (caseId in cases) return RemoteResult.Denied("exists")
        cases[caseId] = uid
        return RemoteResult.Ok
    }

    override suspend fun caseIsMine(uid: String, caseId: String): Boolean? =
        ownershipAnswer ?: (cases[caseId] == uid)

    override suspend fun addMessage(uid: String, caseId: String, messageId: String, body: String): RemoteResult {
        calls += "addMessage:$messageId"
        scriptedFor("addMessage")?.let { return it }
        if (cases[caseId] != uid) return RemoteResult.Denied("not your case")
        if ("$caseId/$messageId" in messages) return RemoteResult.Denied("exists")
        messages["$caseId/$messageId"] = uid
        return RemoteResult.Ok
    }

    override suspend fun messageIsMine(uid: String, caseId: String, messageId: String): Boolean? =
        ownershipAnswer ?: (messages["$caseId/$messageId"] == uid)

    override suspend fun commitAttachment(
        uid: String, caseId: String, messageId: String?, aid: String, maxBytes: Int
    ): RemoteResult {
        calls += "commit:$aid"
        onCommit()
        scriptedFor("commit")?.let { return it }
        if ("$caseId/$aid" in attachments) return RemoteResult.Ok
        if (cases[caseId] != uid) return RemoteResult.Denied("not your case")
        attachments["$caseId/$aid"] = messageId
        return RemoteResult.Ok
    }

    override suspend fun tombstoneAttachment(caseId: String, aid: String): RemoteResult {
        calls += "tombstone:$aid"
        scriptedFor("tombstone")?.let { return it }
        if ("$caseId/$aid" !in attachments) return RemoteResult.Denied("no such screenshot")
        tombstoned += "$caseId/$aid"
        return RemoteResult.Ok
    }

    override suspend fun listCases(uid: String): List<FeedbackCase> =
        cases.filterValues { it == uid }.keys.map {
            FeedbackCase(it, "t", "b", "Mottaget", null, null, 0, false)
        }

    override suspend fun events(caseId: String): List<CaseEvent> = emptyList()
    override suspend fun attachments(caseId: String): List<CaseAttachment> = emptyList()
}

/** A bucket where a second write to a path is refused, as the Storage rules refuse it. */
class FakeAttachmentStore : AttachmentStore {
    val objects = mutableMapOf<String, ByteArray>()
    val calls = mutableListOf<String>()
    val scriptedPut = ArrayDeque<StoreResult>()
    var existsAnswer: Boolean? = null
    var existsUnknown = false

    override suspend fun put(path: String, bytes: ByteArray, mime: String): StoreResult {
        calls += "put:$path"
        scriptedPut.removeFirstOrNull()?.let { return it }
        if (path in objects) return StoreResult.Denied
        objects[path] = bytes
        return StoreResult.Ok
    }

    override suspend fun exists(path: String): Boolean? {
        calls += "exists:$path"
        if (existsUnknown) return null
        return existsAnswer ?: (path in objects)
    }

    override suspend fun getBytes(path: String, maxBytes: Long): ByteArray? = objects[path]
}

val TRANSIENT = RemoteResult.Failed(SendFailure.Transient("offline"))
