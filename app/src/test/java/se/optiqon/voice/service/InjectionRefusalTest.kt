package se.optiqon.voice.service

import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ServiceController
import se.optiqon.voice.data.preferences.testPreferencesDataStore
import se.optiqon.voice.domain.access.AccessGrant
import se.optiqon.voice.domain.access.AccessLease
import se.optiqon.voice.domain.access.AccessRevokedException
import se.optiqon.voice.domain.access.AccountStatus
import se.optiqon.voice.testing.AccessFixture

/**
 * The last boundary, and the only one where being late is unrecoverable.
 *
 * Text handed to another app's input connection cannot be taken back, so the accepted race is
 * bounded to exactly one already-committed call. What must not follow from that is the wider
 * claim — that an injection which has *not* yet happened may as well be completed because the
 * work leading up to it was authorised. These tests hold that line: at the moment of the write,
 * the question is asked again, and a revoked account gets no text anywhere.
 *
 * The clipboard is treated as an effect on equal terms. A dictation left on the clipboard is
 * readable by every app on the device; refusing the injection and then quietly copying it would
 * be the same leak by a politer route.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class InjectionRefusalTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private var injector: ServiceController<TextInjectorService>? = null

    @After
    fun tearDown() {
        // `onDestroy` is what clears the static instance, so the next test does not inherit it.
        injector?.destroy()
        scope.cancel()
    }

    private fun bridge() = TextInjectionBridge(context, testPreferencesDataStore(context))

    private suspend fun approvedLease(): Pair<AccessFixture, AccessLease> {
        val f = AccessFixture(context, scope)
        f.signIn("uid-a")
        f.recordServerVerdict(AccountStatus.APPROVED)
        return f to (f.guard.authorize() as AccessGrant.Granted).lease
    }

    @Test
    fun `a revoked account gets no text on the clipboard`() = runTest {
        val (f, lease) = approvedLease()
        f.recordServerVerdict(AccountStatus.REVOKED)

        refusal { bridge().inject("hej", lease) }

        assertNull("a refused dictation must not be left where any app can read it", clipboard.primaryClip)
    }

    /**
     * The control. Every assertion above is about something *not* being on the clipboard, which
     * is also what a bridge that never writes to the clipboard at all would produce.
     */
    @Test
    fun `a valid lease does reach the clipboard`() = runTest {
        val (_, lease) = approvedLease()

        bridge().inject("hej", lease)

        assertEquals("hej", clipboard.primaryClip?.getItemAt(0)?.text)
    }

    @Test
    fun `the injector path is refused before it writes`() = runTest {
        // With the accessibility service present, `inject` takes the branch that talks to the
        // focused app rather than the clipboard fallback — the branch that matters most.
        // `create()` alone does not connect the service, and an unconnected one would silently
        // send this test down the clipboard path it is meant to avoid, so the branch is
        // asserted rather than assumed.
        injector = Robolectric.buildService(TextInjectorService::class.java).create()
        // The callback that publishes the static instance is protected and has no Robolectric
        // controller step, so it is invoked directly. The assertion below is what makes this
        // safe: if the reflection ever stops working, the test fails instead of quietly
        // testing the clipboard path twice.
        TextInjectorService::class.java.getDeclaredMethod("onServiceConnected").apply {
            isAccessible = true
        }.invoke(injector!!.get())
        val bridge = bridge()
        assertTrue("this test must run against the injector branch", bridge.isAccessibilityServiceActive)

        val (f, lease) = approvedLease()
        f.recordServerVerdict(AccountStatus.REVOKED)

        refusal { bridge.inject("hej", lease) }

        assertNull(clipboard.primaryClip)
    }

    @Test
    fun `signing out is enough on its own`() = runTest {
        val (f, lease) = approvedLease()

        // No server verdict at all: the account that holds this lease simply is not the one
        // acting any more, which is a different question from whether it is still approved.
        f.signOut()

        refusal { bridge().inject("hej", lease) }

        assertNull(clipboard.primaryClip)
    }

    private suspend fun refusal(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: AccessRevokedException) {
            return
        }
        fail("expected the injection to be refused")
    }
}
