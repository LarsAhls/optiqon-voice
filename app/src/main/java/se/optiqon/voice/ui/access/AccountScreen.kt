package se.optiqon.voice.ui.access

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.optiqon.voice.R
import se.optiqon.voice.domain.access.BlockReason
import se.optiqon.voice.domain.access.DisplayName
import se.optiqon.voice.ui.common.GoogleSignInButton
import se.optiqon.voice.ui.common.PrimaryButton
import se.optiqon.voice.ui.common.SecondaryButton
import se.optiqon.voice.ui.theme.TextPrimary
import se.optiqon.voice.ui.theme.TextSecondary

/**
 * The screen every unapproved account sees. Registration is required for the whole app, so
 * this stands in front of the main graph rather than beside it.
 */
@Composable
fun AccountScreen(
    viewModel: AccountViewModel = hiltViewModel(),
    modifier: Modifier = Modifier.fillMaxSize()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? Activity

    AccountScreenContent(
        state = state,
        busy = busy,
        message = message,
        modifier = modifier,
        onGoogle = { activity?.let(viewModel::signInWithGoogle) },
        onEmail = viewModel::submitEmail,
        onName = viewModel::submitName,
        onClaim = viewModel::answerDataClaim,
        onRefresh = viewModel::refresh,
        onSignOut = viewModel::signOut,
        onConsumeMessage = viewModel::consumeMessage
    )
}

/**
 * The same screen with the view model taken out of it.
 *
 * Split out so that what these states actually say can be asserted: the four blocked reasons
 * are told apart here, and the revoked screen's support section is the one place in the app
 * that must offer contact without ever sending anything. Both are claims about what is drawn,
 * and a test that cannot draw them is not evidence for either.
 */
@Composable
fun AccountScreenContent(
    state: AccountUiState,
    busy: Boolean,
    message: String?,
    modifier: Modifier = Modifier.fillMaxSize(),
    onGoogle: () -> Unit = {},
    onEmail: (String) -> Unit = {},
    onName: (String) -> Unit = {},
    onClaim: (Boolean) -> Unit = {},
    onRefresh: () -> Unit = {},
    onSignOut: () -> Unit = {},
    onConsumeMessage: () -> Unit = {}
) {
    Column(
        modifier = modifier.padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when (state) {
            is AccountUiState.Loading, is AccountUiState.Approved -> CircularProgressIndicator()

            is AccountUiState.SignedOut -> SignedOut(
                googleAvailable = state.googleAvailable,
                emailLinkAvailable = state.emailLinkAvailable,
                completingLink = state.completingLink,
                busy = busy,
                onGoogle = onGoogle,
                onEmail = onEmail
            )

            is AccountUiState.NeedsName -> NeedsName(
                email = state.email,
                suggestion = state.suggestion,
                busy = busy,
                onName = onName,
                onSignOut = onSignOut
            )

            is AccountUiState.ClaimChoice -> ClaimChoice(
                email = state.email,
                busy = busy,
                onClaim = { onClaim(true) },
                onStartEmpty = { onClaim(false) }
            )

            is AccountUiState.Waiting -> Waiting(
                email = state.email,
                reason = state.reason,
                busy = busy,
                onRefresh = onRefresh,
                onSignOut = onSignOut
            )
        }

        message?.let {
            Body(it)
            TextButton(onClick = onConsumeMessage) {
                Text(stringResource(R.string.action_ok))
            }
        }
    }
}

/*
 * The three text roles these screens use. They are spelled out rather than inherited so that
 * the contrast is a property of the screen and not of whatever happens to be providing
 * `LocalContentColor` at the time — which is how the headings ended up unreadable once.
 */

/** The one heading on the screen. Off-white, large, centred. */
@Composable
private fun Heading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall,
        color = TextPrimary,
        textAlign = TextAlign.Center
    )
}

/** Explanatory copy under a heading. Light grey, deliberately quieter than the heading. */
@Composable
private fun Body(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium,
        color = TextSecondary,
        textAlign = TextAlign.Center
    )
}

