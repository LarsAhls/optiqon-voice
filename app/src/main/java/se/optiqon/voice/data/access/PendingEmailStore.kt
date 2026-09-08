package se.optiqon.voice.data.access

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers which address a sign-in link was sent to.
 *
 * Firebase requires the address to complete the link, and taking it from the link itself would
 * let a forwarded link sign somebody in as the original recipient. Storing it on the device
 * that requested it keeps the link tied to that device.
 */
@Singleton
open class PendingEmailStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    protected open val store: DataStore<Preferences> get() = context.accessDataStore

    private val key = stringPreferencesKey("pending_sign_in_email")

    suspend fun remember(email: String) {
        store.edit { it[key] = email }
    }

    suspend fun pending(): String? = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[key] }
        .first()

    suspend fun clear() {
        store.edit { it.remove(key) }
    }
}
