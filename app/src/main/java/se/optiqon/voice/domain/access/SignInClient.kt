package se.optiqon.voice.domain.access

import android.app.Activity

/**
 * The two ways into OPTIQON Voice. Both end with a verified email address and nothing else:
 * the app never shows a password form, so there is no password for it to mishandle.
 */
interface SignInClient {

    /**
     * True when Google sign-in can be offered: the build carries a `default_web_client_id`,
     * which only exists once the project has an OAuth web client and the matching
     * google-services.json was present at build time.
     */
    val googleAvailable: Boolean

    /**
     * True when a sign-in link can be *requested*: Firebase is initialised and the build knows
     * the project that hosts the link. Whether the project has the email-link provider switched
     * on cannot be read offline; a request against a project without it fails with
     * [EmailLinkNotEnabled], which the screen reports as such instead of hiding the field.
     */
    val emailLinkAvailable: Boolean

    /** True when at least one way in exists. */
    val isConfigured: Boolean get() = googleAvailable || emailLinkAvailable

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

    /** The project has not enabled email-link sign-in (Firebase `OPERATION_NOT_ALLOWED`). */
    class EmailLinkNotEnabled : Exception("Email-link sign-in is not enabled for this project")

    /**
     * The Google route failed and the device has no network.
     *
     * Its own type because the platform cannot be asked which it was. Credential Manager reports
     * every unfinished sheet as a cancellation — including the case where Play services opened
     * the sheet, could not reach Google's token endpoint, and closed it again. The raw message
     * for that is "Activity is cancelled by the user", which blames the one person who did
     * nothing wrong. Connectivity is therefore read at the failure and the verdict taken from
     * there, the same way [RefreshOutcome.NoNetwork] is decided in `RegistrationRepository`.
     */
    class Offline : Exception("No connection, so signing in could not be completed")

    /** The sign-in sheet closed without a credential while the device was online. */
    class Cancelled : Exception("Sign-in was not completed")
}
