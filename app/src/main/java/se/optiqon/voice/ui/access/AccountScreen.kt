package se.optiqon.voice.ui.access

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.optiqon.voice.R
import se.optiqon.voice.domain.access.BlockReason

/**
 * The screen every unapproved account sees. Registration is required for the whole app, so
 * this stands in front of the main graph rather than beside it.
 */
@Composable
fun AccountScreen(viewModel: AccountViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val activity = LocalContext.current as? Activity

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when (val current = state) {
            is AccountUiState.Loading, is AccountUiState.Approved -> CircularProgressIndicator()

            is AccountUiState.SignedOut -> SignedOut(
                configured = current.configured,
                busy = busy,
                onGoogle = { activity?.let(viewModel::signInWithGoogle) },
                onEmail = viewModel::sendEmailLink
            )

            is AccountUiState.Waiting -> Waiting(
                email = current.email,
                reason = current.reason,
                busy = busy,
                onRefresh = viewModel::refresh,
                onSignOut = viewModel::signOut
            )
        }

        message?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = viewModel::consumeMessage) { Text("OK") }
        }
    }
}

@Composable
private fun SignedOut(
    configured: Boolean,
    busy: Boolean,
    onGoogle: () -> Unit,
    onEmail: (String) -> Unit
) {
    var email by rememberSaveable { mutableStateOf("") }

    Text(stringResource(R.string.registration_title), style = MaterialTheme.typography.headlineSmall)

    if (!configured) {
        // Being told the build cannot sign in beats a button that silently does nothing.
        Text(
            "This build has no Firebase configuration, so signing in is unavailable.",
            style = MaterialTheme.typography.bodyMedium
        )
        return
    }

    Button(onClick = onGoogle, enabled = !busy) {
        Text(stringResource(R.string.registration_google))
    }

    OutlinedTextField(
        value = email,
        onValueChange = { email = it },
        singleLine = true,
        label = { Text("Email") }
    )

    TextButton(onClick = { onEmail(email.trim()) }, enabled = !busy && email.contains('@')) {
        Text(stringResource(R.string.registration_email))
    }
}

@Composable
private fun Waiting(
    email: String?,
    reason: BlockReason,
    busy: Boolean,
    onRefresh: () -> Unit,
    onSignOut: () -> Unit
) {
    val body = when (reason) {
        BlockReason.AWAITING_APPROVAL, BlockReason.NOT_REGISTERED ->
            stringResource(R.string.registration_pending_body)
        BlockReason.REJECTED -> stringResource(R.string.access_blocked_rejected)
        BlockReason.REVOKED -> stringResource(R.string.access_blocked_revoked)
        BlockReason.GRACE_EXPIRED -> stringResource(R.string.access_blocked_grace_expired)
    }

    Text(stringResource(R.string.registration_pending_title), style = MaterialTheme.typography.headlineSmall)
    email?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    Text(body, style = MaterialTheme.typography.bodyMedium)

    Button(onClick = onRefresh, enabled = !busy) {
        Text(stringResource(R.string.registration_check_again))
    }
    TextButton(onClick = onSignOut, enabled = !busy) {
        Text(stringResource(R.string.registration_sign_out))
    }
}
