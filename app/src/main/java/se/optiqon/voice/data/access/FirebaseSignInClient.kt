package se.optiqon.voice.data.access

import android.app.Activity
import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.ActionCodeSettings
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import se.optiqon.voice.domain.access.SignInClient
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseSignInClient @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: FirebaseAuth,
    private val pendingEmailStore: PendingEmailStore
) : SignInClient {

    /**
     * `default_web_client_id` is generated from google-services.json, which is downloaded per
     * machine and never committed. It is looked up by name rather than through `R` so that a
     * checkout without the file still compiles — the build then simply has no way to sign in,
     * which [isConfigured] reports honestly instead of crashing at the first tap.
     */
    private val webClientId: String? by lazy {
        val id = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        if (id == 0) null else context.getString(id)
    }

    override val isConfigured: Boolean get() = webClientId != null

    override suspend fun signInWithGoogle(activity: Activity): Result<Unit> {
        val clientId = webClientId
            ?: return Result.failure(IllegalStateException("This build has no Firebase configuration"))
        return runCatching {
            val option = GetSignInWithGoogleOption.Builder(clientId).build()
            val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
            val response = CredentialManager.create(activity).getCredential(activity, request)
            val googleCredential = GoogleIdTokenCredential.createFrom(response.credential.data)
            val firebaseCredential = GoogleAuthProvider.getCredential(googleCredential.idToken, null)
            auth.signInWithCredential(firebaseCredential).await()
            Unit
        }
    }

    override suspend fun sendEmailLink(email: String): Result<Unit> = runCatching {
        val settings = ActionCodeSettings.newBuilder()
            .setUrl(EMAIL_LINK_CONTINUE_URL)
            .setHandleCodeInApp(true)
            .setAndroidPackageName(context.packageName, true, null)
            .build()
        auth.sendSignInLinkToEmail(email, settings).await()
        pendingEmailStore.remember(email)
        Unit
    }

    override suspend fun completeEmailLink(link: String, email: String): Result<Unit> = runCatching {
        auth.signInWithEmailLink(email, link).await()
        pendingEmailStore.clear()
        Unit
    }

    override fun isEmailLink(link: String): Boolean = auth.isSignInWithEmailLink(link)

    private companion object {
        /**
         * The domain that hosts the sign-in link. Verifying the App Link for this domain is a
         * console action and is deliberately out of scope for Mission 1, so this constant is
         * the only place a later mission has to look.
         */
        const val EMAIL_LINK_CONTINUE_URL = "https://voice.optiqon.se/signin"
    }
}