/** The signed-in address. Quiet, but readable — it is how you catch the wrong account. */
@Composable
private fun AccountLine(email: String) {
    Text(
        text = email,
        style = MaterialTheme.typography.titleSmall,
        color = TextPrimary,
        textAlign = TextAlign.Center
    )
}

/**
 * Fields on this screen carry their own colours for the same reason the text roles do: the
 * defaults put the label, the placeholder and the resting border at alphas meant for a light
 * scheme, which on this canvas leaves an empty field looking like an empty rectangle.
 */
@Composable
private fun authFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = TextSecondary.copy(alpha = 0.55f),
    focusedLabelColor = MaterialTheme.colorScheme.primary,
    unfocusedLabelColor = TextSecondary,
    focusedPlaceholderColor = TextSecondary,
    unfocusedPlaceholderColor = TextSecondary,
    cursorColor = MaterialTheme.colorScheme.primary
)

/**
 * Google first, email link second, and each only when this build can actually do it. A route
 * that is missing is not drawn: a button that fails on tap teaches the tester that the app is
 * broken, when the truth is that the project is not finished being set up.
 */
@Composable
private fun SignedOut(
    googleAvailable: Boolean,
    emailLinkAvailable: Boolean,
    completingLink: Boolean,
    busy: Boolean,
    onGoogle: () -> Unit,
    onEmail: (String) -> Unit
) {
    var email by rememberSaveable { mutableStateOf("") }

    Heading(stringResource(R.string.registration_title))

    if (!googleAvailable && !emailLinkAvailable) {
        // Being told the build cannot sign in beats a button that silently does nothing.
        Body(stringResource(R.string.registration_not_configured))
        return
    }

    if (googleAvailable) {
        Spacer(Modifier.height(8.dp))
        GoogleSignInButton(
            text = stringResource(R.string.registration_google),
            onClick = onGoogle,
            enabled = !busy
        )
    }

    // A link that is already open needs the address even when this build could not have
    // requested it: the request was made elsewhere, and the address is the only thing missing.
    if (emailLinkAvailable || completingLink) {
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            colors = authFieldColors(),
            label = { Text(stringResource(R.string.registration_email_label)) }
        )

        // Lower emphasis than Google on purpose: the two routes are not equally recommended.
        SecondaryButton(
            text = stringResource(
                if (completingLink) R.string.registration_email_confirm
                else R.string.registration_email
            ),
            onClick = { onEmail(email.trim()) },
            enabled = !busy && email.contains('@')
        )
    }
}

/**
 * Contact information, and at most a mail composer the user opens themselves.
 *
 * Deliberately not wired to the feedback outbox: `OutboxSender` is a placeholder with no
 * transport behind it, so a "message sent" here would be a receipt for something that never
 * left the device. The app sends nothing, automatically or otherwise; the mail button hands the
 * text to the user's own mail app, where they decide whether to send it.
 */
@Composable
private fun SupportContact() {
    val context = LocalContext.current
    val address = stringResource(R.string.support_contact_email)

    Text(
        text = stringResource(R.string.support_contact_title),
        style = MaterialTheme.typography.titleSmall,
        color = TextPrimary
    )
    Body(stringResource(R.string.support_contact_body))

    if (address.isNotBlank()) {
        TextButton(onClick = {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + address))
            runCatching { context.startActivity(intent) }
        }) {
            Text(stringResource(R.string.support_contact_action))
        }
    }
}

/**
 * The name step, between a proven account and a registration request.
 *
 * A name is required and a phone number is not, which is a product decision that only holds if
 * the name is one the person actually gave. The provider's own name is offered pre-filled — for
 * the Google route it is usually right, and retyping it would be busywork — but it stands in an
 * editable field, so accepting it is an act rather than an assumption. There is no derivation
 * from the email address anywhere: `first.last@` reads as a name often enough to be accepted
 * unread, and a name nobody chose is worse when it is nearly right.
 *
 * Signing out is offered here because this is the first screen after a sign-in, and somebody who
 * has just signed in as the wrong account must be able to leave without registering that account.
 */
