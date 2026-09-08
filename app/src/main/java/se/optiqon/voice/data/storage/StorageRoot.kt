package se.optiqon.voice.data.storage

import java.io.File

/**
 * The name of one complete, self-contained set of this app's storage: a database file, two
 * preference files and a directory of retained audio.
 *
 * Isolation lives here rather than in a column on every row, because a filter is only as good
 * as the query that remembers to apply it. A background job, a migration or a repository added
 * next month cannot forget which handle it was given. Two roots are separate the way two
 * installs are separate.
 *
 * Exactly one root is active per process, resolved once at start-up from the persisted active
 * identity. Nothing re-scopes an open handle mid-process; see [DeviceDataOwner].
 */
// A data class rather than a value class: an inline class in a Dagger provider signature is
// name-mangled, and the generated factory fails to compile.
data class StorageRoot(val name: String) {

    init {
        require(name.isNotEmpty() && name.all { it.isDigit() || it in 'a'..'z' }) {
            "Refusing to build storage names from an unusable root name"
        }
    }

    /** True for the files this app has always used, which are never renamed or moved. */
    val isDefault: Boolean get() = name == DEFAULT.name

    val databaseName: String get() = if (isDefault) "optiqon_voice.db" else "optiqon_voice_" + name + ".db"

    val preferencesName: String get() = suffixed("settings")

    val securePreferencesName: String get() = suffixed("secure_settings")

    fun retainedAudioDir(filesDir: File): File = File(filesDir, suffixed("retained_audio"))

    private fun suffixed(base: String): String = if (isDefault) base else base + "_" + name

    companion object {
        /**
         * The root holding whatever is already on the device. It keeps the current file names
         * precisely so that upgrading changes nothing: the existing database, settings, keys and
         * audio are opened by the same paths they were written under.
         */
        val DEFAULT = StorageRoot("default")
    }
}
