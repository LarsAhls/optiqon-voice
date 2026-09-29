package se.optiqon.voice.ui.settings

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.optiqon.voice.data.db.dao.OutboxDao
import se.optiqon.voice.data.db.entity.OutboxEntry
import se.optiqon.voice.data.feedback.PrepareResult
import se.optiqon.voice.data.feedback.ScreenshotPreprocessor
import se.optiqon.voice.data.storage.UserScopedStorage
import se.optiqon.voice.domain.access.AuthGateway
import se.optiqon.voice.domain.feedback.ApprovalCheck
import se.optiqon.voice.domain.feedback.AttachmentStore
import se.optiqon.voice.domain.feedback.CaseAttachment
import se.optiqon.voice.domain.feedback.CaseComposer
import se.optiqon.voice.domain.feedback.CaseOutboxPayloads
import se.optiqon.voice.domain.feedback.CaseRemote
import se.optiqon.voice.domain.feedback.ComposeOutcome
import se.optiqon.voice.domain.feedback.FeedbackConfig
import se.optiqon.voice.domain.feedback.FeedbackLimits
import se.optiqon.voice.domain.feedback.HeldOutcome
import se.optiqon.voice.domain.feedback.LocalFeedback
import se.optiqon.voice.domain.feedback.ShotState
import se.optiqon.voice.domain.feedback.showsContent
import java.io.File
import javax.inject.Inject

/**
 * The feedback screen's three pages and everything they show.
 *
 * Remote reads happen only in a build with the channel switched on and for an approved
 * account; otherwise the screen is the device's own queue and says so. Every write goes
 * through [CaseComposer] onto the outbox — the screen never talks to Firestore or Storage for a
 * write, and never asks either for a link to a screenshot.
 */
