package se.optiqon.voice.data.access

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.tasks.await
import se.optiqon.voice.domain.access.AuthGateway
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseAuthGateway @Inject constructor(
    @ApplicationContext private val context: Context,
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
        flushAuthState()
    }

    /**
     * Waits for Firebase's own record of the sign-out to reach disk.
     *
     * Firebase remembers the signed-in user in shared preferences and writes changes to it
     * asynchronously. The caller ends the process moments later — a signed-out process must not
     * go on holding the account's files open — and a queued write does not survive that. The
     * result was a sign-out that appeared to do nothing: the next start read the user back and
     * signed straight in again.
     *
     * The flush is an empty synchronous commit on Firebase's own preference files. Preference
     * instances are shared per file within a process and their writes are serialised, so a
     * commit cannot return until whatever was already queued for that file has been written.
     * Nothing is added to or removed from the files here.
     *
     * Best effort by design: if Firebase ever renames these files, no file matches, nothing is
     * flushed, and the behaviour is the one this app had before — never worse.
     */
    private fun flushAuthState() {
        val sharedPrefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        sharedPrefsDir.listFiles().orEmpty()
            .map { it.name }
            .filter { it.startsWith(FIREBASE_AUTH_PREFS_PREFIX) && it.endsWith(".xml") }
            .forEach { fileName ->
                val name = fileName.removeSuffix(".xml")
                context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().commit()
            }
    }

    private companion object {
        const val FIREBASE_AUTH_PREFS_PREFIX = "com.google.firebase.auth.api."
    }
}
