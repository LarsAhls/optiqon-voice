package se.optiqon.voice.domain.access

import kotlinx.coroutines.flow.Flow

/**
 * Everything the access layer needs from Firebase Auth, and nothing more.
 *
 * Keeping it this narrow is what lets the gate, the outbox policy and the account-switch tests
 * run as plain JVM tests with a fake. It also keeps one fact visible: a signed-in account is
 * an identity, never a permission. [AccessRepository] decides what the identity may do.
 */
interface AuthGateway {

    /** The uid signed in right now, or null. */
    val currentUid: String?

    /** Emits on every sign-in, sign-out and account switch, starting with the current value. */
    fun uidChanges(): Flow<String?>

    val currentEmail: String?

    val isEmailVerified: Boolean

    /** Re-reads the account from the server, so a freshly verified email is noticed. */
    suspend fun reload()

    suspend fun signOut()
}
