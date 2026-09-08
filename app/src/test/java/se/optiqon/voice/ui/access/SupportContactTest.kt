package se.optiqon.voice.ui.access

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import se.optiqon.voice.R
import se.optiqon.voice.domain.access.BlockReason
import se.optiqon.voice.ui.theme.OptiqonVoiceTheme

/**
 * The one screen where a blocked user is offered a way to reach a person.
 *
 * Rev. 11 asks for "Skriv till support" on the revoked screen, and the obvious implementation —
 * a message box wired to the feedback outbox — would be a lie: `OutboxSender` is a placeholder
 * with no transport behind it, so the confirmation would be a receipt for something that never
 * left the device. Somebody locked out of the app is exactly the person who would then wait for
 * an answer to a message nobody received.
 *
 * So the contract is narrow and testable: the screen offers contact information, the app sends
 * nothing on its own, and no text claims otherwise. The address is now decided, so the button
 * exists — and what it does is pinned here: exactly one ACTION_SENDTO to that address, only on a
 * tap, carrying no body. Handing text to the user's own mail app is not sending it; the send
 * button in that app belongs to the user.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night")
class SupportContactTest {

    @get:Rule val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun show(reason: BlockReason) {
        composeRule.setContent {
            OptiqonVoiceTheme {
                AccountScreenContent(
                    state = AccountUiState.Waiting("a@example.test", reason),
                    busy = false,
                    message = null
                )
            }
        }
    }

    private fun string(id: Int): String = context.getString(id)

    @Test
    fun `the revoked screen offers contact and says the app sends nothing`() {
        show(BlockReason.REVOKED)

        composeRule.onNodeWithText(string(R.string.support_contact_title)).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.support_contact_body)).assertIsDisplayed()
    }

    @Test
    fun `rendering it starts nothing and sends nothing`() {
        show(BlockReason.REVOKED)
        composeRule.waitForIdle()

        // Nothing may leave the device without the user doing something. Merely arriving at the
        // screen is not the user doing something.
        assertNull(
            "no intent, no mail composer, no request — the screen only draws",
            shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity
        )
    }

    @Test
    fun `the button is offered only because an address is actually configured`() {
        // A button that opened an empty `mailto:` would be worse than no button: it looks like
        // a working route and ends in a composer addressed to nobody. The screen keeps that
        // fallback, so this asserts the shipped value rather than the branch.
        val address = string(R.string.support_contact_email)
        assertTrue("a support address must be configured: $address", address.contains("@"))

        show(BlockReason.REVOKED)
        composeRule.onNodeWithText(string(R.string.support_contact_action)).assertIsDisplayed()
    }

    @Test
    fun `tapping it hands the address to the user's own mail app and nothing else`() {
        show(BlockReason.REVOKED)

        composeRule.onNodeWithText(string(R.string.support_contact_action)).performClick()
        composeRule.waitForIdle()

        val shadow = shadowOf(ApplicationProvider.getApplicationContext<Application>())
        val started = shadow.nextStartedActivity
        assertNotNull("the tap must open a composer", started)
        assertEquals(Intent.ACTION_SENDTO, started!!.action)
        assertEquals("mailto:" + string(R.string.support_contact_email), started.data.toString())

        // No pre-filled body, because a body is the beginning of a message the app would be
        // authoring on the user's behalf. And exactly one intent: a tap is one act.
        assertNull(started.getStringExtra(Intent.EXTRA_TEXT))
        assertNull("one tap, one composer", shadow.nextStartedActivity)
    }

    @Test
    fun `no copy on this screen promises a message, a receipt or a reply`() {
        val copy = listOf(
            R.string.registration_revoked_title,
            R.string.registration_revoked_body,
            R.string.support_contact_title,
            R.string.support_contact_body,
            R.string.support_contact_action
        ).map { string(it).lowercase() }

        // The failure this guards against is a well-meant edit, not a bug: "we have received
        // your message", "you will hear from us", "sent" are all the kind of sentence somebody
        // adds to be reassuring.
        val forbidden = listOf("message sent", "we will get back", "you will hear", "received your")
        copy.forEach { text ->
            forbidden.forEach { claim ->
                assertTrue("copy must not promise \"$claim\": $text", !text.contains(claim))
            }
        }
    }

    @Test
    fun `a pending account is not offered support at all`() {
        // The control: the section is conditional, so its presence above is about the revoked
        // state rather than about the screen always showing it. Someone whose account is simply
        // not looked at yet has nothing to write about.
        show(BlockReason.AWAITING_APPROVAL)

        composeRule.onNodeWithText(string(R.string.support_contact_title)).assertDoesNotExist()
        composeRule.onNodeWithText(string(R.string.registration_pending_title)).assertIsDisplayed()
    }
}
