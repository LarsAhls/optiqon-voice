package se.optiqon.voice.data.storage

import java.io.File

/**
 * Gives every account its own attachment directory under app-private storage.
 *
 * Isolation here is by path, not by encryption: another *account* on the same install cannot
 * reach a directory it does not know how to name, and the app never hands out a path for a uid
 * other than the one signed in. On a rooted device all of it is readable — that is a limit of
 * app-private storage, not something this class pretends to solve.
 *
 * Scope: attachments only, as preparation for the F3 upload path. Everything the app stores
 * today — history, profiles, prompts, settings, keys, retained audio — is separated by
 * [StorageRoot] instead, at the storage handle rather than by path. Data that predates accounts
 * lives in the `default` root and is bound to an account only by an explicit answer to the
 * claim question; there is no `files/legacy` directory, and nothing ever wrote one.
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
     * The check the upload path uses before touching a file: the file must sit inside the
     * signed-in account's own directory.
     */
    fun isReadableBy(file: File, uid: String?): Boolean {
        if (uid == null) return false
        val root = File(filesDir, "users/$uid").canonicalPath + File.separator
        return file.canonicalPath.startsWith(root)
    }
}
