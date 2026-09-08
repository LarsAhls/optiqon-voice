package se.optiqon.voice.ui.access

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import se.optiqon.voice.data.access.RegistrationRepository
import se.optiqon.voice.domain.access.AccessDecision
import se.optiqon.voice.domain.access.AccessRepository
import se.optiqon.voice.domain.access.AuthGateway
import se.optiqon.voice.domain.access.BlockReason
import se.optiqon.voice.domain.access.SignInClient
import javax.inject.Inject

/** What the account screens need to draw themselves. */
sealed interface AccountUiState {
    data object Loading : AccountUiState
    data class SignedOut(val configured: Boolean) : AccountUiState
    data class Waiting(val email: String?, val reason: BlockReason) : AccountUiState
    data object Approved : AccountUiState
}

@HiltViewModel
class AccountViewModel @Inject constructor(
    private val accessRepository: AccessRepository,
    private val registrationRepository: RegistrationRepository,
    private val authGateway: AuthGateway,
    private val signInClient: SignInClient
) : ViewModel() {

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val state: StateFlow<AccountUiState> = accessRepository.decision
        .map { decision ->
            when {
                authGateway.currentUid == null -> AccountUiState.SignedOut(signInClient.isConfigured)
                decision is AccessDecision.Allowed -> AccountUiState.Approved
                decision is AccessDecision.Blocked ->
                    AccountUiState.Waiting(authGateway.currentEmail, decision.reason)
                else -> AccountUiState.Loading
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountUiState.Loading)

    fun signInWithGoogle(activity: Activity) = run {
        signInClient.signInWithGoogle(activity).thenRegister()
    }

    fun sendEmailLink(email: String) = withBusy {
        signInClient.sendEmailLink(email).fold(
            onSuccess = { _message.value = "Sign-in link sent to $email" },
            onFailure = { _message.value = it.message }
        )
    }

    fun completeEmailLink(link: String, email: String) = run {
        signInClient.completeEmailLink(link, email).thenRegister()
    }

    /** Re-reads the server verdict; the only way the app learns it has been approved. */
    fun refresh() = withBusy {
        registrationRepository.refresh()
    }

    fun signOut() = withBusy {
        authGateway.signOut()
    }

    fun consumeMessage() {
        _message.value = null
    }

    private fun run(block: suspend () -> Unit) = withBusy(block)

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

    private suspend fun Result<Unit>.thenRegister() = fold(
        onSuccess = { registrationRepository.registerAndRefresh(displayName = null) },
        onFailure = { _message.value = it.message }
    )
}
