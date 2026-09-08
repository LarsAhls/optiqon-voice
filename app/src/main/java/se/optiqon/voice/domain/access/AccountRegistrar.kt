package se.optiqon.voice.domain.access

/**
 * What the account screen needs from registration: one call, made right after a successful
 * sign-in, that claims the account and reports what the server said about it.
 *
 * Narrow for the same reason [AccessRefresher.RegistrationReader] is narrow — the screen should
 * not be able to reach Firestore, sign-in or the snapshot store — and because a view model that
 * can only be built with a live `FirebaseFirestore` is a view model whose behaviour is asserted
 * nowhere.
 */
interface AccountRegistrar {

    /**
     * @return the server's answer, so the caller can tell "still pending" from "we could not
     * ask". Registration never grants anything: the rules accept only `pending` from a client.
     */
    suspend fun registerAndRefresh(displayName: String?): RefreshOutcome
}
