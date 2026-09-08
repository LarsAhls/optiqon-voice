package se.optiqon.voice.data.storage

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides which [StorageRoot] this process may open, and when it stops being allowed to write to
 * it.
 *
 * [DeviceDataOwner] remembers which account *was* active. That is a note this app wrote to itself,
 * and on its own it is not a reason to open anybody's files: a process that starts as B while the
 * note still says A would otherwise open A's database, run A's migrations and initialise A's
 * profile before anything had checked who was actually here. So the note is reconciled against the
 * identity that is really signed in, once, before the first handle exists — which is possible only
 * because the resolution happens inside the provider that hands the root out.
 *
 * Two rules follow, and they are the whole point of the class:
 *
 *  1. **An unconfirmed identity opens nobody's private root.** A signed-out or unreadable identity
 *     resolves to the default root while nobody has claimed it — the single-user install this app
 *     has always been — and to a scratch root once someone has, so that signing out cannot leave
 *     the previous owner's data open behind them.
 *  2. **A retired root is not written to.** When the identity moves and the process is on its way
 *     to a restart, [seal] closes the door immediately. If the restart is interrupted and this
 *     process lives on, start-up work that has not run yet finds the door shut rather than writing
 *     into files that no longer belong to whoever is here.
 *
 * Nothing here copies, moves, deletes or uploads anything. Resolving a root is choosing a name.
 */
@Singleton
class StorageOwnership @Inject constructor(
    private val deviceDataOwner: DeviceDataOwner,
    private val signedInUid: SignedInUid
) {
    /**
     * The one root this process opens, resolved at the first injection of a storage root and
     * never again.
     *
     * Once is the requirement, not an optimisation: two different answers in one process would
     * mean two different sets of files open at the same time.
     */
    val root: StorageRoot by lazy { reconcile() }

    @Volatile
    private var sealed = false

    /** False once the root has been retired; see [seal]. */
    val isWritable: Boolean get() = !sealed

    /**
     * Retires the active root: nothing that consults [isWritable] may write to it again.
     *
     * Called before the process is asked to end, so the window between "this is no longer the
     * right root" and "this process is gone" is not a window in which anything can be written.
     */
    fun seal() {
        sealed = true
    }

    private fun reconcile(): StorageRoot {
        // A failure to read the identity is an unknown identity, not an absent one. Both take the
        // conservative branch below, so this cannot turn a broken auth SDK into an open door.
        val signedIn = runCatching { signedInUid.uidOrNull() }.getOrNull()
        val remembered = deviceDataOwner.activeUid()

        if (signedIn == null) {
            // Signed out, or unknown. The remembered uid is a note, not a credential.
            return deviceDataOwner.rootFor(null)
        }

        if (signedIn == remembered) {
            return deviceDataOwner.rootFor(signedIn)
        }

        // Someone else is here than the account this device last wrote as. If the data already on
        // the device is still unclaimed and this device has never had an active account, the claim
        // question is open and the answer is the user's: stay on the default root, bind nothing,
        // and let the account gate hold the app shut until they answer.
        if (remembered == null && deviceDataOwner.canClaimDefault(signedIn)) {
            return StorageRoot.DEFAULT
        }

        // Otherwise the note is simply out of date. Correct it now, before a single handle is
        // opened, so that start-up migrations and profile defaults run against this account's own
        // files rather than the previous account's.
        return deviceDataOwner.setActiveUid(signedIn)
    }
}
