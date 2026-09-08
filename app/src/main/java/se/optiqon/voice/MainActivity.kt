package se.optiqon.voice

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import se.optiqon.voice.domain.access.EmailLinkRelay
import se.optiqon.voice.domain.access.SignInClient
import se.optiqon.voice.ui.navigation.AppNavGraph
import se.optiqon.voice.ui.theme.OptiqonVoiceTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var signInClient: SignInClient
    @Inject lateinit var emailLinkRelay: EmailLinkRelay

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        offerSignInLink(intent)
        setContent {
            OptiqonVoiceTheme {
                AppNavGraph()
            }
        }
    }

    /** The activity is `singleTask`, so a link arriving while it is alive comes through here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        offerSignInLink(intent)
    }

    /**
     * Parks a sign-in link for the account screen to complete.
     *
     * The link is checked with Firebase rather than by its shape: any app can be sent a URL on
     * this path, and only Firebase can say whether one is a genuine sign-in link.
     */
    private fun offerSignInLink(intent: Intent?) {
        val link = intent?.data?.toString() ?: return
        if (signInClient.isEmailLink(link)) emailLinkRelay.offer(link)
    }
}
