package se.optiqon.voice.ui.settings

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.ImageBitmap
import se.optiqon.voice.R
import se.optiqon.voice.domain.feedback.CaseEvent
import se.optiqon.voice.domain.feedback.FeedbackCase
import se.optiqon.voice.domain.feedback.LegacyNote
import se.optiqon.voice.domain.feedback.PreparedImage
import se.optiqon.voice.domain.feedback.QueuedCase
import se.optiqon.voice.domain.feedback.ShotState

sealed interface FeedbackPage {
    data object List : FeedbackPage
    data object Compose : FeedbackPage
    data class Detail(val caseId: String) : FeedbackPage
}

/** A screenshot picked for the message being written, already stripped and on disk. */
data class DraftImage(val image: PreparedImage, val thumbnail: ImageBitmap?)

/** A screenshot shown with a case, and where it stands — see [ShotState]. */
data class Shot(val aid: String, val messageId: String?, val state: ShotState, val thumbnail: ImageBitmap?)

data class CaseDetail(
    val caseId: String,
    /** Null while the case exists only on this device. */
    val case: FeedbackCase?,
    val queued: QueuedCase?,
    val events: List<CaseEvent> = emptyList(),
    val shots: List<Shot> = emptyList(),
    val loading: Boolean = false,
    val error: Boolean = false
) {
    val title: String get() = case?.title ?: queued?.title.orEmpty()
    val closed: Boolean get() = case?.closed == true
}

/** [ok] is false for a refusal: it is shown as a problem, never with the confirmation tick. */
enum class FeedbackNotice(@StringRes val res: Int, val ok: Boolean = false) {
    Queued(R.string.feedback_notice_queued, ok = true),
    SavedLocally(R.string.feedback_notice_saved_locally, ok = true),
    Empty(R.string.feedback_empty),
    TooLong(R.string.feedback_too_long),
    TooManyImages(R.string.feedback_notice_too_many_images),
    ImageUnsupported(R.string.feedback_notice_image_unsupported),
    ImageUnreadable(R.string.feedback_notice_image_unreadable),
    SignedOut(R.string.feedback_signed_out),
    NotApproved(R.string.feedback_notice_not_approved),
    Removed(R.string.feedback_notice_removed, ok = true),
    HeldReleased(R.string.feedback_notice_held_released, ok = true),
    ScreenshotRemoved(R.string.feedback_notice_screenshot_removed, ok = true)
}

data class FeedbackUiState(
    val page: FeedbackPage = FeedbackPage.List,
    /** False in a build where nothing leaves the device; the copy then says so. */
    val remoteEnabled: Boolean = false,
    val signedIn: Boolean = true,
    val approved: Boolean = false,
    val remoteCases: List<FeedbackCase> = emptyList(),
    val remoteError: Boolean = false,
    val queued: List<QueuedCase> = emptyList(),
    val legacy: List<LegacyNote> = emptyList(),
    /** Rows held when approval was withdrawn; nothing sends them until the owner chooses. */
    val held: Int = 0,
    val draft: List<DraftImage> = emptyList(),
    val detail: CaseDetail? = null,
    val busy: Boolean = false,
    val notice: FeedbackNotice? = null
) {
    /** A queued case whose opening has since reached Optiqon is shown once, as the remote one. */
    val localOnly: List<QueuedCase>
        get() = queued.filter { q -> remoteCases.none { it.id == q.caseId } }
}
