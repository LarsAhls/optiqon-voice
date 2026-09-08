package se.optiqon.voice.data.access

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.tasks.await
import se.optiqon.voice.domain.access.AuthGateway
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseAuthGateway @Inject constructor(
    private val auth: FirebaseAuth
) : AuthGateway {

    override val currentUid: String? get() = auth.currentUser?.uid

    override val currentEmail: String? get() = auth.currentUser?.email

    override val currentDisplayName: String? get() = auth.currentUser?.displayName

    override val isEmailVerified: Boolean get() = auth.currentUser?.isEmailVerified == true

    override fun uidChanges(): Flow<String?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser?.uid) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }.distinctUntilChanged()

    override suspend fun reload() {
        auth.currentUser?.reload()?.await()
    }

    /**
     * Signing out drops the identity and nothing else. No local original data is deleted here,
     * and `clearPersistence()` is never called: unsent work belongs to the account that created
     * it and waits for that account to come back.
     */
    override suspend fun signOut() {
        auth.signOut()
    }
}
