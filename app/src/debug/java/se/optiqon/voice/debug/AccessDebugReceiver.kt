package se.optiqon.voice.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * adb entry point for [AccessDebugControls]. Debug source set only; declared in
 * `app/src/debug/AndroidManifest.xml` behind `android.permission.DUMP`, which an adb shell
 * holds and no third-party app can obtain.
 *
 *     adb shell am broadcast -a se.optiqon.voice.debug.FAIL_REFRESH --ez enabled true
 *     adb shell am broadcast -a se.optiqon.voice.debug.CLOCK_OFFSET --el offsetMs 259200000
 *     adb shell am broadcast -a se.optiqon.voice.debug.EXPORT_ID_TOKEN
 *     adb shell run-as se.optiqon.voice cat files/debug/id-token.txt
 *
 * The exported token goes to the app's private files directory and nowhere else: never to
 * logcat, never to the broadcast result. The smoke procedure reads it once, uses it once
 * and deletes it.
 */
@AndroidEntryPoint
class AccessDebugReceiver : BroadcastReceiver() {

    @Inject lateinit var controls: AccessDebugControls

    override fun onReceive(context: Context, intent: Intent) {
        val extras = intent.extras
        val args = buildMap<String, Any> {
            extras?.keySet()?.forEach { key ->
                @Suppress("DEPRECATION")
                extras.get(key)?.let { put(key, it) }
            }
        }
        val outcome = AccessDebugCommands(
            controls = controls,
            filesDir = context.filesDir,
            idToken = { currentIdToken(context) }
        ).handle(intent.action, args)
        Log.i(TAG, outcome.description)
        resultCode = if (outcome.ok) 0 else 1
        resultData = outcome.description
    }

    /** Null when Firebase is not initialised (no google-services.json) or nobody is signed in. */
    private fun currentIdToken(context: Context): String? {
        if (FirebaseApp.getApps(context).isEmpty()) return null
        val user = FirebaseAuth.getInstance().currentUser ?: return null
        return Tasks.await(user.getIdToken(false), 10, TimeUnit.SECONDS).token
    }

    private companion object {
        const val TAG = "AccessDebug"
    }
}
