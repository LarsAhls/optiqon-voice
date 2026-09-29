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
import se.optiqon.voice.domain.feedback.CaseComposer
import se.optiqon.voice.domain.feedback.CaseUnread
import se.optiqon.voice.domain.feedback.FeedbackBuildInfo
import se.optiqon.voice.domain.feedback.FeedbackConfig
import se.optiqon.voice.domain.feedback.UnreadTracker
import se.optiqon.voice.domain.feedback.UnreadTrackerTest
import se.optiqon.voice.testing.FakeAttachmentStore
import se.optiqon.voice.testing.FakeCaseRemote
import se.optiqon.voice.testing.MemoryOutboxDao
import se.optiqon.voice.testing.SwitchableAuth
import java.io.File

/**
 * FS-S468 end to end through the screen's state: the remote list carries the unread set,
 * opening a case records it in the (fake) Firestore markers, and nothing else does.
 */
@RunWith(RobolectricTestRunner::class)
class FeedbackViewModelUnreadTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val auth = SwitchableAuth("uid-a")
    private var approved = true
    private val remote = FakeCaseRemote()
    private val reads = UnreadTrackerTest.FakeReads()
    private val outbox = MemoryOutboxDao()

    private fun viewModel(remoteEnabled: Boolean = true): FeedbackViewModel {
        val files = UserScopedStorage(File(context.cacheDir, "fvm-${System.nanoTime()}"))
        return FeedbackViewModel(
            context = context,
            composer = CaseComposer(
                outbox = outbox,
                auth = auth,
                approval = { approved },
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
            approval = { approved },
            config = FeedbackConfig("gs://test", remoteEnabled),
            unread = UnreadTracker(CaseUnread(reads, auth) { approved }, auth)
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

    private fun own(id: String, publicRev: Long, uid: String = "uid-a") {
        remote.cases[id] = uid
        remote.publicRev[id] = publicRev
    }

    @Test
    fun `a case with a newer public reply is unread in the list`() = runTest {
        own("c1", 2L)
        own("c2", 1L)
        reads.markers["uid-a"] = mutableMapOf("c2" to 1L)

        val vm = viewModel()
        settle()

        assertEquals(setOf("c1"), vm.state.value.unread)
    }

    @Test
    fun `opening it records the revision it showed, and back on the list it is read`() = runTest {
        own("c1", 2L)
        val vm = viewModel()
        settle()

        vm.open("c1")
        settle()
        assertEquals(emptySet<String>(), vm.state.value.unread)
        assertEquals(listOf(Triple("uid-a", "c1", 2L)), reads.writes)

        vm.back()
        settle()
        assertEquals(emptySet<String>(), vm.state.value.unread)
        assertEquals(1, reads.writes.size)
    }

    @Test
    fun `opening a read case again writes nothing`() = runTest {
        own("c1", 2L)
        reads.markers["uid-a"] = mutableMapOf("c1" to 2L)
        val vm = viewModel()
        settle()

        vm.open("c1")
        settle()
        vm.back()
        settle()
        vm.refreshRemote()
        vm.refreshRemote()
        settle()

        assertTrue(reads.writes.isEmpty())
        assertEquals(emptySet<String>(), vm.state.value.unread)
    }

    @Test
    fun `a later reply makes it unread again`() = runTest {
        own("c1", 2L)
        val vm = viewModel()
        settle()
        vm.open("c1")
        settle()
        vm.back()
        settle()

        remote.publicRev["c1"] = 3L
        vm.refreshRemote()
        settle()

        assertEquals(setOf("c1"), vm.state.value.unread)
    }

    @Test
    fun `a revoked or pending account sees no unread and writes nothing`() = runTest {
        own("c1", 2L)
        approved = false
        val vm = viewModel()
        settle()
        vm.open("c1")
        settle()

        assertEquals(emptySet<String>(), vm.state.value.unread)
        assertEquals(0, reads.reads)
        assertTrue(reads.writes.isEmpty())
    }

    @Test
    fun `another account's markers never leak into this list`() = runTest {
        own("c1", 2L)
        reads.markers["uid-b"] = mutableMapOf("c1" to 2L)
        val vm = viewModel()
        settle()
        assertEquals(setOf("c1"), vm.state.value.unread)

        vm.open("c1")
        settle()
        assertEquals(listOf("uid-a"), reads.writes.map { it.first })
        assertEquals(2L, reads.markers["uid-b"]!!["c1"])
    }

    @Test
    fun `with remote feedback off nothing is read or written`() = runTest {
        own("c1", 2L)
        val vm = viewModel(remoteEnabled = false)
        settle()

        assertEquals(emptySet<String>(), vm.state.value.unread)
        assertEquals(0, reads.reads)
        assertTrue(reads.writes.isEmpty())
    }
}
