package se.optiqon.voice.data.storage

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers which account owns which [StorageRoot], and answers the one question a fresh
 * install cannot: who the data already on this device belongs to.
 *
 * Deliberately its own tiny SharedPreferences file rather than a row in the app's own settings.
 * The bindings have to be readable before any root is chosen, so they cannot live inside a
 * root — that would be a lock with its key inside. It is read synchronously, because the
 * process must know which files to open before anything opens one.
 *
 * Nothing here moves, copies, deletes or uploads a single byte. A binding is a name.
 */
@Singleton
open class DeviceDataOwner @Inject constructor(
    @ApplicationContext context: Context
) {
    protected open val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    }

    /**
     * The account this process should open storage for.
     *
     * Persisted by this app rather than read back from the auth SDK: the root has to be resolved
     * at start-up, and asking a network-backed identity provider a question you need answered
     * before the first file is opened is a race waiting to happen.
     */
    fun activeUid(): String? = prefs.getString(KEY_ACTIVE_UID, null)

    /**
     * Records the signed-in account and returns the root it resolves to, allocating one if this
     * is an account the device has not seen before.
     *
     * Committed rather than applied: the caller may end the process on the very next line, and a
     * binding that had not reached disk would send the next start to the wrong files.
     */
    fun setActiveUid(uid: String?): StorageRoot {
        val root = rootFor(uid)
        prefs.edit().putString(KEY_ACTIVE_UID, uid).commit()
        return root
    }

    /** The root for [uid], allocating a fresh one the first time an account appears. */
    fun rootFor(uid: String?): StorageRoot {
        if (uid == null) return StorageRoot.DEFAULT
        prefs.getString(KEY_BINDING + uid, null)?.let { return StorageRoot(it) }

        // A new account gets its own empty root, not the data already on the device. Claiming
        // that data is a separate, explicit answer to a question this account has not been asked
        // yet, and guessing it would hand one person a history that belongs to another.
        val index = prefs.getInt(KEY_NEXT_INDEX, 1)
        val allocated = StorageRoot("u" + index)
        prefs.edit()
            .putString(KEY_BINDING + uid, allocated.name)
            .putInt(KEY_NEXT_INDEX, index + 1)
            .commit()
        return allocated
    }

    /** The account owning the pre-existing data, or null while nobody has claimed it. */
    fun defaultOwner(): String? = prefs.getString(KEY_DEFAULT_OWNER, null)

    /**
     * Whether [uid] may still be offered the data already on this device.
     *
     * False once anyone has claimed it, and false once this account has declined: the question
     * is asked once, and a "start empty" that kept re-asking would be a nag, not a choice.
     */
    fun canClaimDefault(uid: String): Boolean =
        defaultOwner() == null && !prefs.getBoolean(KEY_DECLINED + uid, false)

    /** Whether [uid] has already answered the claim question, either way. */
    fun hasAnsweredClaim(uid: String): Boolean =
        defaultOwner() == uid || prefs.getBoolean(KEY_DECLINED + uid, false)

    /**
     * Binds the existing data to [uid]. The files are not touched; only the name of the root
     * this account opens changes.
     */
    fun claimDefault(uid: String): StorageRoot {
        check(canClaimDefault(uid)) { "The data on this device is already claimed" }
        prefs.edit()
            .putString(KEY_DEFAULT_OWNER, uid)
            .putString(KEY_BINDING + uid, StorageRoot.DEFAULT.name)
            .commit()
        return StorageRoot.DEFAULT
    }

    /**
     * Records that [uid] starts empty. The default root stays on disk exactly as it is,
     * unclaimed and untouched, and remains claimable later.
     */
    fun declineDefault(uid: String) {
        prefs.edit().putBoolean(KEY_DECLINED + uid, true).commit()
    }

    private companion object {
        const val FILE_NAME = "storage_bindings"
        const val KEY_ACTIVE_UID = "active_uid"
        const val KEY_BINDING = "binding_"
        const val KEY_DECLINED = "declined_default_"
        const val KEY_DEFAULT_OWNER = "default_owner"
        const val KEY_NEXT_INDEX = "next_root_index"
    }
}
