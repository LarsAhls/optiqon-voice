package se.optiqon.voice.ui.access

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
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
import se.optiqon.voice.testing.captureBaseline
import se.optiqon.voice.ui.theme.OptiqonVoiceTheme

/**
 * The signed-out screen in each shape a build can take, drawn and asserted.
 *
 * Which routes a build has depends on what the Firebase project had been through when its
 * google-services.json was downloaded: nothing (no file), email link only (Auth initialised,
 * no OAuth client yet), or both. Each shape is a picture, because "Google is primary, email
 * secondary" is a claim about layout, and a claim about layout that nobody can see is not
 * one that gets caught when it stops being true.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night")
class SignedOutScreenTest {

    @get:Rule val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun string(id: Int) = context.getString(id)

    private fun show(name: String, state: AccountUiState.SignedOut) {
        composeRule.setContent {
            OptiqonVoiceTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    AccountScreenContent(state = state, busy = false, message = null)
                }
            }
        }
        composeRule.onRoot().captureBaseline(name)
    }

    @Test
    fun `no configuration says so and offers nothing`() {
        show(
            "account_signed_out_unconfigured",
            AccountUiState.SignedOut(googleAvailable = false, emailLinkAvailable = false, completingLink = false)
        )
        composeRule.onNodeWithText(string(R.string.registration_not_configured)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.registration_google)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.registration_email)).assertDoesNotExist()
    }

    @Test
    fun `email link only hides the Google button rather than offering one that fails`() {
        show(
            "account_signed_out_email_only",
            AccountUiState.SignedOut(googleAvailable = false, emailLinkAvailable = true, completingLink = false)
        )
        composeRule.onNodeWithText(string(R.string.registration_google)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.registration_email_label)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.registration_email)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.registration_not_configured)).assertDoesNotExist()
    }

    @Test
    fun `both routes show Google first and the email link second`() {
        show(
            "account_signed_out_google_and_email",
            AccountUiState.SignedOut(googleAvailable = true, emailLinkAvailable = true, completingLink = false)
        )
        val google = composeRule.onNodeWithText(string(R.string.registration_google))
        val email = composeRule.onNodeWithText(string(R.string.registration_email))
        google.assertIsDisplayed()
        email.assertIsDisplayed()
        val googleTop = google.fetchSemanticsNode().positionInRoot.y
        val emailTop = email.fetchSemanticsNode().positionInRoot.y
        assert(googleTop < emailTop) { "Google ($googleTop) must be drawn above the email link ($emailTop)" }
    }

    @Test
    fun `a link opened on this device still asks for the address even without a request route`() {
        // The request was made elsewhere; this build only has to finish it.
        show(
            "account_signed_out_completing_link",
            AccountUiState.SignedOut(googleAvailable = true, emailLinkAvailable = false, completingLink = true)
        )
        composeRule.onNodeWithText(string(R.string.registration_email_confirm)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.registration_email)).assertDoesNotExist()
    }
}