@HiltViewModel
class FeedbackViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val composer: CaseComposer,
    private val remote: CaseRemote,
    private val store: AttachmentStore,
    private val preprocessor: ScreenshotPreprocessor,
    private val files: UserScopedStorage,
    private val outbox: OutboxDao,
    private val auth: AuthGateway,
    private val approval: ApprovalCheck,
    private val config: FeedbackConfig
) : ViewModel() {

    private val _state = MutableStateFlow(FeedbackUiState(remoteEnabled = config.remoteEnabled))
    val state: StateFlow<FeedbackUiState> = _state.asStateFlow()

    private var rows: List<OutboxEntry> = emptyList()
    private var receivedIds: List<String> = emptyList()

    /** The open case and the attachments the server last reported for it. */
    private var shotSource: Pair<String, List<CaseAttachment>>? = null
    private val thumbnails = mutableMapOf<String, ImageBitmap?>()
    /** Screenshots the user removed on this visit; their disappearance is announced. */
    private val removals = mutableSetOf<String>()
    private val startedAtMs = System.currentTimeMillis()

    init {
        viewModelScope.launch { sweepOrphans() }
        viewModelScope.launch {
            val approved = approval.isApproved()
            _state.update { it.copy(approved = approved, signedIn = auth.currentUid != null) }
            refreshRemote()
        }
        viewModelScope.launch {
            outbox.observeUnsent().collect { unsent ->
                rows = unsent
                val uid = auth.currentUid
                _state.update {
                    if (uid == null) it.copy(queued = emptyList(), legacy = emptyList(), held = 0)
                    else it.copy(
                        queued = LocalFeedback.queuedCases(unsent, uid),
                        legacy = LocalFeedback.legacy(unsent, uid),
                        held = LocalFeedback.heldCount(unsent, uid)
                    )
                }
                restate()
            }
        }
        viewModelScope.launch {
            outbox.observeKind(CaseOutboxPayloads.KIND_WITHDRAW).collect { withdrawals ->
                receivedIds = auth.currentUid?.let { LocalFeedback.alreadyReceived(withdrawals, it) }.orEmpty()
                _state.update { it.copy(alreadyReceived = receivedIds.size) }
            }
        }
    }

    fun refreshRemote() {
        val uid = auth.currentUid ?: return
        if (!config.remoteEnabled) return
        viewModelScope.launch {
            if (!approval.isApproved()) return@launch
            val cases = runCatching { remote.listCases(uid) }
            _state.update { it.copy(remoteCases = cases.getOrDefault(it.remoteCases), remoteError = cases.isFailure) }
        }
    }

    fun newCase() = _state.update { it.copy(page = FeedbackPage.Compose, notice = null) }

    fun back() {
        val page = _state.value.page
        if (page is FeedbackPage.Compose || page is FeedbackPage.Detail) {
            clearDraft()
            shotSource = null
            _state.update { it.copy(page = FeedbackPage.List, detail = null, notice = null) }
            refreshRemote()
        }
    }

    /** True when the screen used the back press itself; false hands it to Settings. */
    fun handlesBack(): Boolean = _state.value.page != FeedbackPage.List

    fun addImages(uris: List<Uri>) {
        val uid = auth.currentUid ?: return
        val room = maxOf(0, FeedbackLimits.MAX_IMAGES_PER_MESSAGE - _state.value.draft.size)
        viewModelScope.launch {
            var notice: FeedbackNotice? = if (uris.size > room) FeedbackNotice.TooManyImages else null
            val added = withContext(Dispatchers.IO) {
                uris.take(room).mapNotNull { uri ->
                    val resolver = context.contentResolver
                    when (val result = preprocessor.prepare(uid, resolver.getType(uri)) { resolver.openInputStream(uri) }) {
                        is PrepareResult.Ready -> DraftImage(result.image, thumbnail(File(files.attachmentsDir(uid), result.image.file)))
                        PrepareResult.Unsupported -> { notice = FeedbackNotice.ImageUnsupported; null }
                        PrepareResult.Unreadable -> { notice = FeedbackNotice.ImageUnreadable; null }
                    }
                }
            }
            _state.update { it.copy(draft = it.draft + added, notice = notice) }
        }
    }

    fun removeImage(file: String) {
        val uid = auth.currentUid ?: return
        preprocessor.discard(uid, file)
        _state.update { s -> s.copy(draft = s.draft.filterNot { it.image.file == file }) }
    }

    fun submit(text: String) {
        val detail = _state.value.detail
        val images = _state.value.draft.map { it.image }
        _state.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            val outcome = if (detail?.case != null) composer.addMessage(detail.case.id, text, images)
            else composer.createCase(text, images)
            val notice = noticeFor(outcome)
            if (outcome is ComposeOutcome.Queued) {
                // The copies now belong to the queued rows; they are removed once uploaded.
                _state.update { it.copy(draft = emptyList(), busy = false, notice = notice, page = if (detail == null) FeedbackPage.List else it.page) }
                if (detail != null) open(detail.caseId)
            } else {
                _state.update { it.copy(busy = false, notice = notice) }
            }
        }
    }

    fun sendLegacy(id: String) {
        val row = rows.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            _state.update { it.copy(notice = noticeFor(composer.sendLegacy(row))) }
        }
    }

    fun discardLegacy(id: String) {
        viewModelScope.launch {
            if (composer.discardLegacy(id)) _state.update { it.copy(notice = FeedbackNotice.Removed) }
        }
    }

    fun open(caseId: String) {
        auth.currentUid ?: return
        val case = _state.value.remoteCases.firstOrNull { it.id == caseId }
        val queued = _state.value.queued.firstOrNull { it.caseId == caseId }
        shotSource = caseId to emptyList()
        thumbnails.clear()
        removals.clear()
        _state.update { it.copy(page = FeedbackPage.Detail(caseId), detail = CaseDetail(caseId, case, queued, loading = case != null)) }
        viewModelScope.launch {
            if (case == null) {
                restate()
                return@launch
            }
            val events = runCatching { remote.events(caseId) }
            val attachments = runCatching { remote.attachments(caseId) }
            if (shotSource?.first == caseId) shotSource = caseId to attachments.getOrDefault(emptyList())
            _state.update { s ->
                s.copy(detail = s.detail?.takeIf { it.caseId == caseId }?.copy(
                    events = events.getOrDefault(emptyList()),
                    loading = false,
                    error = events.isFailure || attachments.isFailure
                ))
            }
            restate()
        }
    }

    /**
     * Takes a screenshot down. It stays in the list, marked as being removed, until the device
     * knows the removal holds: the tombstone written, or — for a case that never left the phone
     * — the upload dropped. Only then does it go, and only then is it called removed.
     */
    fun deleteShot(aid: String) {
        val uid = auth.currentUid ?: return
        val caseId = _state.value.detail?.caseId ?: return
        viewModelScope.launch {
            val localCopy = withContext(Dispatchers.IO) {
                LocalFeedback.pendingUploads(outbox.all(), uid, caseId).firstOrNull { it.aid == aid }?.file
            }
            val outcome = composer.deleteScreenshot(caseId, aid)
            if (outcome is ComposeOutcome.Queued) {
                // The upload is gone from the queue, so nothing will send this copy any more.
                localCopy?.let { withContext(Dispatchers.IO) { preprocessor.discard(uid, it) } }
                removals += aid
                // The picture goes from memory at once, not only from the disk.
                thumbnails -= aid
                _state.update { it.copy(notice = null) }
                restate()
            } else {
                _state.update { it.copy(notice = noticeFor(outcome)) }
            }
        }
    }

    /** Recomputes the open case's screenshots from the whole queue, sent rows included. */
    private suspend fun restate() {
        val uid = auth.currentUid ?: return
        val (caseId, attachments) = shotSource ?: return
        val statuses = withContext(Dispatchers.IO) { LocalFeedback.shots(outbox.all(), uid, caseId, attachments) }
        if (shotSource?.first != caseId) return
        val hidden = statuses.filter { !it.state.showsContent || it.aid in removals }.map { it.aid }.toSet()
        thumbnails -= hidden
        val missing = statuses.filter { it.aid !in hidden && it.aid !in thumbnails }
        val loaded = withContext(Dispatchers.IO) {
            missing.associate { shot ->
                shot.aid to when {
                    shot.file != null -> thumbnail(File(files.attachmentsDir(uid), shot.file))
                    shot.state == ShotState.AVAILABLE ->
                        store.getBytes(AttachmentStore.path(uid, caseId, shot.aid), FeedbackLimits.MAX_IMAGE_BYTES.toLong())?.let(::decode)
                    else -> null
                }
            }
        }
        thumbnails.putAll(loaded)
        val shown = statuses.map { it.aid }.toSet()
        val gone = removals.filter { it !in shown }
        removals -= gone.toSet()
        _state.update { s ->
            s.copy(
                detail = s.detail?.takeIf { it.caseId == caseId }?.copy(
                    shots = statuses.map {
                        Shot(it.aid, it.messageId, it.state, thumbnails[it.aid].takeIf { _ -> it.aid !in hidden })
                    }
                ),
                notice = if (gone.isNotEmpty()) FeedbackNotice.ScreenshotRemoved else s.notice
            )
        }
    }

    /**
     * Copies a draft prepared before the process died are referenced by nothing and would stay
     * on the device for good. Anything written since this screen opened is left alone: it may
     * belong to a draft being put together right now.
     */
    private suspend fun sweepOrphans() {
        val uid = auth.currentUid ?: return
        withContext(Dispatchers.IO) {
            val orphans = LocalFeedback.orphanedFiles(outbox.all(), uid, preprocessor.present(uid))
            preprocessor.sweep(uid, orphans, startedAtMs)
        }
    }

    fun discardQueued(caseId: String) {
        val uid = auth.currentUid ?: return
        viewModelScope.launch {
            val removed = composer.discardQueuedCase(caseId) ?: return@launch
            removed.forEach { preprocessor.discard(uid, it) }
            _state.update { it.copy(page = FeedbackPage.List, detail = null, notice = FeedbackNotice.Removed) }
        }
    }

    /** The owner's word on what was held when approval was withdrawn: send it after all. */
    fun sendHeld() {
        viewModelScope.launch {
            val notice = when (composer.releaseHeld()) {
                is HeldOutcome.Done -> FeedbackNotice.HeldReleased
                HeldOutcome.NotApproved -> FeedbackNotice.NotApproved
                HeldOutcome.SignedOut -> FeedbackNotice.SignedOut
                HeldOutcome.NothingHeld -> return@launch
            }
            _state.update { it.copy(notice = notice) }
        }
    }

    /** The owner's word on what was held: throw it away, screenshot copies included. */
    fun discardHeld() {
        val uid = auth.currentUid ?: return
        viewModelScope.launch {
            val outcome = composer.discardHeld() as? HeldOutcome.Done ?: return@launch
            withContext(Dispatchers.IO) { outcome.files.forEach { preprocessor.discard(uid, it) } }
            _state.update { it.copy(notice = FeedbackNotice.Removed) }
        }
    }

    /**
     * The owner has read that a discard came too late. The delivered rows are let go of; the
     * intent on the server is permanent and the report itself stays in the list.
     */
    fun acknowledgeAlreadyReceived() {
        val ids = receivedIds
        viewModelScope.launch {
            ids.forEach { outbox.discard(it) }
            refreshRemote()
        }
    }

    override fun onCleared() {
        clearDraft()
    }

    /** Copies picked but never sent are not left behind on the device. */
    private fun clearDraft() {
        val uid = auth.currentUid ?: return
        _state.value.draft.forEach { preprocessor.discard(uid, it.image.file) }
        _state.update { it.copy(draft = emptyList()) }
    }

    private fun noticeFor(outcome: ComposeOutcome): FeedbackNotice = when (outcome) {
        is ComposeOutcome.Queued -> if (config.remoteEnabled) FeedbackNotice.Queued else FeedbackNotice.SavedLocally
        ComposeOutcome.Empty -> FeedbackNotice.Empty
        ComposeOutcome.TooLong -> FeedbackNotice.TooLong
        ComposeOutcome.TooManyImages -> FeedbackNotice.TooManyImages
        ComposeOutcome.SignedOut -> FeedbackNotice.SignedOut
        ComposeOutcome.NotApproved -> FeedbackNotice.NotApproved
    }

    private fun thumbnail(file: File): ImageBitmap? =
        if (file.isFile) runCatching { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }.getOrNull() else null

    private fun decode(bytes: ByteArray): ImageBitmap? =
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
}
