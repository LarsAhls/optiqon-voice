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
import se.optiqon.voice.domain.feedback.CaseComposer
import se.optiqon.voice.domain.feedback.CaseRemote
import se.optiqon.voice.domain.feedback.ComposeOutcome
import se.optiqon.voice.domain.feedback.FeedbackConfig
import se.optiqon.voice.domain.feedback.FeedbackLimits
import se.optiqon.voice.domain.feedback.LocalFeedback
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

    init {
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
                    if (uid == null) it.copy(queued = emptyList(), legacy = emptyList())
                    else it.copy(queued = LocalFeedback.queuedCases(unsent, uid), legacy = LocalFeedback.legacy(unsent, uid))
                }
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
        val uid = auth.currentUid ?: return
        val case = _state.value.remoteCases.firstOrNull { it.id == caseId }
        val queued = _state.value.queued.firstOrNull { it.caseId == caseId }
        _state.update { it.copy(page = FeedbackPage.Detail(caseId), detail = CaseDetail(caseId, case, queued, loading = case != null)) }
        viewModelScope.launch {
            val pending = withContext(Dispatchers.IO) {
                LocalFeedback.pendingUploads(rows, uid, caseId).map {
                    Shot(it.aid, it.messageId, pending = true, thumbnail = thumbnail(File(files.attachmentsDir(uid), it.file)))
                }
            }
            if (case == null) {
                _state.update { s -> s.copy(detail = s.detail?.takeIf { it.caseId == caseId }?.copy(shots = pending)) }
                return@launch
            }
            val events = runCatching { remote.events(caseId) }
            val attachments = runCatching { remote.attachments(caseId) }
            val remoteShots = withContext(Dispatchers.IO) {
                attachments.getOrDefault(emptyList())
                    .filterNot { a -> a.deleted || pending.any { it.aid == a.id } }
                    .map { a ->
                        val bytes = store.getBytes(AttachmentStore.path(uid, caseId, a.id), FeedbackLimits.MAX_IMAGE_BYTES.toLong())
                        Shot(a.id, a.messageId, pending = false, thumbnail = bytes?.let(::decode))
                    }
            }
            _state.update { s ->
                s.copy(detail = s.detail?.takeIf { it.caseId == caseId }?.copy(
                    events = events.getOrDefault(emptyList()),
                    shots = remoteShots + pending,
                    loading = false,
                    error = events.isFailure || attachments.isFailure
                ))
            }
        }
    }

    /** A tombstone on the queue; the bytes are the server's to remove. */
    fun deleteShot(aid: String) {
        val uid = auth.currentUid ?: return
        val caseId = _state.value.detail?.caseId ?: return
        val localCopy = LocalFeedback.pendingUploads(rows, uid, caseId).firstOrNull { it.aid == aid }?.file
        viewModelScope.launch {
            val outcome = composer.deleteScreenshot(caseId, aid)
            if (outcome is ComposeOutcome.Queued) {
                localCopy?.let { preprocessor.discard(uid, it) }
                _state.update { s ->
                    s.copy(
                        notice = FeedbackNotice.ScreenshotRemoved,
                        detail = s.detail?.copy(shots = s.detail.shots.filterNot { it.aid == aid })
                    )
                }
            } else {
                _state.update { it.copy(notice = noticeFor(outcome)) }
            }
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
