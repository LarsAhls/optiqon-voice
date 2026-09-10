package se.optiqon.voice.ui.access

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import se.optiqon.voice.R
import se.optiqon.voice.domain.access.BlockReason
import se.optiqon.voice.testing.captureBaseline
import se.optiqon.voice.ui.theme.OptiqonVoiceTheme

/**
 * The approval screen, drawn.
 *
 * The hierarchy here is a claim: "Check again" is the one thing this screen can do for you and
 * has to look like it, while signing out is an escape hatch. Both are assertions about what is
 * above what and how loud each is, so both are a picture rather than a description. The theme
 * is entered without a Box behind it on purpose — the screens run inside `OptiqonVoiceTheme`
 * alone in the real app, and it is that path which has to produce readable text.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night")
class WaitingScreenTest {

    @get:Rule val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun string(id: Int) = context.getString(id)

    private fun show(name: String, reason: BlockReason) {
        composeRule.setContent {
            OptiqonVoiceTheme {
                AccountScreenContent(
                    state = AccountUiState.Waiting(email = "ahlstedt.lars@gmail.com", reason = reason),
                    busy = false,
                    message = null
                )
            }
        }
        composeRule.onRoot().captureBaseline(name)
    }

    @Test
    fun `a pending account is told to wait and offered the one useful action first`() {
        show("account_waiting_pending", BlockReason.AWAITING_APPROVAL)
        val check = composeRule.onNodeWithText(string(R.string.registration_check_again))
        val signOut = composeRule.onNodeWithText(string(R.string.registration_sign_out))
        check.assertIsDisplayed()
        signOut.assertIsDisplayed()

        val checkNode = check.fetchSemanticsNode()
        val signOutNode = signOut.fetchSemanticsNode()
        assert(checkNode.positionInRoot.y < signOutNode.positionInRoot.y) {
            "Check again must be drawn above Sign out"
        }
        // The primary is a full-width slab; the sign-out is a bare label. Comparing their
        // widths is the cheapest thing that fails if somebody makes them peers again.
        assert(checkNode.size.width > signOutNode.size.width * 2) {
            "Check again (${checkNode.size.width}) must be visibly heavier than " +
                "Sign out (${signOutNode.size.width})"
        }
    }

    @Test
    fun `a revoked account is not told it is waiting`() {
        show("account_waiting_revoked", BlockReason.REVOKED)
        composeRule.onNodeWithText(string(R.string.registration_revoked_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.registration_pending_title)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.support_contact_action)).assertIsDisplayed()
    }
}
