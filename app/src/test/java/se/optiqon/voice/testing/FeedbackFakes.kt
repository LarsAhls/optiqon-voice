package se.optiqon.voice.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import se.optiqon.voice.data.db.dao.OutboxDao
import se.optiqon.voice.data.db.entity.OutboxEntry
import se.optiqon.voice.data.db.entity.OutboxState
import se.optiqon.voice.domain.access.AuthGateway
import se.optiqon.voice.domain.feedback.AttachmentStore
import se.optiqon.voice.domain.feedback.CaseAttachment
import se.optiqon.voice.domain.feedback.CaseEvent
import se.optiqon.voice.domain.feedback.CaseOutboxPayloads
import se.optiqon.voice.domain.feedback.CaseRemote
import se.optiqon.voice.domain.feedback.FeedbackCase
import se.optiqon.voice.domain.feedback.RemoteResult
import se.optiqon.voice.domain.feedback.RemoteState
import se.optiqon.voice.domain.feedback.ServerApproval
import se.optiqon.voice.domain.feedback.StoreResult
import se.optiqon.voice.domain.feedback.WithdrawalOutcome
import se.optiqon.voice.domain.feedback.WithdrawalStatus
import se.optiqon.voice.domain.sync.SendFailure

/** The outbox table in a list, with Room's ordering and its ABORT-on-duplicate insert. */
class MemoryOutboxDao : OutboxDao {
    private val table = MutableStateFlow<List<OutboxEntry>>(emptyList())
    val rows: List<OutboxEntry> get() = table.value

    override suspend fun insert(entry: OutboxEntry) {
        check(rows.none { it.id == entry.id }) { "duplicate id ${entry.id}" }
        table.value = rows + entry
    }

    /** Like Room: one duplicate id and none of the list is written. */
    override suspend fun insertAll(entries: List<OutboxEntry>) {
        val ids = entries.map { it.id }
        check(ids.toSet().size == ids.size && rows.none { it.id in ids }) { "duplicate id in $ids" }
        table.value = rows + entries
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

    /** Mirrors the SQL: one step, and only a row still pending moves. */
    override suspend fun completeIfPending(id: String, state: OutboxState, attempts: Int, error: String?): Int {
        var changed = 0
        table.update { current ->
            changed = 0
            current.map {
                if (it.id == id && it.state == OutboxState.PENDING) {
                    changed = 1
                    it.copy(state = state, attempts = attempts, lastError = error)
                } else it
            }
        }
        return changed
    }

    override suspend fun discard(id: String) {
        if (id == dieDiscarding) {
            dieDiscarding = null
            throw IllegalStateException("process died discarding $id")
        }
        table.value = rows.filterNot { it.id == id }
    }

    /** The row whose [discard] fails, once: a process that dies part-way through a discard. */
    var dieDiscarding: String? = null

    /** Like Room's `@Transaction`: an exception part-way leaves the table as it was. */
    override suspend fun enqueueAndDiscard(added: List<OutboxEntry>, discarded: List<String>) {
        val before = table.value
        try {
            super.enqueueAndDiscard(added, discarded)
        } catch (e: Throwable) {
            table.value = before
            throw e
        }
    }

    /** Mirrors the SQL: case rows only, pending only, this owner only. */
    override suspend fun holdPendingFor(ownerUid: String): Int {
        val holdable = setOf(CaseOutboxPayloads.KIND_CREATE, CaseOutboxPayloads.KIND_MESSAGE, CaseOutboxPayloads.KIND_UPLOAD)
        val ids = rows.filter { it.ownerUid == ownerUid && it.state == OutboxState.PENDING && it.kind in holdable }
            .map { it.id }.toSet()
        table.value = rows.map { if (it.id in ids) it.copy(state = OutboxState.HELD) else it }
        return ids.size
    }

    override suspend fun releaseHeldFor(ownerUid: String): Int {
        val ids = rows.filter { it.ownerUid == ownerUid && it.state == OutboxState.HELD }.map { it.id }.toSet()
        table.value = rows.map { if (it.id in ids) it.copy(state = OutboxState.PENDING) else it }
        return ids.size
    }

    override suspend fun updateHeldPayload(id: String, payload: String): Int {
        val hit = rows.any { it.id == id && it.state == OutboxState.HELD }
        if (hit) table.value = rows.map { if (it.id == id) it.copy(payload = payload) else it }
        return if (hit) 1 else 0
    }

    override suspend fun heldFor(ownerUid: String) =
        rows.filter { it.ownerUid == ownerUid && it.state == OutboxState.HELD }.sortedBy { it.createdAtMs }

    override suspend fun updatePendingPayload(id: String, payload: String): Int {
        val hit = rows.any { it.id == id && it.state == OutboxState.PENDING }
        if (hit) table.value = rows.map { if (it.id == id) it.copy(payload = payload) else it }
        return if (hit) 1 else 0
    }

    override fun observeKind(kind: String): Flow<List<OutboxEntry>> =
        table.map { all -> all.filter { it.kind == kind }.sortedBy { it.createdAtMs } }
}

/** An account that can be switched under a running piece of code. */
class SwitchableAuth(override var currentUid: String?) : AuthGateway {
    override fun uidChanges(): Flow<String?> = MutableStateFlow(currentUid)
    override val currentEmail: String? = null
    override val currentDisplayName: String? = null
    override var isEmailVerified: Boolean = true
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

