package se.optiqon.voice.data.storage

import android.content.Context
import java.io.File

/**
 * Forces preference writes that are still queued in this process out to disk.
 *
 * `SharedPreferences.apply()` returns before the file is written; the write is finished on a
 * background thread. That is the right trade almost everywhere — and exactly wrong on the two
 * lines of this app that end the process on purpose, because a process that no longer exists
 * does not finish anything. Whatever was queued is simply gone, and the next start reads a file
 * that disagrees with what the user just did.
 *
 * This has cost real behaviour twice: a sign-out that appeared to do nothing, and a sign-in that
 * bound the storage root but lost Firebase's record of who had signed in, leaving the account
 * screen asking to register again no matter how many times the user signed in.
 *
 * The mechanism is an empty synchronous commit. Preference instances are shared per file within
 * a process and their writes are serialised, so a commit cannot return until whatever was
 * already queued for that file has been written. Nothing is added, changed or removed here.
 */
object PendingPreferenceWrites {

    /**
     * Flushes every preference file this app owns, including the ones written by libraries.
     *
     * Deliberately indiscriminate: the caller is about to end the process, and the question
     * "which of these files matters" has been answered wrongly before. Files that have nothing
     * queued cost a commit that returns immediately.
     */
    fun flushAll(context: Context) {
        val sharedPrefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        sharedPrefsDir.listFiles().orEmpty()
            .map { it.name }
            .filter { it.endsWith(SUFFIX) }
            .forEach { fileName ->
                val name = fileName.removeSuffix(SUFFIX)
                context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().commit()
            }
    }

    /** The same, narrowed to the files whose names start with [prefix]. */
    fun flush(context: Context, prefix: String) {
        val sharedPrefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        sharedPrefsDir.listFiles().orEmpty()
            .map { it.name }
            .filter { it.startsWith(prefix) && it.endsWith(SUFFIX) }
            .forEach { fileName ->
                val name = fileName.removeSuffix(SUFFIX)
                context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().commit()
            }
    }

    private const val SUFFIX = ".xml"
}
