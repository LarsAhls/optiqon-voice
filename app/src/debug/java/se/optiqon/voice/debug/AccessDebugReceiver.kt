package se.optiqon.voice.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import se.optiqon.voice.data.access.AccessStateStore
import se.optiqon.voice.data.db.dao.DictationDao
import se.optiqon.voice.data.db.dao.ProfileDao
import se.optiqon.voice.data.db.dao.TextReplacementRuleDao
import se.optiqon.voice.data.preferences.PreferencesDataStore
import se.optiqon.voice.data.storage.DeviceDataOwner
import se.optiqon.voice.data.storage.StorageRoot
import se.optiqon.voice.domain.access.AccountSignOut
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * adb entry point for [AccessDebugControls] and [SyntheticState]. Debug source set only;
 * declared in `app/src/debug/AndroidManifest.xml` behind `android.permission.DUMP`, which an
 * adb shell holds and no third-party app can obtain.
 *
 *     adb shell am broadcast -a se.optiqon.voice.debug.FAIL_REFRESH --ez enabled true
 *     adb shell am broadcast -a se.optiqon.voice.debug.CLOCK_OFFSET --el offsetMs 259200000
 *     adb shell am broadcast -a se.optiqon.voice.debug.EXPORT_ID_TOKEN
 *     adb shell run-as se.optiqon.voice cat files/debug/id-token.txt
 *     adb shell am broadcast -a se.optiqon.voice.debug.SEED_SYNTHETIC
 *     adb shell am broadcast -a se.optiqon.voice.debug.DUMP_STATE
 *     adb shell am broadcast -a se.optiqon.voice.debug.DUMP_STATE --es root u1
 *     adb shell run-as se.optiqon.voice cat files/debug/state.txt
 *     adb shell run-as se.optiqon.voice cat files/debug/state-u1.txt
 *
 * The exported token goes to the app's private files directory and nowhere else: never to
 * logcat, never to the broadcast result. The smoke procedure reads it once, uses it once
 * and deletes it. The state dump contains counts, names, settings and key *digests* — never a
 * transcript and never a key — and describes the process's own root unless `--es root` names
 * another; see [SyntheticState].
 *
 * The work runs off the main thread under [goAsync]: the stores are suspend/IO, and the
 * Firebase task bridge refuses to block the main thread. `am broadcast` waits for
 * [android.content.BroadcastReceiver.PendingResult.finish] and prints the result.
 */
@AndroidEntryPoint
class AccessDebugReceiver : BroadcastReceiver() {

    @Inject lateinit var controls: AccessDebugControls
    @Inject lateinit var storageRoot: StorageRoot
    @Inject lateinit var profileDao: ProfileDao
    @Inject lateinit var ruleDao: TextReplacementRuleDao
    @Inject lateinit var dictationDao: DictationDao
    @Inject lateinit var preferences: PreferencesDataStore
    @Inject lateinit var deviceDataOwner: DeviceDataOwner
    @Inject lateinit var accessStateStore: AccessStateStore
    @Inject lateinit var accountSignOut: AccountSignOut

    override fun onReceive(context: Context, intent: Intent) {
        val extras = intent.extras
        val args = buildMap<String, Any> {
            extras?.keySet()?.forEach { key ->
                @Suppress("DEPRECATION")
                extras.get(key)?.let { put(key, it) }
            }
        }
        val appContext = context.applicationContext
        val commands = AccessDebugCommands(
            controls = controls,
            filesDir = appContext.filesDir,
            idToken = { currentIdToken(appContext) },
            signOut = { accountSignOut.signOut() },
            synthetic = SyntheticState(
                AndroidSyntheticSink(
                    appContext, storageRoot, profileDao, ruleDao, dictationDao,
                    preferences, deviceDataOwner, accessStateStore
                ),
                appContext.filesDir
            )
        )
        val pending = goAsync()
        scope.launch {
            val outcome = runCatching { commands.handle(intent.action, args) }
                .getOrElse { AccessDebugCommands.Outcome(false, "failed: ${it.javaClass.simpleName}: ${it.message}") }
            Log.i(TAG, outcome.description)
            pending.resultCode = if (outcome.ok) 0 else 1
            pending.resultData = outcome.description
            pending.finish()
        }
    }

    /** Null when Firebase is not initialised (no google-services.json) or nobody is signed in. */
    private fun currentIdToken(context: Context): String? {
        if (FirebaseApp.getApps(context).isEmpty()) return null
        val user = FirebaseAuth.getInstance().currentUser ?: return null
        return Tasks.await(user.getIdToken(false), 10, TimeUnit.SECONDS).token
    }

    private companion object {
        const val TAG = "AccessDebug"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
