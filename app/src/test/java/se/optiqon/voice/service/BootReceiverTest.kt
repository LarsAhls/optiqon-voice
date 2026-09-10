package se.optiqon.voice.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.optiqon.voice.data.preferences.PreferencesDataStore
import se.optiqon.voice.data.preferences.testPreferencesDataStore
import se.optiqon.voice.domain.access.AccountStatus
import se.optiqon.voice.testing.AccessFixture

/**
 * A reboot is one of the ways an account state can be quietly forgotten.
 *
 * Nothing about a restart re-asks the server, and on a cold boot there is often no network to
 * ask over, so the bubble would otherwise come back for a revoked account and keep working
 * until some later refresh happened to succeed. The stored verdict is read from disk instead,
 * and every "does not start" here has a control proving the same fixture does start.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BootReceiverTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private suspend fun preferencesWithBootStart(
        context: Context,
        startOnBoot: Boolean
    ): PreferencesDataStore = testPreferencesDataStore(context).apply {
        updateGeneralSettings(
            autoClipboard = false,
            vibrateOnRecord = false,
            pauseOtherAudio = false,
            silenceThresholdMs = 2_000L,
            historyEnabled = true,
            keepStatsWithoutHistory = false,
            historyRetentionLimit = 100,
            startOnBoot = startOnBoot
        )
    }

    /** Signed in and stamped with [status], the way a device is when it reboots. */
    private suspend fun TestScope.access(status: AccountStatus?): AccessFixture {
        val access = AccessFixture(context, backgroundScope)
        if (status != null) {
            access.signIn("uid-a")
            access.recordServerVerdict(status)
        }
        return access
    }

    private suspend fun TestScope.startsOnBoot(
        status: AccountStatus?,
        startOnBoot: Boolean = true
    ): Boolean = shouldStartBubbleOnBoot(
        preferencesDataStore = preferencesWithBootStart(context, startOnBoot),
        accessRepository = access(status).repository
    )

    @Test
    fun `an approved account gets its bubble back`() = runTest {
        assertTrue(startsOnBoot(AccountStatus.APPROVED))
    }

    @Test
    fun `a revoked account does not`() = runTest {
        assertFalse(startsOnBoot(AccountStatus.REVOKED))
    }

    @Test
    fun `nor a rejected one`() = runTest {
        assertFalse(startsOnBoot(AccountStatus.REJECTED))
    }

    @Test
    fun `nor one still waiting for approval`() = runTest {
        assertFalse(startsOnBoot(AccountStatus.PENDING))
    }

    @Test
    fun `nor one that was never signed in at all`() = runTest {
        assertFalse(startsOnBoot(status = null))
    }

    /**
     * Grace is the case a reboot is most likely to hit: the device has been off, nobody has
     * been able to confirm anything, and the stored approval has run out meanwhile.
     */
    @Test
    fun `an approval whose grace has run out does not come back either`() = runTest {
        val preferences = preferencesWithBootStart(context, startOnBoot = true)
        val access = access(AccountStatus.APPROVED)
        assertTrue(
            "control: it starts while the grace is still valid",
            shouldStartBubbleOnBoot(preferences, access.repository)
        )

        access.clock.advance(73L * 60L * 60L * 1000L)

        assertFalse(shouldStartBubbleOnBoot(preferences, access.repository))
    }

    /** The user's own setting still wins; a permitted account is not a started one. */
    @Test
    fun `start on boot turned off keeps the bubble off for an approved account`() = runTest {
        assertFalse(startsOnBoot(AccountStatus.APPROVED, startOnBoot = false))
    }
}
