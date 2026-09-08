package se.optiqon.voice.ui.access

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import se.optiqon.voice.data.access.PendingEmailStore
import se.optiqon.voice.data.access.RegistrationRepository
import se.optiqon.voice.domain.access.AccessDecision
import se.optiqon.voice.domain.access.AccessRepository
import se.optiqon.voice.domain.access.AuthGateway
import se.optiqon.voice.domain.access.BlockReason
import se.optiqon.voice.domain.access.EmailLinkRelay
import se.optiqon.voice.domain.access.SignInClient
import javax.inject.Inject

/** What the account screens need to draw themselves. */
sealed interface AccountUiState {
    data object Loading : AccountUiState
    /**
     * @param completingLink a sign-in link is open and only needs the address it was sent to,
     * which happens when the link is followed on a device other than the one that asked for it.
     */
    data class SignedOut(val configured: Boolean, val completingLink: Boolean) : AccountUiState
    data class Waiting(val email: String?, val reason: BlockReason) : AccountUiState
    data object Approved : AccountUiState
}

@HiltViewModel
class AccountViewModel @Inject constructor(
    private val accessRepository: AccessRepository,
    private val registrationRepository: RegistrationRepository,
    private val authGateway: AuthGateway,
    private val signInClient: SignInClient,
    private val pendingEmailStore: PendingEmailStore,
    private val emailLinkRelay: EmailLinkRelay
) : ViewModel() {

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /**
     * A sign-in link waiting for the address it was sent to.
     *
     * The address is never taken from the link itself. A link forwarded to somebody else would
     * otherwise sign that person in as its original recipient, which is the one thing the email
     * route must not allow.
     */
    private val _linkAwaitingEmail = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch {
            emailLinkRelay.link.collect { if (it != null) onIncomingLink() }
        }
    }

    private suspend fun onIncomingLink() {
        val link = emailLinkRelay.consume() ?: return
        val remembered = pendingEmailStore.pending()
        if (remembered != null) {
            _busy.value = true
            try {
                signInClient.completeEmailLink(link, remembered).thenRegister()
            } finally {
                _busy.value = false
            }
        } else {
            _linkAwaitingEmail.value = link
            _message.value = "Enter the address this link was sent to."
        }
    }

    val state: StateFlow<AccountUiState> = combine(
        accessRepository.decision,
        _linkAwaitingEmail
    ) { decision, pendingLink ->
        when {
            authGateway.currentUid == null ->
                AccountUiState.SignedOut(signInClient.isConfigured, pendingLink != null)
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

    /**
     * Sends a sign-in link, or completes the one already open with the address just typed.
     *
     * One field, two jobs, because from the tester's side it is one question: which address is
     * this? The screen says which of the two is about to happen.
     */
    fun submitEmail(email: String) = withBusy {
        val pendingLink = _linkAwaitingEmail.value
        if (pendingLink != null) {
            _linkAwaitingEmail.value = null
            signInClient.completeEmailLink(pendingLink, email).thenRegister()
        } else {
            signInClient.sendEmailLink(email).fold(
                onSuccess = { _message.value = "Sign-in link sent to $email" },
                onFailure = { _message.value = it.message }
            )
        }
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
