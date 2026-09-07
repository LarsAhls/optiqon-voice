package se.optiqon.voice.data.preferences

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * EncryptedSharedPreferences needs the AndroidKeyStore, which does not exist off-device, and
 * no test here is about key storage — so the secrets live in a plain file instead.
 */
internal class PlainSecurePreferencesStore(context: Context) : SecurePreferencesStore(context) {
    override val sharedPreferences: SharedPreferences =
        context.getSharedPreferences("secure_settings_test", Context.MODE_PRIVATE)
}

private val storeCounter = AtomicInteger()

/**
 * A preferences store on a file of its own. The production DataStore is one instance per
 * process, so tests that shared it would decide each other's outcome by running order alone.
 */
internal fun testPreferencesDataStore(context: Context): PreferencesDataStore =
    object : PreferencesDataStore(context, PlainSecurePreferencesStore(context)) {
        override val store: DataStore<Preferences> by lazy {
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            ) {
                File(context.cacheDir, "test-${storeCounter.incrementAndGet()}.preferences_pb")
            }
        }
    }
