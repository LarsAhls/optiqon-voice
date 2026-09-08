package se.optiqon.voice.data.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Who owns the data that was already on the device.
 *
 * This is the question the account gate creates and cannot avoid: an install that has been
 * dictating for months suddenly has to say whose history that is. Getting it wrong in either
 * direction is a real harm — attaching one person's transcripts to whoever signs in first, or
 * making an existing user's own data unreachable — so the binding is explicit, persisted, and
 * asked exactly once.
 *
 * Every test here works on synthetic bindings. Nothing in this class deletes, moves or
 * rewrites a byte of anyone's data, and neither does the class it tests.
 */
@RunWith(RobolectricTestRunner::class)
class DeviceDataOwnerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** A new instance reading the same file, which is what the next process start is. */
    private fun owner() = DeviceDataOwner(context)

    @Test
    fun `a new account is never given the data already on the device`() {
        val root = owner().rootFor("uid-a")

        assertNotEquals(
            "an unasked account must not inherit the existing history",
            StorageRoot.DEFAULT,
            root
        )
        assertNull(owner().defaultOwner())
    }

    @Test
    fun `claiming binds the existing files without renaming them`() {
        val o = owner()

        assertTrue(o.canClaimDefault("uid-a"))
        assertEquals(StorageRoot.DEFAULT, o.claimDefault("uid-a"))

        // The point of the default root: the claim changes a name in a preferences file, not a
        // single path on disk.
        assertEquals("optiqon_voice.db", o.rootFor("uid-a").databaseName)
        assertEquals("uid-a", o.defaultOwner())
    }

    @Test
    fun `a second account cannot claim data that is already owned`() {
        val o = owner()
        o.claimDefault("uid-a")

        assertFalse(o.canClaimDefault("uid-b"))
        assertThrows(IllegalStateException::class.java) { o.claimDefault("uid-b") }
        assertEquals("uid-a", o.defaultOwner())
    }

    @Test
    fun `declining leaves the data unclaimed and still claimable by its owner`() {
        val o = owner()
        o.declineDefault("uid-b")

        assertNull("nobody owns it yet", o.defaultOwner())
        assertFalse("and the account that declined is not asked again", o.canClaimDefault("uid-b"))
        assertTrue("but the real owner can still claim it later", o.canClaimDefault("uid-a"))
    }

    @Test
    fun `the claim question is asked once per account`() {
        val o = owner()

        assertFalse(o.hasAnsweredClaim("uid-a"))
        o.declineDefault("uid-a")
        assertTrue(o.hasAnsweredClaim("uid-a"))

        val second = owner()
        assertTrue("and the answer survives a restart", second.hasAnsweredClaim("uid-a"))
    }

    @Test
    fun `two accounts never resolve to the same root`() {
        val o = owner()

        val a = o.rootFor("uid-a")
        val b = o.rootFor("uid-b")

        assertNotEquals(a, b)
        assertNotEquals(a.databaseName, b.databaseName)
        assertNotEquals(a.preferencesName, b.preferencesName)
        assertNotEquals(a.securePreferencesName, b.securePreferencesName)
    }

    @Test
    fun `a binding survives the process it was made in`() {
        val allocated = owner().rootFor("uid-a")

        // A fresh instance, as after a restart: the same account must open the same files, or
        // its data is gone as far as it can tell.
        assertEquals(allocated, owner().rootFor("uid-a"))
    }

    @Test
    fun `an interrupted claim leaves either the old answer or the new one, never a mixture`() {
        val o = owner()
        o.rootFor("uid-a")

        // The claim is committed rather than applied precisely because the caller may end the
        // process on the next line. Reading it back through a new instance is the only way to
        // tell the difference between a write that reached disk and one that did not.
        o.claimDefault("uid-a")
        val afterRestart = owner()

        assertEquals("uid-a", afterRestart.defaultOwner())
        assertEquals(StorageRoot.DEFAULT, afterRestart.rootFor("uid-a"))
        assertFalse(afterRestart.canClaimDefault("uid-a"))
    }

    @Test
    fun `the active account is remembered so the next start opens the right files`() {
        val o = owner()
        val root = o.setActiveUid("uid-a")

        val afterRestart = owner()
        assertEquals("uid-a", afterRestart.activeUid())
        assertEquals(root, afterRestart.rootFor(afterRestart.activeUid()))
    }

    @Test
    fun `a signed-out device resolves to the default root`() {
        val o = owner()
        o.setActiveUid("uid-a")

        o.setActiveUid(null)

        assertNull(owner().activeUid())
        assertEquals(StorageRoot.DEFAULT, owner().rootFor(null))
    }
}
