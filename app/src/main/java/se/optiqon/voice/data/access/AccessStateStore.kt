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

    fun snapshot(uid: String): Flow<AccessSnapshot?> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { prefs ->
            val stored = prefs[statusKey(uid)] ?: return@map null
            // An unparseable value is treated as no verdict at all rather than as approval.
            val status = AccountStatus.entries.firstOrNull { it.name == stored } ?: return@map null
            AccessSnapshot(
                uid = uid,
                status = status,
                verifiedAtWallMs = prefs[wallKey(uid)] ?: 0L,
                verifiedAtElapsedMs = prefs[elapsedKey(uid)] ?: 0L
            )
        }

    suspend fun record(snapshot: AccessSnapshot) {
        store.edit { prefs ->
            prefs[statusKey(snapshot.uid)] = snapshot.status.name
            prefs[wallKey(snapshot.uid)] = snapshot.verifiedAtWallMs
            prefs[elapsedKey(snapshot.uid)] = snapshot.verifiedAtElapsedMs
        }
    }
}