    /**
     * The two phases, as the rules keep them. A case or message with no entry here was put in
     * place by a test directly and counts as accepted, as a document with no state does in
     * FirestoreCaseRemote.
     */
    val caseStates = mutableMapOf<String, String>()     // caseId -> submitted | accepted
    val messageStates = mutableMapOf<String, String>()  // caseId/messageId -> submitted | accepted
    val activityRev = mutableMapOf<String, Long>()      // caseId -> revision

    /** The server's approval generation per uid; the rules refuse any other stamp. */
    val generations = mutableMapOf<String, Long>()

    /** Accounts the server no longer holds approved; every write of theirs is refused. */
    val unapproved = mutableSetOf<String>()

    /** How often the server's approval was asked for; [approvalUnknown] makes the asking fail. */
    var approvalReads = 0
    var approvalUnknown = false
    val stamps = mutableMapOf<String, Long>()           // caseId, caseId/messageId or caseId/aid -> stamp

    /** Answers to hand out before behaving normally, one per call, keyed by method. */
    val scripted = mutableMapOf<String, ArrayDeque<RemoteResult>>()

    /** Overrides what [caseState] and [messageState] answer; [stateUnknown] makes them fail. */
    var stateAnswer: RemoteState? = null
    var stateUnknown = false
    var onCommit: () -> Unit = {}

    /**
     * Withdrawal intents as the rules keep them: create-only under `users/{uid}/withdrawals`,
     * keyed "uid/targetId". [Intent.outcome] stays null until [reconcileWithdrawals] plays the
     * server.
     */
    data class Intent(val target: String, val caseId: String, val outcome: String? = null)
    val withdrawals = mutableMapOf<String, Intent>()
    var withdrawalUnknown = false

    /** True when [uid] has an intent for [targetId]: the rules then refuse create and finalize. */
    private fun withdrawn(uid: String, targetId: String) = "$uid/$targetId" in withdrawals

    private fun scriptedFor(method: String): RemoteResult? = scripted[method]?.removeFirstOrNull()
    private fun genOf(uid: String) = generations[uid] ?: 0L
    private fun accepted(state: String?) = state == null || state == "accepted"
    private fun caseOwner(caseId: String) = cases[caseId]

    override suspend fun approvalOf(uid: String): ServerApproval? {
        approvalReads++
        if (approvalUnknown) return null
        return ServerApproval(approved = uid !in unapproved, generation = genOf(uid))
    }

    override suspend fun createCase(
        uid: String, caseId: String, title: String, body: String, generation: Long
    ): RemoteResult {
        calls += "createCase:$caseId"
        scriptedFor("createCase")?.let { return it }
        if (uid in unapproved) return RemoteResult.Denied("not approved")
        if (caseId in cases) return RemoteResult.Denied("exists")
        if (withdrawn(uid, caseId)) return RemoteResult.Denied("withdrawn")
        if (generation != genOf(uid)) return RemoteResult.Denied("stale generation")
        cases[caseId] = uid
        caseStates[caseId] = "submitted"
        activityRev[caseId] = 0L
        stamps[caseId] = generation
        return RemoteResult.Ok
    }

    override suspend fun finalizeCase(caseId: String, generation: Long): RemoteResult {
        calls += "finalizeCase:$caseId"
        scriptedFor("finalizeCase")?.let { return it }
        val owner = caseOwner(caseId) ?: return RemoteResult.Denied("absent")
        if (accepted(caseStates[caseId])) return RemoteResult.Ok
        if (withdrawn(owner, caseId)) return RemoteResult.Denied("withdrawn")
        if (owner in unapproved || generation != genOf(owner)) return RemoteResult.Denied("stale generation")
        caseStates[caseId] = "accepted"
        stamps[caseId] = generation
        return RemoteResult.Ok
    }

    override suspend fun caseState(uid: String, caseId: String): RemoteState? {
        if (stateUnknown) return null
        stateAnswer?.let { return it }
        if (caseOwner(caseId) != uid) return RemoteState.NOT_MINE
        return if (accepted(caseStates[caseId])) RemoteState.ACCEPTED else RemoteState.SUBMITTED
    }

    override suspend fun addMessage(
        uid: String, caseId: String, messageId: String, body: String, generation: Long
    ): RemoteResult {
        calls += "addMessage:$messageId"
        scriptedFor("addMessage")?.let { return it }
        if (uid in unapproved) return RemoteResult.Denied("not approved")
        if (caseOwner(caseId) != uid || !accepted(caseStates[caseId])) return RemoteResult.Denied("not your case")
        if ("$caseId/$messageId" in messages) return RemoteResult.Denied("exists")
        if (withdrawn(uid, messageId)) return RemoteResult.Denied("withdrawn")
        if (generation != genOf(uid)) return RemoteResult.Denied("stale generation")
        messages["$caseId/$messageId"] = uid
        messageStates["$caseId/$messageId"] = "submitted"
        stamps["$caseId/$messageId"] = generation
        return RemoteResult.Ok
    }

