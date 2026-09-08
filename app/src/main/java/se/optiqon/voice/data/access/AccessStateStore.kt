package se.optiqon.voice.data.access

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import se.optiqon.voice.domain.access.AccessSnapshot
import se.optiqon.voice.domain.access.AccountStatus
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

internal val Context.accessDataStore: DataStore<Preferences> by preferencesDataStore(name = "access")

/**
 * The last server verdict, one record per uid.
 *
 * Keys are namespaced by uid so signing in as B never reads A's verdict, and signing out
 * deletes nothing: A's record simply stops being consulted. Deleting it would be the same
 * mistake as clearing Firestore's persistence — it throws away state that the rightful owner
 * still needs, in exchange for an isolation that per-uid keys already provide.
 */
@Singleton
open class AccessStateStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    protected open val store: DataStore<Preferences> get() = context.accessDataStore

    private fun statusKey(uid: String) = stringPreferencesKey("status_$uid")
    private fun wallKey(uid: String) = longPreferencesKey("verified_wall_$uid")
    private fun elapsedKey(uid: String) = longPreferencesKey("verified_elapsed_$uid")
    private fun epochKey(uid: String) = longPreferencesKey("epoch_token_$uid")
    private fun seqKey(uid: String) = longPreferencesKey("seq_$uid")

    fun snapshot(uid: String): Flow<AccessSnapshot?> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs -> read(prefs, uid) }

    suspend fun record(snapshot: AccessSnapshot) {
        recordIfAccepted(snapshot) { true }
    }

    /**
     * Stores [snapshot] only if [accept] says so, deciding and writing in one step.
     *
     * The decision has to happen *here* rather than in the caller, and the difference is not
     * stylistic. Reading the stored verdict, deciding, and then writing is three steps with two
     * gaps in them: two answers that arrive together can both read the same "nothing stored
     * yet", both conclude they are newest, and then commit in whichever order the dispatcher
     * happens to pick — so an older approval can land on top of a newer revocation. DataStore
     * serialises `updateData` per instance, so a predicate evaluated inside the transform sees
     * the state that will actually be overwritten, and nothing can slip between the two.
     *
     * @param accept given the currently stored snapshot for this uid, or null if there is none.
     * It may be re-run — DataStore retries the transform on a write conflict — so it must
     * decide from its argument and from live state only, never from anything a previous run
     * left behind.
     *
     * @return whether the snapshot was stored.
     */
    suspend fun recordIfAccepted(
        snapshot: AccessSnapshot,
        accept: (AccessSnapshot?) -> Boolean
    ): Boolean {
        var accepted = false
        store.edit { prefs ->
            accepted = accept(read(prefs, snapshot.uid))
            if (accepted) {
                prefs[statusKey(snapshot.uid)] = snapshot.status.name
                prefs[wallKey(snapshot.uid)] = snapshot.verifiedAtWallMs
                prefs[elapsedKey(snapshot.uid)] = snapshot.verifiedAtElapsedMs
                prefs[epochKey(snapshot.uid)] = snapshot.epochToken
                prefs[seqKey(snapshot.uid)] = snapshot.seq
            }
        }
        return accepted
    }

    private fun read(prefs: Preferences, uid: String): AccessSnapshot? {
        val stored = prefs[statusKey(uid)] ?: return null
        // An unparseable value is treated as no verdict at all rather than as approval.
        val status = AccountStatus.entries.firstOrNull { it.name == stored } ?: return null
        return AccessSnapshot(
            uid = uid,
            status = status,
            verifiedAtWallMs = prefs[wallKey(uid)] ?: 0L,
            verifiedAtElapsedMs = prefs[elapsedKey(uid)] ?: 0L,
            epochToken = prefs[epochKey(uid)] ?: 0L,
            seq = prefs[seqKey(uid)] ?: 0L
        )
    }
}
