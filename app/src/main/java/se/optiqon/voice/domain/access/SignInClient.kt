package se.optiqon.voice.domain.access

import android.app.Activity

/**
 * The two ways into OPTIQON Voice. Both end with a verified email address and nothing else:
 * the app never shows a password form, so there is no password for it to mishandle.
 */
interface SignInClient {

    /** True when this build actually has Firebase configuration to sign in against. */
    val isConfigured: Boolean

    /** Google via Credential Manager. The Google account's email is verified by Google. */
    suspend fun signInWithGoogle(activity: Activity): Result<Unit>

    /**
     * Sends a one-time sign-in link. The address is remembered locally so the link can be
     * completed on this device; a link opened on a different device asks for the address again
     * rather than trusting whatever the link carries.
     */
    suspend fun sendEmailLink(email: String): Result<Unit>

    /** Completes a sign-in link the user opened. */
    suspend fun completeEmailLink(link: String, email: String): Result<Unit>

    fun isEmailLink(link: String): Boolean
}