@Composable
private fun NeedsName(
    email: String?,
    suggestion: String,
    busy: Boolean,
    onName: (String) -> Unit,
    onSignOut: () -> Unit
) {
    var name by rememberSaveable(suggestion) { mutableStateOf(suggestion) }

    Heading(stringResource(R.string.registration_name_title))
    email?.let { AccountLine(it) }
    Body(stringResource(R.string.registration_name_body))

    OutlinedTextField(
        value = name,
        onValueChange = { if (it.length <= DisplayName.MAX_LENGTH) name = it },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = MaterialTheme.shapes.small,
        colors = authFieldColors(),
        label = { Text(stringResource(R.string.registration_name_label)) }
    )

    // The same predicate the view model re-applies. A disabled button is a courtesy, not the
    // check: whitespace looks like a name in a text field and is not one.
    PrimaryButton(
        text = stringResource(R.string.registration_name_submit),
        onClick = { onName(name) },
        enabled = !busy && DisplayName.isValid(name)
    )
    SignOutAction(busy = busy, onSignOut = onSignOut)
}

@Composable
private fun ClaimChoice(
    email: String?,
    busy: Boolean,
    onClaim: () -> Unit,
    onStartEmpty: () -> Unit
) {
    Heading(stringResource(R.string.claim_title))
    email?.let { AccountLine(it) }
    Body(stringResource(R.string.claim_body))

    PrimaryButton(
        text = stringResource(R.string.claim_link),
        onClick = onClaim,
        enabled = !busy
    )
    TextButton(onClick = onStartEmpty, enabled = !busy) {
        Text(stringResource(R.string.claim_empty))
    }
    Text(
        text = stringResource(R.string.claim_empty_note),
        style = MaterialTheme.typography.bodySmall,
        color = TextSecondary,
        textAlign = TextAlign.Center
    )
}

/**
 * The four blocked states, told apart.
 *
 * They used to share the heading "Waiting for approval", which is false for three of them: a
 * revoked account is not waiting for anything, and telling somebody to wait for a decision that
 * has already been taken against them is the kind of copy that produces a support message
 * instead of an understanding.
 */
@Composable
private fun Waiting(
    email: String?,
    reason: BlockReason,
    busy: Boolean,
    onRefresh: () -> Unit,
    onSignOut: () -> Unit
) {
    val title = when (reason) {
        BlockReason.AWAITING_APPROVAL, BlockReason.NOT_REGISTERED -> R.string.registration_pending_title
        BlockReason.REJECTED -> R.string.registration_rejected_title
        BlockReason.REVOKED -> R.string.registration_revoked_title
        BlockReason.GRACE_EXPIRED -> R.string.registration_grace_expired_title
    }
    val body = when (reason) {
        BlockReason.AWAITING_APPROVAL, BlockReason.NOT_REGISTERED -> R.string.registration_pending_body
        BlockReason.REJECTED -> R.string.registration_rejected_body
        BlockReason.REVOKED -> R.string.registration_revoked_body
        BlockReason.GRACE_EXPIRED -> R.string.registration_grace_expired_body
    }

    Heading(stringResource(title))
    email?.let { AccountLine(it) }
    Body(stringResource(body))

    // Checking again is the only thing this screen can actually do for you, so it is the one
    // filled button on it; signing out is an escape hatch and is drawn as one.
    PrimaryButton(
        text = stringResource(R.string.registration_check_again),
        onClick = onRefresh,
        enabled = !busy
    )
    SignOutAction(busy = busy, onSignOut = onSignOut)

    if (reason == BlockReason.REVOKED || reason == BlockReason.REJECTED) {
        SupportContact()
    }
}

/** Always the lowest-emphasis action on a screen that has one: a bare label, never a slab. */
@Composable
private fun SignOutAction(busy: Boolean, onSignOut: () -> Unit) {
    TextButton(onClick = onSignOut, enabled = !busy) {
        Text(
            text = stringResource(R.string.registration_sign_out),
            style = MaterialTheme.typography.labelLarge,
            color = TextSecondary
        )
    }
}
