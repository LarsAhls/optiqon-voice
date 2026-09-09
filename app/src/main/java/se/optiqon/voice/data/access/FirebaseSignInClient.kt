package se.optiqon.voice.data.access

import android.app.Activity
import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.ActionCodeSettings
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.GoogleAuthProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import se.optiqon.voice.BuildConfig
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
     * checkout without the file still compiles — the build then simply has no Google route,
     * which [googleAvailable] reports honestly instead of crashing at the first tap.
     *
     * The id is generated only when the project has an OAuth web client, i.e. after Google
     * sign-in has been enabled in the console and a fresh google-services.json downloaded. A
     * file from before that step yields a build with email link only.
     */
    private val webClientId: String? by lazy {
        val id = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        if (id == 0) null else context.getString(id)
    }

    override val googleAvailable: Boolean get() = webClientId != null

    /**
     * A link can be requested when Firebase initialised itself from google-services.json (no
     * file, no `FirebaseApp`) and the build knows which project hosts the link. Whether the
     * provider is enabled is a server-side fact, learned from the request itself.
     */
    override val emailLinkAvailable: Boolean
        get() = BuildConfig.FIREBASE_PROJECT_ID.isNotEmpty() && FirebaseApp.getApps(context).isNotEmpty()

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

    override suspend fun sendEmailLink(email: String): Result<Unit> {
        if (!emailLinkAvailable) {
            return Result.failure(IllegalStateException("This build has no Firebase configuration"))
        }
        return runCatching {
            val settings = ActionCodeSettings.newBuilder()
                .setUrl(emailLinkContinueUrl(BuildConfig.FIREBASE_PROJECT_ID))
                .setHandleCodeInApp(true)
                .setAndroidPackageName(context.packageName, true, null)
                .build()
            auth.sendSignInLinkToEmail(email, settings).await()
            pendingEmailStore.remember(email)
            Unit
        }.recoverCatching { failure ->
            // The one failure the screen must name rather than echo: the project exists, the
            // build is fine, and the provider is simply not switched on yet.
            if (failure is FirebaseAuthException && failure.errorCode == ERROR_OPERATION_NOT_ALLOWED) {
                throw SignInClient.EmailLinkNotEnabled()
            }
            throw failure
        }
    }

    override suspend fun completeEmailLink(link: String, email: String): Result<Unit> = runCatching {
        auth.signInWithEmailLink(email, link).await()
        pendingEmailStore.clear()
        Unit
    }

    override fun isEmailLink(link: String): Boolean = auth.isSignInWithEmailLink(link)

    companion object {
        private const val ERROR_OPERATION_NOT_ALLOWED = "ERROR_OPERATION_NOT_ALLOWED"

        /**
         * Where the sign-in link continues after Firebase has handled it: the project's own
         * Hosting site, which also serves the `assetlinks.json` that lets the link open the app.
         *
         * Derived from the same `project_id` that the manifest's `firebaseAuthHost` placeholder
         * is built from (see app/build.gradle.kts), so the two can never name different
         * projects. No `setLinkDomain`: that call is for custom Hosting domains, and the default
         * `<project_id>.firebaseapp.com` link domain is selected automatically.
         */
        fun emailLinkContinueUrl(projectId: String): String = "https://$projectId.web.app/signin"
    }
}
