package se.optiqon.voice.ui.settings

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import se.optiqon.voice.data.feedback.ScreenshotPreprocessor
import se.optiqon.voice.data.storage.UserScopedStorage
import se.optiqon.voice.data.db.entity.OutboxEntry
import se.optiqon.voice.domain.feedback.CaseComposer
import se.optiqon.voice.domain.feedback.CasePayload
import se.optiqon.voice.domain.feedback.CaseOutboxPayloads
import se.optiqon.voice.domain.feedback.CaseUnread
import se.optiqon.voice.domain.feedback.FeedbackBuildInfo
import se.optiqon.voice.domain.feedback.FeedbackConfig
import se.optiqon.voice.domain.feedback.UnreadTracker
import se.optiqon.voice.domain.feedback.UnreadTrackerTest
import se.optiqon.voice.data.db.entity.OutboxState
import se.optiqon.voice.testing.FakeAttachmentStore
import se.optiqon.voice.testing.FakeCaseRemote
import se.optiqon.voice.testing.MemoryOutboxDao
import se.optiqon.voice.testing.SwitchableAuth
import java.io.File

/**
 * A closed case is read-only and is not reopened: its screenshots are not taken down by hand.
 * Hiding the button is not enough -- asking the screen's state to do it anyway queues nothing,
 * and the rules refuse it on the server whatever a device sends (tests/rules/fs1-cases).
 */
@RunWith(RobolectricTestRunner::class)
class FeedbackViewModelClosedCaseTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val auth = SwitchableAuth("uid-a")
    private val remote = FakeCaseRemote()
    private val outbox = MemoryOutboxDao()

    private fun viewModel(): FeedbackViewModel {
        val files = UserScopedStorage(File(context.cacheDir, "fvc-${System.nanoTime()}"))
        return FeedbackViewModel(
            context = context,
            composer = CaseComposer(
                outbox = outbox,
                auth = auth,
                approval = { true },
                generation = { 1L },
                scheduler = {},
                build = FeedbackBuildInfo("1.2.3", 34, "Pixel Test")
            ),
            remote = remote,
            store = FakeAttachmentStore(),
            preprocessor = ScreenshotPreprocessor(files),
            files = files,
            outbox = outbox,
            auth = auth,
            approval = { true },
            config = FeedbackConfig("gs://test", true),
            unread = UnreadTracker(CaseUnread(UnreadTrackerTest.FakeReads(), auth) { true }, auth)
        )
    }

    private fun TestScope.settle(rounds: Int = 20) {
        repeat(rounds) {
            runCurrent()
            shadowOf(Looper.getMainLooper()).idle()
            @Suppress("BlockingMethodInNonBlockingContext")
            Thread.sleep(1L)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun removals() = outbox.rows.filter { it.kind == CaseOutboxPayloads.KIND_DELETE }

    @Test
    fun `on a closed case a removal is not even queued`() = runTest {
        remote.cases["c1"] = "uid-a"
        remote.attachments["c1/a1"] = null
        remote.closed += "c1"
        val vm = viewModel()
        settle()
        vm.open("c1")
        settle()
        assertTrue(vm.state.value.detail?.closed == true)

        vm.deleteShot("a1")
        settle()
        assertEquals(emptyList<Any>(), removals())
    }

    @Test
    fun `on a closed case an upload that never got through is dropped, and nothing is sent`() = runTest {
        remote.cases["c1"] = "uid-a"
        remote.closed += "c1"
        val upload = CasePayload.Upload("c1", null, "a1", "a1.png", "image/png", 16, 0L)
        outbox.insert(
            OutboxEntry(
                id = "u1", ownerUid = "uid-a", kind = CaseOutboxPayloads.kindOf(upload),
                payload = CaseOutboxPayloads.encode(upload), createdAtMs = 0L, state = OutboxState.BLOCKED
            )
        )
        val vm = viewModel()
        settle()
        vm.open("c1")
        settle()

        vm.deleteShot("a1")
        settle()
        assertEquals(emptyList<Any>(), outbox.rows.filter { it.kind == CaseOutboxPayloads.KIND_UPLOAD })
        assertEquals(emptyList<Any>(), removals())
    }

    @Test
    fun `on an open case it is`() = runTest {
        remote.cases["c1"] = "uid-a"
        remote.attachments["c1/a1"] = null
        val vm = viewModel()
        settle()
        vm.open("c1")
        settle()

        vm.deleteShot("a1")
        settle()
        assertEquals(1, removals().size)
    }
}
