package se.optiqon.voice.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import se.optiqon.voice.R
import se.optiqon.voice.domain.feedback.CaseEvent
import se.optiqon.voice.domain.feedback.FeedbackCase
import se.optiqon.voice.domain.feedback.LegacyNote
import se.optiqon.voice.domain.feedback.PreparedImage
import se.optiqon.voice.domain.feedback.QueuedCase
import se.optiqon.voice.domain.feedback.ShotState
import se.optiqon.voice.testing.PrimeTypefaces
import se.optiqon.voice.testing.captureBaseline
import se.optiqon.voice.ui.theme.OptiqonVoiceTheme

/**
 * Report a problem, drawn: the list, the form and one case.
 *
 * What is asserted beyond the pictures is the honesty of the copy. A build with sending switched
 * off has to say so and offer "save on this device" rather than "send"; a note written before
 * reports could be sent is offered, never sent on its own; and an account that is not approved
 * cannot start a report. No fixture carries a timestamp, so the pictures do not drift by date.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night")
class FeedbackScreenTest {

    @get:Rule val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun string(id: Int) = context.getString(id)

    private val padding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)

    private val remoteCase = FeedbackCase(
        id = "case-1",
        title = "Dikteringen stannar efter 30 sekunder",
        body = "Dikteringen stannar efter 30 sekunder\n\n1.2.3 · Android 34 · Pixel",
        status = "open",
        createdAtMs = null,
        lastActivityAtMs = null,
        activeAttachmentCount = 1,
        closed = false
    )

    private fun list(state: FeedbackUiState) {
        composeRule.setContent {
            OptiqonVoiceTheme {
                PrimeTypefaces()
                FeedbackListContent(state, padding, onNew = {}, onOpen = {}, onSendLegacy = {}, onDeleteLegacy = {})
            }
        }
    }

    @Test
    fun `the list shows the device's queue, the remote cases and the unsent legacy note`() {
        list(
            FeedbackUiState(
                remoteEnabled = true,
                approved = true,
                remoteCases = listOf(remoteCase, remoteCase.copy(id = "case-2", title = "Fel språk i menyn", closed = true)),
                queued = listOf(QueuedCase("case-3", "Appen kraschar vid start", emptyList(), blocked = false, createdAtMs = 0)),
                legacy = listOf(LegacyNote("legacy-1", "Knappen för att byta profil syns inte i mörkt läge.", 0))
            )
        )
        composeRule.onRoot().captureBaseline("feedback_list")

        composeRule.onNodeWithText(string(R.string.feedback_pill_waiting)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.feedback_legacy_send)).assertIsEnabled()
        composeRule.onNodeWithText(string(R.string.feedback_legacy_delete)).assertIsDisplayed()
    }

    @Test
    fun `a build that sends nothing says so, and an unapproved account cannot start a report`() {
        list(FeedbackUiState(remoteEnabled = false, approved = false))
        composeRule.onRoot().captureBaseline("feedback_list_local_unapproved")

        composeRule.onNodeWithText(string(R.string.feedback_local_only)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.feedback_not_approved)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.feedback_new)).assertIsNotEnabled()
        composeRule.onNodeWithText(string(R.string.feedback_empty_title)).assertIsDisplayed()
    }

    @Test
    fun `the form counts screenshots and, with sending off, saves on the device`() {
        val draft = listOf(DraftImage(PreparedImage("a.png", "image/png", 100), thumbnail = null))
        composeRule.setContent {
            OptiqonVoiceTheme {
                PrimeTypefaces()
                FeedbackComposeContent(
                    state = FeedbackUiState(page = FeedbackPage.Compose, remoteEnabled = false, approved = true, draft = draft),
                    padding = padding,
                    text = "Dikteringen stannar efter 30 sekunder när skärmen släcks.",
                    onText = {}, onPick = {}, onRemoveImage = {}, onSubmit = {}
                )
            }
        }
        composeRule.onRoot().captureBaseline("feedback_compose")

        // The eyebrow sets its text in capitals.
        composeRule.onNodeWithText(context.getString(R.string.feedback_screenshots, 1, 3), ignoreCase = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.feedback_save_local)).assertIsEnabled()
        composeRule.onNodeWithText(string(R.string.feedback_send)).assertDoesNotExist()
    }

    @Test
    fun `a case shows its timeline, its screenshots and a reply box`() {
        val detail = CaseDetail(
            caseId = "case-1",
            case = remoteCase,
            queued = null,
            events = listOf(
                CaseEvent("e1", "message", "Vilken telefonmodell gäller det?", fromOwner = false, toStatus = null, createdAtMs = null),
                CaseEvent("e2", "message", "Pixel 8, Android 15.", fromOwner = true, toStatus = null, createdAtMs = null)
            ),
            shots = listOf(Shot("aid-1", messageId = null, state = ShotState.AVAILABLE, thumbnail = null))
        )
        composeRule.setContent {
            OptiqonVoiceTheme {
                PrimeTypefaces()
                FeedbackDetailContent(
                    state = FeedbackUiState(page = FeedbackPage.Detail("case-1"), remoteEnabled = true, approved = true, detail = detail),
                    padding = padding,
                    reply = "",
                    onReply = {}, onPick = {}, onRemoveImage = {}, onSubmit = {}, onDeleteShot = {}, onDiscardQueued = {}
                )
            }
        }
        composeRule.onRoot().captureBaseline("feedback_detail")

        composeRule.onNodeWithText(string(R.string.feedback_from_optiqon)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.feedback_delete_screenshot)).assertIsEnabled()
        composeRule.onNodeWithText(string(R.string.feedback_reply_label)).assertIsDisplayed()
    }

    @Test
    fun `a closed case offers no reply`() {
        val detail = CaseDetail("case-1", remoteCase.copy(closed = true), queued = null)
        composeRule.setContent {
            OptiqonVoiceTheme {
                FeedbackDetailContent(
                    state = FeedbackUiState(page = FeedbackPage.Detail("case-1"), remoteEnabled = true, approved = true, detail = detail),
                    padding = padding,
                    reply = "",
                    onReply = {}, onPick = {}, onRemoveImage = {}, onSubmit = {}, onDeleteShot = {}, onDiscardQueued = {}
                )
            }
        }
        composeRule.onNodeWithText(string(R.string.feedback_detail_closed)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.feedback_reply_label)).assertDoesNotExist()
    }

    @Test
    fun `a case still on the device can be taken back and says it has not reached Optiqon`() {
        val queued = QueuedCase("case-9", "Appen kraschar vid start", emptyList(), blocked = false, createdAtMs = 0)
        composeRule.setContent {
            OptiqonVoiceTheme {
                FeedbackDetailContent(
                    state = FeedbackUiState(
                        page = FeedbackPage.Detail("case-9"), remoteEnabled = true, approved = false,
                        detail = CaseDetail("case-9", case = null, queued = queued)
                    ),
                    padding = padding,
                    reply = "",
                    onReply = {}, onPick = {}, onRemoveImage = {}, onSubmit = {}, onDeleteShot = {}, onDiscardQueued = {}
                )
            }
        }
        composeRule.onNodeWithText(string(R.string.feedback_detail_on_device)).assertIsDisplayed()
        // Taking your own unsent report off your own phone needs no approval.
        composeRule.onNodeWithText(string(R.string.feedback_discard_queued)).assertIsEnabled()
    }


    @Test
    fun `a refused upload is shown as failed, never as waiting`() {
        val detail = CaseDetail(
            caseId = "case-1",
            case = remoteCase,
            queued = null,
            shots = listOf(Shot("aid-1", messageId = null, state = ShotState.UPLOAD_FAILED, thumbnail = null))
        )
        composeRule.setContent {
            OptiqonVoiceTheme {
                FeedbackDetailContent(
                    state = FeedbackUiState(page = FeedbackPage.Detail("case-1"), remoteEnabled = true, approved = true, detail = detail),
                    padding = padding,
                    reply = "",
                    onReply = {}, onPick = {}, onRemoveImage = {}, onSubmit = {}, onDeleteShot = {}, onDiscardQueued = {}
                )
            }
        }
        composeRule.onNodeWithText(string(R.string.feedback_shot_upload_failed)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.feedback_shot_pending)).assertDoesNotExist()
        // The one way out: remove it.
        composeRule.onNodeWithText(string(R.string.feedback_delete_screenshot)).assertIsEnabled()
    }

    @Test
    fun `a removal under way is not called done`() {
        val detail = CaseDetail(
            caseId = "case-1",
            case = remoteCase,
            queued = null,
            shots = listOf(Shot("aid-1", messageId = null, state = ShotState.REMOVING, thumbnail = null))
        )
        composeRule.setContent {
            OptiqonVoiceTheme {
                FeedbackDetailContent(
                    state = FeedbackUiState(page = FeedbackPage.Detail("case-1"), remoteEnabled = true, approved = true, detail = detail),
                    padding = padding,
                    reply = "",
                    onReply = {}, onPick = {}, onRemoveImage = {}, onSubmit = {}, onDeleteShot = {}, onDiscardQueued = {}
                )
            }
        }
        composeRule.onNodeWithText(string(R.string.feedback_shot_removing)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.feedback_notice_screenshot_removed)).assertDoesNotExist()
    }

    private fun shotsOnly(vararg shots: Shot) {
        val detail = CaseDetail(caseId = "case-1", case = remoteCase, queued = null, shots = shots.toList())
        composeRule.setContent {
            OptiqonVoiceTheme {
                FeedbackDetailContent(
                    state = FeedbackUiState(page = FeedbackPage.Detail("case-1"), remoteEnabled = true, approved = true, detail = detail),
                    padding = padding,
                    reply = "",
                    onReply = {}, onPick = {}, onRemoveImage = {}, onSubmit = {}, onDeleteShot = {}, onDiscardQueued = {}
                )
            }
        }
    }

    @Test
    fun `a screenshot being removed is not drawn, even with its picture still in memory`() {
        val picture = ImageBitmap(8, 8)
        shotsOnly(
            Shot("aid-1", messageId = null, state = ShotState.REMOVING, thumbnail = picture),
            Shot("aid-2", messageId = null, state = ShotState.REMOVE_FAILED, thumbnail = picture)
        )
        composeRule.onAllNodesWithTag(THUMBNAIL_TAG, useUnmergedTree = true).assertCountEquals(0)
        // Where the removal stands is still said, and it is not called done.
        composeRule.onNodeWithText(string(R.string.feedback_shot_removing)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.feedback_shot_remove_failed)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.feedback_notice_screenshot_removed)).assertDoesNotExist()
    }

    @Test
    fun `a screenshot that is staying is drawn`() {
        // The control for the test above: the tag does find a picture when there is one.
        shotsOnly(Shot("aid-1", messageId = null, state = ShotState.AVAILABLE, thumbnail = ImageBitmap(8, 8)))
        composeRule.onAllNodesWithTag(THUMBNAIL_TAG, useUnmergedTree = true).assertCountEquals(1)
    }
}
