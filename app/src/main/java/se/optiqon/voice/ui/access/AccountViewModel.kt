package se.optiqon.voice.ui.access

import android.app.Activity
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import se.optiqon.voice.R
import se.optiqon.voice.data.access.PendingEmailStore
import se.optiqon.voice.domain.access.AccessDecision
import se.optiqon.voice.domain.access.AccessRepository
import se.optiqon.voice.domain.access.AccessSession
import se.optiqon.voice.domain.access.AccountRegistrar
import se.optiqon.voice.domain.access.AuthGateway
import se.optiqon.voice.domain.access.BlockReason
import se.optiqon.voice.domain.access.EmailLinkRelay
import se.optiqon.voice.domain.access.RefreshOutcome
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
    /**
     * The account is approved, but has not yet said what should happen to the data already on
     * this device. Asked once, before the app opens, because opening it either way first would
     * be the guess this screen exists to avoid.
     */
    data class ClaimChoice(val email: String?) : AccountUiState
    data object Approved : AccountUiState
}

@HiltViewModel
class AccountViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accessRepository: AccessRepository,
    private val accountRegistrar: AccountRegistrar,
    private val accessSession: AccessSession,
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

    /**
     * Bumped when the claim question is answered.
     *
     * [AccessSession.needsDataClaimDecision] reads persisted state, which no flow observes, so
     * without this the screen would keep asking a question the user has already answered.
     */
    private val _claimAnswers = MutableStateFlow(0)

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
            _message.value = context.getString(R.string.registration_email_needed)
        }
    }

    /**
     * The uid is taken from the flow, not read imperatively inside the combine.
     *
     * Signing in as an account the server has never heard of leaves the decision exactly where
     * it was — `Blocked(NOT_REGISTERED)` before and after — so nothing re-emits, and a screen
     * that read `currentUid` at combine time would still be showing "sign in" to somebody who
     * just did.
     */
    val state: StateFlow<AccountUiState> = combine(
        accessRepository.decision,
        authGateway.uidChanges(),
        _linkAwaitingEmail,
        _claimAnswers
    ) { decision, uid, pendingLink, _ ->
        when {
            uid == null ->
                AccountUiState.SignedOut(signInClient.isConfigured, pendingLink != null)
            decision is AccessDecision.Allowed ->
                if (accessSession.needsDataClaimDecision()) {
                    AccountUiState.ClaimChoice(authGateway.currentEmail)
                } else {
                    AccountUiState.Approved
                }
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
                onSuccess = {
                    _message.value = context.getString(R.string.registration_email_sent, email)
                },
                onFailure = { _message.value = it.message }
            )
        }
    }

    /**
     * Re-reads the server verdict; the only way the app learns it has been approved.
     *
     * Routed through the session rather than straight at the repository so that this button
     * shares the single-flight and the identity checks with every other trigger. Calling the
     * reader directly would be a second, unsynchronised way of recording a verdict.
     */
    fun refresh() = withBusy {
        // Reporting the outcome is the whole point of the button. A check that quietly fails
        // leaves the screen saying "waiting for approval", which is a claim about the server
        // that the app has no basis for — and the user presses it again, and again.
        when (accessSession.refreshNow()) {
            is RefreshOutcome.NoNetwork ->
                _message.value = context.getString(R.string.registration_check_offline)
            is RefreshOutcome.Failed ->
                _message.value = context.getString(R.string.registration_check_failed)
            is RefreshOutcome.NoAccount, is RefreshOutcome.Confirmed, RefreshOutcome.Throttled ->
                Unit
        }
    }

    /**
     * @param claim true to open this device's existing data under the signed-in account, false
     * to start with an empty set of files. Neither answer copies, moves, deletes or uploads
     * anything.
     *
     * Starting empty resolves to a different storage root, which the session applies by ending
     * the process; this method may therefore not return.
     */
    fun answerDataClaim(claim: Boolean) {
        accessSession.answerDataClaim(claim)
        _claimAnswers.value += 1
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
        onSuccess = { accountRegistrar.registerAndRefresh(displayName = null) },
        onFailure = { _message.value = it.message }
    )
}
