package se.optiqon.voice.data.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The negative tests §3.18, §3.20 and the file half of §3.23: an account switch must not expose
 * the previous account's images, and files that predate accounts must stay where they are.
 */
class UserScopedStorageTest {

    @get:Rule val folder = TemporaryFolder()

    private val storage: UserScopedStorage by lazy { UserScopedStorage(folder.root) }

    private fun attachment(uid: String, name: String): File =
        File(storage.attachmentsDir(uid), name).apply { writeText("bild") }

    @Test
    fun `each account gets a directory of its own`() {
        assertTrue(storage.attachmentsDir("uid-a").isDirectory)
        assertFalse(storage.attachmentsDir("uid-a") == storage.attachmentsDir("uid-b"))
    }

    // §3.18 / §3.20 — what the next account can reach.

    @Test
    fun `an image left by A is not readable by B`() {
        val pending = attachment("uid-a", "kvitto.jpg")

        assertFalse(storage.isReadableBy(pending, "uid-b"))
        assertTrue(storage.isReadableBy(pending, "uid-a"))
    }

    @Test
    fun `an image stays in its owner's directory when somebody else signs in`() {
        val pending = attachment("uid-a", "kvitto.jpg")

        // Signing in as B creates B's directory and changes nothing about A's file.
        storage.attachmentsDir("uid-b")

        assertTrue(pending.exists())
        assertTrue(storage.attachmentsDir("uid-b").listFiles().orEmpty().isEmpty())
        assertEquals(listOf("kvitto.jpg"), storage.attachmentsDir("uid-a").list()!!.toList())
    }

    @Test
    fun `nobody signed in can read anything`() {
        val pending = attachment("uid-a", "kvitto.jpg")

        assertFalse(storage.isReadableBy(pending, uid = null))
    }

    @Test
    fun `a path outside the account's directory is refused`() {
        attachment("uid-a", "kvitto.jpg")

        val traversal = File(storage.attachmentsDir("uid-b"), "../../uid-a/attachments/kvitto.jpg")

        // The check is on the canonical path, so climbing back out does not fool it.
        assertFalse(storage.isReadableBy(traversal, "uid-b"))
        assertTrue(storage.isReadableBy(traversal, "uid-a"))
    }

    @Test
    fun `a uid that would escape the storage root is rejected outright`() {
        for (bad in listOf("", "  ", ".", "..", "../uid-a", "uid-a/attachments")) {
            val threw = runCatching { storage.attachmentsDir(bad) }.exceptionOrNull()
            assertTrue("uid '$bad' should have been refused", threw is IllegalArgumentException)
        }
    }

    // §3.23 — files from before Voice had accounts.

    @Test
    fun `legacy files are not attributed to whoever signs in first`() {
        val legacy = File(storage.legacyDir().apply { mkdirs() }, "gammal-inspelning.m4a")
        legacy.writeText("ljud")

        storage.attachmentsDir("uid-a")

        assertTrue(legacy.exists())
        assertTrue(storage.attachmentsDir("uid-a").listFiles().orEmpty().isEmpty())
        // Not readable *as* an account's own file either: adopting it is a separate decision,
        // and the upload path only ever touches files that pass this check.
        assertFalse(storage.isReadableBy(legacy, "uid-a"))
    }
}
