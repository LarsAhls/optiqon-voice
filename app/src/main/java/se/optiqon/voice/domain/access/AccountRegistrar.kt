package se.optiqon.voice.domain.access

/**
 * What the account screen needs from registration: one call, made once the user has confirmed
 * the name to register under, that claims the account and reports what the server said about it.
 *
 * Narrow for the same reason [AccessRefresher.RegistrationReader] is narrow — the screen should
 * not be able to reach Firestore, sign-in or the snapshot store — and because a view model that
 * can only be built with a live `FirebaseFirestore` is a view model whose behaviour is asserted
 * nowhere.
 */
interface AccountRegistrar {

    /**
     * @param displayName a name the user has actually seen and accepted. It is normalised and
     * bounds-checked here as well as in the UI, because "the screen validates it" is a claim
     * about one caller rather than about the contract.
     *
     * @return the server's answer, so the caller can tell "still pending" from "we could not
     * ask". Registration never grants anything: the rules accept only `pending` from a client.
     */
    suspend fun registerAndRefresh(displayName: String): RefreshOutcome
}
