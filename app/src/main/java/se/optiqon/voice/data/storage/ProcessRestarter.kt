package se.optiqon.voice.data.storage

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import se.optiqon.voice.MainActivity
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.system.exitProcess

/**
 * Ends this process so the next one opens a different [StorageRoot].
 *
 * Blunt on purpose. Re-pointing an open Room database, two preference stores and whatever
 * background work is in flight at a different set of files, correctly, at an arbitrary moment,
 * is the kind of thing that works in testing and leaks a stale handle in the field. A process
 * that no longer exists cannot hold a handle to the previous account's data, and that is a
 * property worth more here than a seamless transition.
 */
interface ProcessRestarter {
    fun restart()
}

@Singleton
class AndroidProcessRestarter @Inject constructor(
    @ApplicationContext private val context: Context
) : ProcessRestarter {

    override fun restart() {
        // Queued before the exit so the user lands back in the app rather than on the launcher,
        // which would look exactly like a crash.
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        context.startActivity(intent)
        // The restart is only correct if the next process can read what this one decided. An
        // `apply()` that has not reached disk does not survive the line below, and the identity
        // that triggered the restart is precisely the kind of thing written that way — by us
        // and by Firebase. Flushed here rather than at each call site so that ending the process
        // cannot silently lose a write again.
        PendingPreferenceWrites.flushAll(context)
        exitProcess(0)
    }
}
