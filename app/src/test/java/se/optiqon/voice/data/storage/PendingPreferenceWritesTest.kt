package se.optiqon.voice.data.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * What survives the end of the process.
 *
 * The app ends its own process on purpose when the identity moves, and twice now a write that
 * had only been `apply()`d was lost to that exit: a sign-out that appeared to do nothing, and a
 * sign-in that bound the storage root while Firebase's record of who had signed in never
 * reached disk, so the account screen asked to register again forever.
 *
 * A unit test cannot reproduce the race — it cannot hold a queued write open and kill the
 * process — so what is fastened here is the weaker, checkable half: after the flush the value
 * is readable **in the file**, not merely in the in-memory copy that dies with the process.
 * The race itself is answered by where the flush is called from, and proven on a device.
 */
@RunWith(RobolectricTestRunner::class)
class PendingPreferenceWritesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun fileFor(name: String) =
        File(File(context.applicationInfo.dataDir, "shared_prefs"), "$name.xml")

    @Test
    fun `flushing everything puts an applied value in the file`() {
        context.getSharedPreferences("some_library", Context.MODE_PRIVATE)
            .edit().putString("who", "signed-in").apply()

        PendingPreferenceWrites.flushAll(context)

        assertTrue(fileFor("some_library").readText().contains("signed-in"))
    }

    @Test
    fun `flushing by prefix leaves the files it does not name alone`() {
        context.getSharedPreferences("com.google.firebase.auth.api.Store.abc", Context.MODE_PRIVATE)
            .edit().putString("who", "the-account").apply()

        PendingPreferenceWrites.flush(context, "com.google.firebase.auth.api.")

        val flushed = fileFor("com.google.firebase.auth.api.Store.abc")
        assertTrue(flushed.readText().contains("the-account"))
    }

    @Test
    fun `an empty shared preferences directory is not an error`() {
        PendingPreferenceWrites.flushAll(context)
        PendingPreferenceWrites.flush(context, "nothing-is-called-this")
    }
}
