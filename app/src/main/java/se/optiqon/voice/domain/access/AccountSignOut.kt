package se.optiqon.voice.domain.access

import se.optiqon.voice.data.preferences.PreferencesDataStore
import se.optiqon.voice.data.storage.DeviceDataOwner
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Signing out, as the one ordered operation it has to be.
 *
 * Two things have to be true afterwards and neither is true by accident:
 *
 * 1. **The account is really gone.** Dropping the identity moves the process to a different
 *    storage root, and the session ends the process to get there. Anything the sign-out still
 *    had to write would go with it, which is why [AuthGateway.signOut] is asked to be durable
 *    before it returns and why it is called last here.
 * 2. **The next start opens onboarding.** With no account there is nothing configured to come
 *    back to, and the first-run flow now begins with the account step — so it is both the
 *    honest screen and the one that asks the next question. The answer is written into the
 *    root being left *and* into the root the next process will open, because they are separate
 *    files and only the second one will be read.
 *
 * Nothing here deletes anything. The account's database, settings, keys and audio stay exactly
 * where they are and are opened again, untouched, the next time it signs in.
 */
@Singleton
class AccountSignOut @Inject constructor(
    private val authGateway: AuthGateway,
    private val preferencesDataStore: PreferencesDataStore,
    private val deviceDataOwner: DeviceDataOwner
) {

    suspend fun signOut() {
        preferencesDataStore.setOnboardingComplete(false)
        preferencesDataStore.setOnboardingComplete(false, deviceDataOwner.rootFor(null))
        authGateway.signOut()
    }
}
