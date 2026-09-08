package se.optiqon.voice.data.storage

import java.io.File

/**
 * Gives every account its own directory under app-private storage, and keeps the pre-Voice
 * files where they are.
 *
 * Isolation here is by path, not by encryption: another *account* on the same install cannot
 * reach a directory it does not know how to name, and the app never hands out a path for a uid
 * other than the one signed in. On a rooted device all of it is readable — that is a limit of
 * app-private storage, not something this class pretends to solve.
 */
class UserScopedStorage(private val filesDir: File) {

    /** `files/users/<uid>/attachments`, created on demand. */
    fun attachmentsDir(uid: String): File {
        require(uid.isNotBlank() && !uid.contains('/') && uid != "." && uid != "..") {
            "Refusing to build a storage path from an unusable uid"
        }
        return File(File(filesDir, "users/$uid"), "attachments").apply { mkdirs() }
    }

    /**
     * Where files created before Voice had accounts live. They are never moved into a uid
     * directory: attributing them to whoever signs in first would be a guess, and a wrong guess
     * would attach one person's recordings to another person's account. Adopting them is a
     * separate, explicit decision.
     */
    fun legacyDir(): File = File(filesDir, "legacy")

    /**
     * The check the upload path uses before touching a file: the file must sit inside the
     * signed-in account's own directory.
     */
    fun isReadableBy(file: File, uid: String?): Boolean {
        if (uid == null) return false
        val root = File(filesDir, "users/$uid").canonicalPath + File.separator
        return file.canonicalPath.startsWith(root)
    }
}