    override suspend fun finalizeMessage(caseId: String, messageId: String, generation: Long): RemoteResult {
        calls += "finalizeMessage:$messageId"
        scriptedFor("finalizeMessage")?.let { return it }
        val key = "$caseId/$messageId"
        val actor = messages[key] ?: return RemoteResult.Denied("absent")
        if (accepted(messageStates[key])) return RemoteResult.Ok
        if (withdrawn(actor, messageId)) return RemoteResult.Denied("withdrawn")
        if (actor in unapproved || generation != genOf(actor)) return RemoteResult.Denied("stale generation")
        messageStates[key] = "accepted"
        stamps[key] = generation
        activityRev[caseId] = (activityRev[caseId] ?: 0L) + 1
        return RemoteResult.Ok
    }

    override suspend fun messageState(uid: String, caseId: String, messageId: String): RemoteState? {
        if (stateUnknown) return null
        stateAnswer?.let { return it }
        val key = "$caseId/$messageId"
        if (messages[key] != uid) return RemoteState.NOT_MINE
        return if (accepted(messageStates[key])) RemoteState.ACCEPTED else RemoteState.SUBMITTED
    }

    override suspend fun commitAttachment(
        uid: String, caseId: String, messageId: String?, aid: String, maxBytes: Int, generation: Long
    ): RemoteResult {
        calls += "commit:$aid"
        onCommit()
        scriptedFor("commit")?.let { return it }
        if ("$caseId/$aid" in attachments) return RemoteResult.Ok
        if (uid in unapproved) return RemoteResult.Denied("not approved")
        if (caseOwner(caseId) != uid || !accepted(caseStates[caseId])) return RemoteResult.Denied("not your case")
        if (messageId != null && !accepted(messageStates["$caseId/$messageId"])) {
            return RemoteResult.Denied("message not accepted")
        }
        if (generation != genOf(uid)) return RemoteResult.Denied("stale generation")
        attachments["$caseId/$aid"] = messageId
        stamps["$caseId/$aid"] = generation
        activityRev[caseId] = (activityRev[caseId] ?: 0L) + 1
        return RemoteResult.Ok
    }

    override suspend fun tombstoneAttachment(caseId: String, aid: String): RemoteResult {
        calls += "tombstone:$aid"
        scriptedFor("tombstone")?.let { return it }
        // Never committed: nothing to take down, as in FirestoreCaseRemote.
        if ("$caseId/$aid" !in attachments) return RemoteResult.Ok
        // A tombstone is not activity: the revision stays where it is.
        tombstoned += "$caseId/$aid"
        return RemoteResult.Ok
    }

    /** Records nothing but the intent; approval is not asked (M3=A). Idempotent. */
    override suspend fun requestWithdrawal(uid: String, target: String, targetId: String, caseId: String): RemoteResult {
        calls += "withdraw:$targetId"
        scriptedFor("withdraw")?.let { return it }
        withdrawals.getOrPut("$uid/$targetId") { Intent(target, caseId) }
        return RemoteResult.Ok
    }

    override suspend fun withdrawalStatus(uid: String, targetId: String): WithdrawalStatus? {
        if (withdrawalUnknown) return null
        val intent = withdrawals["$uid/$targetId"] ?: return WithdrawalStatus.Missing
        return intent.outcome?.let { WithdrawalStatus.Reconciled(it) } ?: WithdrawalStatus.Pending
    }

    /**
     * Plays S4 over every open intent, as `server/withdrawal.mjs` does: only a `submitted` case
     * or a `submitted` message of the intent's own author is removed; accepted stays.
     */
    fun reconcileWithdrawals() {
        withdrawals.entries.filter { it.value.outcome == null }.forEach { entry ->
            val uid = entry.key.substringBefore("/")
            val targetId = entry.key.substringAfter("/")
            val intent = entry.value
            val outcome = if (intent.target == "case") {
                val owner = cases[targetId]
                when {
                    owner == null -> WithdrawalOutcome.ABSENT
                    owner != uid -> WithdrawalOutcome.IGNORED_FOREIGN
                    accepted(caseStates[targetId]) -> WithdrawalOutcome.IGNORED_ACCEPTED
                    else -> {
                        cases.remove(targetId); caseStates.remove(targetId); activityRev.remove(targetId)
                        WithdrawalOutcome.WITHDRAWN
                    }
                }
            } else {
                val key = "${intent.caseId}/$targetId"
                val actor = messages[key]
                when {
                    actor == null -> WithdrawalOutcome.ABSENT
                    actor != uid -> WithdrawalOutcome.IGNORED_FOREIGN
                    accepted(messageStates[key]) -> WithdrawalOutcome.IGNORED_ACCEPTED
                    else -> {
                        messages.remove(key); messageStates.remove(key)
                        WithdrawalOutcome.WITHDRAWN
                    }
                }
            }
            entry.setValue(intent.copy(outcome = outcome))
        }
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
