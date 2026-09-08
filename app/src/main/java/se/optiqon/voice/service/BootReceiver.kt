package se.optiqon.voice.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import se.optiqon.voice.data.preferences.PreferencesDataStore
import se.optiqon.voice.domain.access.AccessDecision
import se.optiqon.voice.domain.access.AccessRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Restores the dictation bubble after a reboot.
 *
 * The bubble only becomes visible once a keyboard appears, so this brings back the
 * service (and its notification), not a bubble on the home screen.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var preferencesDataStore: PreferencesDataStore
    @Inject lateinit var accessRepository: AccessRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        // The overlay permission survives reboots, but it can be revoked while the device
        // is off. Without it BubbleService stops itself immediately, so skip the start.
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Skipping boot start: overlay permission not granted")
            return
        }

        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                if (!shouldStartBubbleOnBoot(preferencesDataStore, accessRepository)) return@launch
                BubbleService.start(appContext)
            } catch (e: Exception) {
                // A denied background start must not crash the boot broadcast.
                Log.e(TAG, "Failed to start bubble service on boot", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}

/**
 * Whether a reboot should bring the bubble back.
 *
 * A free function because the receiver itself is a Hilt entry point, and the decision — not the
 * injection — is the part worth pinning: a blocked account must stay blocked across a reboot
 * rather than getting a working bubble until the first refresh happens to succeed.
 *
 * `currentDecision()` reads the stored verdict from disk and never the network, which is what
 * makes it usable here at all: a boot broadcast has seconds to live and may well run before
 * any connectivity exists.
 */
internal suspend fun shouldStartBubbleOnBoot(
    preferencesDataStore: PreferencesDataStore,
    accessRepository: AccessRepository
): Boolean {
    if (!preferencesDataStore.preferences.first().startOnBoot) return false
    if (accessRepository.currentDecision() is AccessDecision.Blocked) {
        Log.i("BootReceiver", "Skipping boot start: account is not allowed to dictate")
        return false
    }
    return true
}
