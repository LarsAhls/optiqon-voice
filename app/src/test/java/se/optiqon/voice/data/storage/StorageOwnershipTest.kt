package se.optiqon.voice.data.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.optiqon.voice.domain.access.AccessSession
import se.optiqon.voice.domain.transcription.NetworkMonitor
import se.optiqon.voice.testing.AccessFixture
import java.io.File

/**
 * Which files this process is allowed to open, and when it stops being allowed to write to them.
 *
 * The note this app writes to itself about who was last active is not a credential. A process can
 * start under a different account than the note says — a sign-out on another device, a token that
 * expired into a different session, a switch that was interrupted before the note caught up — and
 * everything that runs at start-up writes something: schema migrations, profile defaults,
 * repositories waking up. If the root is chosen from the note alone, all of that lands in the
 * wrong person's files before anything has checked who is actually here.
 *
 * So every test below asks the same question in a different way: *whose bytes changed?* The data
 * belonging to the account that is not signed in is written with a known content and compared
 * afterwards, byte for byte. Every byte in this file is synthetic; nothing here, and nothing in
 * the class it tests, deletes, moves, copies or uploads anyone's data.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class StorageOwnershipTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Records a restart instead of ending the JVM the test is running in. */
    private class RecordingRestarter : ProcessRestarter {
        var restarts = 0
            private set

        override fun restart() {
            restarts++
        }
    }

    /** A new instance reading the same bindings file, which is what the next process start is. */
    private fun owner() = DeviceDataOwner(context)

    private fun ownership(owner: DeviceDataOwner, uid: String?) =
        StorageOwnership(owner) { uid }

    /** Stands in for whatever a root holds: a file at a path only that root resolves to. */
    private fun writeSyntheticData(root: StorageRoot, content: String): File {
        val file = context.getDatabasePath(root.databaseName)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return file
    }

    @Test
    fun `a start under a different account than the one remembered opens neither wrongly`() {
        // The device last wrote as A, and A's files exist.
        val setUp = owner()
        val rootA = setUp.setActiveUid("uid-a")
        val fileA = writeSyntheticData(rootA, "A:s egna anteckningar")

        // This process is actually signed in as B.
        val resolved = ownership(owner(), "uid-b").root

        assertNotEquals("B must not be handed A's files", rootA, resolved)
        assertNotEquals(
            "nor A's database by any other name",
            rootA.databaseName,
            resolved.databaseName
        )
        assertEquals(
            "and A's data is exactly as A left it",
            "A:s egna anteckningar",
            fileA.readText()
        )
        assertEquals("the stale note is corrected, not obeyed", "uid-b", owner().activeUid())
    }

    @Test
    fun `the right owner gets its own files back on the next start`() {
        val setUp = owner()
        val rootA = setUp.setActiveUid("uid-a")
        val fileA = writeSyntheticData(rootA, "A:s egna anteckningar")

        // B starts once, which moves the note. That must not cost A anything.
        ownership(owner(), "uid-b").root

        val backAsA = ownership(owner(), "uid-a").root

        assertEquals("the binding is what recovery rests on", rootA, backAsA)
        assertEquals("A:s egna anteckningar", fileA.readText())
    }

    @Test
    fun `a signed-out process does not open the data that belongs to someone`() {
        val setUp = owner()
        setUp.claimDefault("uid-a")
        setUp.setActiveUid("uid-a")
        val ownerFile = writeSyntheticData(StorageRoot.DEFAULT, "allt A har dikterat")

        val resolved = ownership(owner(), null).root

        assertEquals(StorageRoot.SIGNED_OUT, resolved)
        assertFalse("signing out is not a way back into the owner's files", resolved.isDefault)
        assertEquals("allt A har dikterat", ownerFile.readText())
    }

    @Test
    fun `a device whose data nobody has claimed still opens the data it has always used`() {
        // The install this app has always been: one person, no account, no claim. Nothing about
        // the account gate may take their own data away from them.
        assertEquals(StorageRoot.DEFAULT, ownership(owner(), null).root)
    }

    @Test
    fun `an identity that cannot be read is unknown, not the account that is remembered`() {
        val setUp = owner()
        setUp.claimDefault("uid-a")
        setUp.setActiveUid("uid-a")
        val ownerFile = writeSyntheticData(StorageRoot.DEFAULT, "allt A har dikterat")

        // An auth SDK that is missing, unconfigured or broken answers with an exception. That is
        // "we cannot say who this is", which is the closed door — not "carry on as A".
        val unreadable = StorageOwnership(owner()) { error("auth is not available") }

        assertEquals(StorageRoot.SIGNED_OUT, unreadable.root)
        assertEquals("allt A har dikterat", ownerFile.readText())
    }

    @Test
    fun `an account that has not answered the claim question stays on the unclaimed data`() {
        // Nobody owns what is on the device yet, and this account has never been asked. Binding
        // it either way here would be the guess the claim question exists to avoid, so the root
        // does not move and the account gate holds the app shut until the user answers.
        val resolved = ownership(owner(), "uid-a").root

        assertEquals(StorageRoot.DEFAULT, resolved)
        assertTrue("and the question is still open", owner().canClaimDefault("uid-a"))
    }

    @Test
    fun `the root is resolved once, no matter how often the identity is asked about`() {
        var uid: String? = "uid-a"
        owner().declineDefault("uid-a")
        val ownership = StorageOwnership(owner()) { uid }

        val first = ownership.root
        uid = "uid-b"

        assertEquals("two answers in one process would be two sets of open files", first, ownership.root)
    }

    /**
     * The switch itself, which is where an interrupted restart becomes a real risk.
     *
     * Ending the process is what makes a root change safe: no handle survives a process that does
     * not exist. But the restart is a request, not a guarantee — and in the window between "this
     * root is no longer right" and "this process is gone", start-up work is still queued. The
     * restarter here never restarts anything, which is precisely the interrupted case.
     */
    @Test
    fun `a switch retires the root immediately, even when the restart does not happen`() = runTest {
        val access = AccessFixture(context, backgroundScope)
        val owner = owner()
        // A device whose existing data this account has already declined: the switch therefore
        // resolves to a different root instead of stopping to ask the claim question.
        owner.declineDefault("uid-a")
        val ownership = StorageOwnership(owner) { null }
        val restarter = RecordingRestarter()
        val session = AccessSession(
            authGateway = access.auth,
            accessRepository = access.repository,
            refresher = access.refresher,
            activeIdentity = access.activeIdentity,
            networkMonitor = NetworkMonitor(context),
            deviceDataOwner = owner,
            processRestarter = restarter,
            storageOwnership = ownership,
            scope = backgroundScope
        )
        session.start()
        settle()

        assertEquals(StorageRoot.DEFAULT, ownership.root)
        assertTrue("start-up work may write before anything moves", ownership.isWritable)

        access.auth.signIn("uid-a", "a@example.test")
        settle()

        assertEquals("the process was asked to end", 1, restarter.restarts)
        assertFalse(
            "and until it does, nothing may write into the root it is leaving",
            ownership.isWritable
        )
    }

    /**
     * Real time is yielded as well as virtual: the access layer persists through DataStore, whose
     * file I/O is not on the test dispatcher and so cannot be advanced, only waited for.
     */
    private fun TestScope.settle(rounds: Int = 20) {
        repeat(rounds) {
            runCurrent()
            @Suppress("BlockingMethodInNonBlockingContext")
            Thread.sleep(1L)
        }
        runCurrent()
    }
}
