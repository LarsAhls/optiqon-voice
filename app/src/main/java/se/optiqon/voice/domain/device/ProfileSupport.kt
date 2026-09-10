package se.optiqon.voice.domain.device

import android.content.Context
import android.os.Build
import android.os.UserManager
import androidx.annotation.StringRes
import se.optiqon.voice.R

/** Whether this copy of the app can dictate at all, given the profile it was installed into. */
sealed interface ProfileVerdict {
    data object Supported : ProfileVerdict

    /** The app runs where dictation cannot work, and must say so instead of pretending. */
    data class Unsupported(
        @StringRes val titleRes: Int,
        @StringRes val bodyRes: Int
    ) : ProfileVerdict
}

/**
 * Refuses a managed (work) profile (F18).
 *
 * Android binds an accessibility service only for the user that is *current*, and a managed
 * profile never is. The work copy's service therefore shows as enabled in settings and is never
 * started, so the personal copy is what hears the microphone, spends the personal key and writes
 * the personal history — no matter which profile the person believes they are dictating into.
 * Silence here reads as a bug in the work copy; it is worse than that, so it is named.
 *
 * `isManagedProfile()` for the calling user needs no permission but arrived in API 30. Below that
 * the question cannot be asked without a device-admin component, so the answer is [Supported] and
 * behaviour is exactly what it is today.
 */
fun profileVerdictOf(context: Context): ProfileVerdict {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return ProfileVerdict.Supported
    val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
        ?: return ProfileVerdict.Supported
    if (!userManager.isManagedProfile) return ProfileVerdict.Supported
    return ProfileVerdict.Unsupported(
        titleRes = R.string.unsupported_profile_title,
        bodyRes = R.string.unsupported_profile_body
    )
}
