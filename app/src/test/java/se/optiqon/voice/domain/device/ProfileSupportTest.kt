package se.optiqon.voice.domain.device

import android.content.Context
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import se.optiqon.voice.R

/**
 * A work profile is not a second account, and must not be offered as one (F18).
 *
 * Android binds an accessibility service only for the user that is current, and a managed
 * profile is never current. The work copy's service is therefore enabled in settings and never
 * started, so every dictation is heard by the personal copy: its microphone, its key, its
 * history. Nothing on screen says so. This pins the detection and the sentence together —
 * finding the profile is only useful if the person is told.
 */
@RunWith(RobolectricTestRunner::class)
class ProfileSupportTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager

    @Test
    fun `an ordinary profile is left alone`() {
        assertEquals(ProfileVerdict.Supported, profileVerdictOf(context))
    }

    @Test
    fun `a work profile is stopped with a sentence that names the personal copy`() {
        shadowOf(userManager).setManagedProfile(true)

        val verdict = profileVerdictOf(context)

        assertTrue("a managed profile must not reach the app", verdict is ProfileVerdict.Unsupported)
        val blocked = verdict as ProfileVerdict.Unsupported
        assertEquals(R.string.unsupported_profile_title, blocked.titleRes)
        assertEquals(R.string.unsupported_profile_body, blocked.bodyRes)
        assertTrue(
            "the body has to explain where the dictation actually goes",
            context.getString(blocked.bodyRes).contains("personal", ignoreCase = true)
        )
    }
}
