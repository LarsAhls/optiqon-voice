package se.optiqon.voice.ui.access

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import se.optiqon.voice.R
import se.optiqon.voice.domain.access.AccessSession
import se.optiqon.voice.domain.access.AccessSessionState
import se.optiqon.voice.domain.access.AccountSignOut
import se.optiqon.voice.domain.access.AuthGateway
import se.optiqon.voice.domain.access.BlockReason
import se.optiqon.voice.domain.access.DegradedKind
import se.optiqon.voice.ui.common.SectionCard
import javax.inject.Inject

@HiltViewModel
class AccountSettingsViewModel @Inject constructor(
    private val accessSession: AccessSession,
    private val authGateway: AuthGateway,
    private val accountSignOut: AccountSignOut
) : ViewModel() {

    val state: StateFlow<AccessSessionState> = accessSession.state

    val email: String? get() = authGateway.currentEmail

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun refresh() = withBusy { accessSession.refreshNow() }

    fun signOut() = withBusy { accountSignOut.signOut() }

    private fun withBusy(block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            try {
                block()
            } finally {
                _busy.value = false
            }
        }
    }
}

/**
 * The rev. 11 account section in Settings: which account this is, what the server last said,
 * and the two things the user can do about it.
 *
 * The status line states what is known, never what is hoped: a stale verification says so
 * rather than showing a green tick, because the difference between "approved" and "approved as
 * far as we could last tell" is the whole point of the grace window.
 */
@Composable
fun AccountSettingsSection(
    modifier: Modifier = Modifier,
    viewModel: AccountSettingsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()

    val status = when (val current = state) {
        is AccessSessionState.Verifying -> R.string.account_status_verifying
        is AccessSessionState.SignedOut -> R.string.account_status_signed_out
        is AccessSessionState.Active -> R.string.account_status_active
        is AccessSessionState.Degraded -> when (current.kind) {
            DegradedKind.VERIFICATION_FAILED -> R.string.account_status_verification_failed
            DegradedKind.OFFLINE -> R.string.account_status_offline
        }
        is AccessSessionState.Blocked -> when (current.reason) {
            BlockReason.AWAITING_APPROVAL, BlockReason.NOT_REGISTERED -> R.string.account_status_pending
            BlockReason.REJECTED -> R.string.account_status_rejected
            BlockReason.REVOKED -> R.string.account_status_revoked
            BlockReason.GRACE_EXPIRED -> R.string.account_status_grace_expired
        }
    }

    SectionCard(
        title = stringResource(R.string.account_section_title),
        subtitle = viewModel.email,
        modifier = modifier
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(status), style = MaterialTheme.typography.bodyMedium)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = viewModel::refresh, enabled = !busy) {
                Text(stringResource(R.string.registration_check_again))
            }
            TextButton(onClick = viewModel::signOut, enabled = !busy) {
                Text(stringResource(R.string.registration_sign_out))
            }
        }
    }
}
