package se.optiqon.voice.domain.access

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.optiqon.voice.data.preferences.PlainSecurePreferencesStore
import se.optiqon.voice.data.preferences.PreferencesDataStore
import se.optiqon.voice.data.storage.DeviceDataOwner
import se.optiqon.voice.data.storage.StorageRoot
import se.optiqon.voice.testing.FakeAuthGateway

/**
 * Signing out has to leave two things behind: no identity, and a device that asks its setup
 * questions again. The second one is the part that is easy to get wrong, because the next
 * process reads a different file from the one being written by the account on its way out.
 */
@RunWith(RobolectricTestRunner::class)
class AccountSignOutTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var deviceDataOwner: DeviceDataOwner
    private lateinit var auth: FakeAuthGateway

    @Before
    fun setUp() {
        deviceDataOwner = DeviceDataOwner(context)
        // Somebody owns the data already on the device, so a process with no identity moves to
        // the signed-out root — which is what makes the two files genuinely different files.
        deviceDataOwner.claimDefault("owner-uid")
        auth = FakeAuthGateway()
    }

    private fun preferencesOn(root: StorageRoot) =
        PreferencesDataStore(context, PlainSecurePreferencesStore(context), root)

    @Test
    fun `signing out asks for onboarding again, in the root left and the root opened next`() =
        runTest {
            val leaving = preferencesOn(StorageRoot("signouta"))
            leaving.setOnboardingComplete(true)
            auth.signIn("uid-a", "a@example.test")

            AccountSignOut(auth, leaving, deviceDataOwner).signOut()

            assertNull(auth.currentUid)
            assertFalse(leaving.preferences.first().onboardingComplete)
            assertFalse(preferencesOn(StorageRoot.SIGNED_OUT).preferences.first().onboardingComplete)
        }

    @Test
    fun `the answer is written before the identity is dropped`() = runTest {
        val leaving = preferencesOn(StorageRoot("signoutb"))
        leaving.setOnboardingComplete(true)
        auth.signIn("uid-b", "b@example.test")

        // The process may end the moment the identity moves, so anything this sign-out still
        // had to write would be lost. Reading the flag from inside the sign-out is the only
        // way to state that ordering as a test rather than as a hope.
        var onboardingCompleteWhenDropped: Boolean? = null
        val recording = object : AuthGateway by auth {
            override suspend fun signOut() {
                onboardingCompleteWhenDropped = leaving.preferences.first().onboardingComplete
                auth.signOut()
            }
        }

        AccountSignOut(recording, leaving, deviceDataOwner).signOut()

        assertFalse(onboardingCompleteWhenDropped!!)
    }

    @Test
    fun `the push registration is removed under the account being left, before it is signed out`() = runTest {
        val leaving = preferencesOn(StorageRoot("signoutc"))
        auth.signIn("uid-c", "c@example.test")
        val order = mutableListOf<String>()
        val recording = object : AuthGateway by auth {
            override suspend fun signOut() {
                order += "signOut"
                auth.signOut()
            }
        }

        AccountSignOut(recording, leaving, deviceDataOwner, AccountLeaving { uid -> order += "leaving:$uid" }).signOut()

        org.junit.Assert.assertEquals(listOf("leaving:uid-c", "signOut"), order)
        assertNull(auth.currentUid)
    }

    @Test
    fun `a failing push removal never keeps anyone signed in`() = runTest {
        val leaving = preferencesOn(StorageRoot("signoutd"))
        auth.signIn("uid-d", "d@example.test")

        AccountSignOut(auth, leaving, deviceDataOwner, AccountLeaving { error("offline") }).signOut()

        assertNull(auth.currentUid)
    }
}
