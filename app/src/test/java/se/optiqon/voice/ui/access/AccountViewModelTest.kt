package se.optiqon.voice.ui.access

import android.app.Activity
import android.content.Context
import android.os.Looper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import se.optiqon.voice.R
import se.optiqon.voice.data.access.PendingEmailStore
import se.optiqon.voice.data.preferences.testPreferencesDataStore
import se.optiqon.voice.data.storage.DeviceDataOwner
import se.optiqon.voice.data.storage.ProcessRestarter
import se.optiqon.voice.data.storage.StorageOwnership
import se.optiqon.voice.domain.access.AccessSession
import se.optiqon.voice.domain.access.AccountRegistrar
import se.optiqon.voice.domain.access.AccountSignOut
import se.optiqon.voice.domain.access.AccountStatus
import se.optiqon.voice.domain.access.BlockReason
import se.optiqon.voice.domain.access.EmailLinkRelay
import se.optiqon.voice.domain.access.RefreshOutcome
import se.optiqon.voice.domain.access.SignInClient
import se.optiqon.voice.domain.transcription.NetworkMonitor
import se.optiqon.voice.testing.AccessFixture
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * The account screen's own decisions, as opposed to the gate's.
 *
 * Three of them are worth pinning. The four block reasons must reach the screen as four
 * distinct states, because before this they all rendered as "waiting for approval" — which
 * tells a rejected tester to keep waiting for something that is not coming. "Check again" must
 * say when it could not ask, for the same reason: a button that quietly fails leaves the screen
 * making a claim about the server that nobody made. And a sign-in link opened on a device that
 * did not request it must not be completable, because the address would then come from the
 * link, and a forwarded link would sign its new reader in as its original recipient.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AccountViewModelTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** The real store on a private file, so test order cannot decide an outcome. */
    private class IsolatedPendingEmailStore(
        context: Context,
        scope: kotlinx.coroutines.CoroutineScope
    ) : PendingEmailStore(context) {
        override val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) {
            File(context.cacheDir, "pending-email-${COUNTER.incrementAndGet()}.preferences_pb")
        }

        private companion object {
            val COUNTER = AtomicInteger()
        }
    }

    /**
     * Records what it was asked to do rather than doing it. The address it is handed is the
     * assertion: it is the one thing that must never come out of the link.
     */
    private class FakeSignInClient : SignInClient {
        override var googleAvailable: Boolean = true
        override var emailLinkAvailable: Boolean = true
        var completions = mutableListOf<Pair<String, String>>()
            private set
        var sent = mutableListOf<String>()
            private set
        var sendResult: Result<Unit> = Result.success(Unit)

        override suspend fun signInWithGoogle(activity: Activity): Result<Unit> =
            Result.success(Unit)

        override suspend fun sendEmailLink(email: String): Result<Unit> {
            sent += email
            return sendResult
        }

        override suspend fun completeEmailLink(link: String, email: String): Result<Unit> {
            completions += link to email
            return Result.success(Unit)
        }

        override fun isEmailLink(link: String): Boolean = link.startsWith("https://")
    }

    /** Records the name it was handed; that name is the assertion in half of these tests. */
    private class RecordingRegistrar : AccountRegistrar {
        val names = mutableListOf<String>()
        val calls get() = names.size
        var outcome: RefreshOutcome = RefreshOutcome.Confirmed(AccountStatus.PENDING)

        override suspend fun registerAndRefresh(displayName: String): RefreshOutcome {
            names += displayName
            return outcome
        }
    }

    private class NoopRestarter : ProcessRestarter {
        override fun restart() = Unit
    }

    private class Harness(
        val access: AccessFixture,
        val viewModel: AccountViewModel,
        val signIn: FakeSignInClient,
        val registrar: RecordingRegistrar,
        val pendingEmail: PendingEmailStore,
        val relay: EmailLinkRelay
    )

    private fun TestScope.harness(
        googleAvailable: Boolean = true,
        emailLinkAvailable: Boolean = true
    ): Harness {
        val access = AccessFixture(context, backgroundScope)
        val owner = DeviceDataOwner(context)
        val session = AccessSession(
            authGateway = access.auth,
            accessRepository = access.repository,
            refresher = access.refresher,
            activeIdentity = access.activeIdentity,
            networkMonitor = NetworkMonitor(context),
            deviceDataOwner = owner,
            processRestarter = NoopRestarter(),
            storageOwnership = StorageOwnership(owner) { null },
            scope = backgroundScope
        )
        val signIn = FakeSignInClient().apply {
            this.googleAvailable = googleAvailable
            this.emailLinkAvailable = emailLinkAvailable
        }
        val registrar = RecordingRegistrar()
        val pendingEmail = IsolatedPendingEmailStore(context, backgroundScope)
        val relay = EmailLinkRelay()
        val viewModel = AccountViewModel(
            context = context,
            accessRepository = access.repository,
            accountRegistrar = registrar,
            accountSignOut = AccountSignOut(access.auth, testPreferencesDataStore(context), owner),
            accessSession = session,
            authGateway = access.auth,
            signInClient = signIn,
            pendingEmailStore = pendingEmail,
            emailLinkRelay = relay
        )
        // `state` shares while subscribed, so without a collector it never leaves Loading.
        backgroundScope.launch { viewModel.state.collect {} }
        settle()
        return Harness(access, viewModel, signIn, registrar, pendingEmail, relay)
    }

    /**
     * Lets the collectors run. Real time is yielded as well as virtual: the access layer and
     * the pending-email store are DataStore-backed, and their file I/O is not on the test
     * dispatcher, so it can only be waited for.
     */
    private fun TestScope.settle(rounds: Int = 20) {
        repeat(rounds) {
            runCurrent()
            // `viewModelScope` is Dispatchers.Main, which under Robolectric is the paused main
            // looper rather than the test dispatcher, so the state flow recomputes only when
            // the looper is drained. Without this the screen keeps its first value forever.
            shadowOf(Looper.getMainLooper()).idle()
            @Suppress("BlockingMethodInNonBlockingContext")
            Thread.sleep(1L)
        }
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private suspend fun TestScope.blockedAs(h: Harness, status: AccountStatus): BlockReason {
        h.access.signIn("uid-a")
        h.access.recordServerVerdict(status)
        settle()
        val state = h.viewModel.state.value
        assertTrue("$status: expected Waiting, was $state", state is AccountUiState.Waiting)
        return (state as AccountUiState.Waiting).reason
    }

    @Test
    fun `pending, rejected and revoked are three different screens, not one`() = runTest {
        assertEquals(BlockReason.AWAITING_APPROVAL, blockedAs(harness(), AccountStatus.PENDING))
        assertEquals(BlockReason.REJECTED, blockedAs(harness(), AccountStatus.REJECTED))
        assertEquals(BlockReason.REVOKED, blockedAs(harness(), AccountStatus.REVOKED))
    }

    /**
     * An account the server has never heard of is the fourth state, and it is not a waiting
     * screen: there is nothing to wait for until the user has said what to register as.
     */
    @Test
    fun `an account with no registration is asked what to call itself`() = runTest {
        val h = harness()
        h.access.auth.currentDisplayName = "  Ada   Lovelace "
        h.access.signIn("uid-a", email = "ada@example.test")
        h.access.recordServerVerdict(AccountStatus.NEW)
        settle()

        val state = h.viewModel.state.value
        assertTrue("expected NeedsName, was $state", state is AccountUiState.NeedsName)
        state as AccountUiState.NeedsName
        assertEquals("ada@example.test", state.email)
        // Offered, and offered tidily — but only offered.
        assertEquals("Ada Lovelace", state.suggestion)
        assertEquals("signing in registers nothing by itself", 0, h.registrar.calls)
    }

    /** A provider that supplies no name leaves the field empty rather than inventing one. */
    @Test
    fun `no provider name means no suggestion, and never the email address`() = runTest {
        val h = harness()
        h.access.signIn("uid-a", email = "ada.lovelace@example.test")
        settle()

        val state = h.viewModel.state.value as AccountUiState.NeedsName
        assertEquals("", state.suggestion)
    }

    @Test
    fun `registering uses the name the user confirmed, normalised`() = runTest {
        val h = harness()
        h.access.auth.currentDisplayName = "Ada Lovelace"
        h.access.signIn("uid-a")
        settle()

        h.viewModel.submitName("  Ada   L  ")
        settle()

        assertEquals(listOf("Ada L"), h.registrar.names)
        assertNull(h.viewModel.message.value)
    }

    /**
     * The button is disabled for these, which is a UI state and not a guarantee: the view model
     * is what stands between an unusable name and a document nobody can identify afterwards.
     */
    @Test
    fun `an empty, whitespace, too short or too long name registers nothing`() = runTest {
        val h = harness()
        h.access.signIn("uid-a")
        settle()

        for (name in listOf("", "   ", "A", " A ", "N".repeat(81))) {
            h.viewModel.submitName(name)
            settle()
            assertEquals("\"$name\" must not register", emptyList<String>(), h.registrar.names)
            assertEquals(
                context.getString(R.string.registration_name_invalid),
                h.viewModel.message.value
            )
            h.viewModel.consumeMessage()
        }
    }

    /** A registration that could not be sent says so, rather than looking like a rejection. */
    @Test
    fun `an offline registration reports the connection, not a verdict`() = runTest {
        val h = harness()
        h.access.signIn("uid-a")
        h.registrar.outcome = RefreshOutcome.NoNetwork
        settle()

        h.viewModel.submitName("Ada Lovelace")
        settle()

        assertEquals(listOf("Ada Lovelace"), h.registrar.names)
        assertEquals(
            context.getString(R.string.registration_check_offline),
            h.viewModel.message.value
        )
    }

    @Test
    fun `the waiting screen names the account it is waiting for`() = runTest {
        val h = harness()
        h.access.signIn("uid-a", email = "tester@example.test")
        h.access.recordServerVerdict(AccountStatus.PENDING)
        settle()
        assertEquals(
            "tester@example.test",
            (h.viewModel.state.value as AccountUiState.Waiting).email
        )
    }

    @Test
    fun `check again says so when there was no connection to ask over`() = runTest {
        val h = harness()
        h.access.signIn("uid-a")
        h.access.recordServerVerdict(AccountStatus.PENDING)
        h.access.server.answer = { RefreshOutcome.NoNetwork }
        settle()

        h.viewModel.refresh()
        settle()

        assertEquals(
            context.getString(R.string.registration_check_offline),
            h.viewModel.message.value
        )
    }

    @Test
    fun `check again says so when the server could not be reached`() = runTest {
        val h = harness()
        h.access.signIn("uid-a")
        h.access.recordServerVerdict(AccountStatus.PENDING)
        h.access.server.answer = { RefreshOutcome.Failed(IllegalStateException("boom")) }
        settle()

        h.viewModel.refresh()
        settle()

        assertEquals(
            context.getString(R.string.registration_check_failed),
            h.viewModel.message.value
        )
    }

    /** The control: a check that did reach the server says nothing, and changes the state. */
    @Test
    fun `check again reports nothing when the server answered`() = runTest {
        val h = harness()
        h.access.signIn("uid-a")
        h.access.recordServerVerdict(AccountStatus.PENDING)
        h.access.server.answer = { RefreshOutcome.Confirmed(AccountStatus.APPROVED) }
        settle()

        h.viewModel.refresh()
        settle()

        assertNull(h.viewModel.message.value)
        assertTrue(
            "expected the approved account to leave the waiting screen",
            h.viewModel.state.value !is AccountUiState.Waiting
        )
    }

    @Test
    fun `a link opened on a device that never asked for one signs nobody in`() = runTest {
        val h = harness()
        h.relay.offer("https://voice.example.test/link?oobCode=abc&email=victim@example.test")
        settle()

        assertEquals(emptyList<Pair<String, String>>(), h.signIn.completions)
        assertEquals(0, h.registrar.calls)
        assertEquals(
            context.getString(R.string.registration_email_needed),
            h.viewModel.message.value
        )
        val state = h.viewModel.state.value
        assertTrue("expected SignedOut, was $state", state is AccountUiState.SignedOut)
        assertTrue(
            "the screen should be asking for the address",
            (state as AccountUiState.SignedOut).completingLink
        )
    }

    /**
     * And when it is completed, the address is the one the user typed — never the one carried
     * in the link, which is why the previous test may not simply be "nothing happens".
     */
    @Test
    fun `completing a parked link uses the address the user typed`() = runTest {
        val h = harness()
        val link = "https://voice.example.test/link?oobCode=abc&email=victim@example.test"
        h.relay.offer(link)
        settle()

        h.viewModel.submitEmail("me@example.test")
        settle()

        assertEquals(listOf(link to "me@example.test"), h.signIn.completions)
        // Signing in is not registering. The name step comes next, and it is the user's.
        assertEquals(0, h.registrar.calls)
    }

    /** The control for the device that did ask: it completes without asking again. */
    @Test
    fun `a link on the device that requested it completes with the remembered address`() =
        runTest {
            val h = harness()
            h.pendingEmail.remember("me@example.test")
            val link = "https://voice.example.test/link?oobCode=abc&email=victim@example.test"
            h.relay.offer(link)
            settle()

            assertEquals(listOf(link to "me@example.test"), h.signIn.completions)
            assertEquals(0, h.registrar.calls)
            assertNull(h.viewModel.message.value)
        }

    @Test
    fun `the same link is not completed twice`() = runTest {
        val h = harness()
        h.pendingEmail.remember("me@example.test")
        h.relay.offer("https://voice.example.test/link?oobCode=abc")
        settle()
        // A recreated screen collects the relay again; the link must already be gone.
        assertNull(h.relay.link.value)
        assertEquals(1, h.signIn.completions.size)
    }

    @Test
    fun `a build with no Firebase configuration says so instead of offering sign-in`() = runTest {
        val h = harness(googleAvailable = false, emailLinkAvailable = false)
        val state = h.viewModel.state.value
        assertTrue("expected SignedOut, was $state", state is AccountUiState.SignedOut)
        assertTrue(
            "an unconfigured build must not claim it can sign anybody in",
            !(state as AccountUiState.SignedOut).configured
        )
    }

    @Test
    fun `a project without an OAuth client offers the email link and not Google`() = runTest {
        val h = harness(googleAvailable = false, emailLinkAvailable = true)
        val state = h.viewModel.state.value as AccountUiState.SignedOut
        assertFalse("no default_web_client_id, no Google button", state.googleAvailable)
        assertTrue(state.emailLinkAvailable)
        assertTrue(state.configured)
    }

    @Test
    fun `a fully configured project offers both routes`() = runTest {
        val h = harness(googleAvailable = true, emailLinkAvailable = true)
        val state = h.viewModel.state.value as AccountUiState.SignedOut
        assertTrue(state.googleAvailable)
        assertTrue(state.emailLinkAvailable)
    }

    @Test
    fun `a project that has not enabled sign-in links says so instead of echoing a code`() = runTest {
        val h = harness()
        h.signIn.sendResult = Result.failure(SignInClient.EmailLinkNotEnabled())
        h.viewModel.submitEmail("me@example.test")
        settle()
        assertEquals(
            context.getString(R.string.registration_email_link_disabled),
            h.viewModel.message.value
        )
    }
}
