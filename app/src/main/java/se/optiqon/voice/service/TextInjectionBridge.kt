package se.optiqon.voice.service

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import se.optiqon.voice.R
import se.optiqon.voice.data.preferences.PreferencesDataStore
import se.optiqon.voice.domain.access.AccessLease
import se.optiqon.voice.domain.model.AppContext
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TextInjectionBridge @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesDataStore: PreferencesDataStore
) {
    companion object {
        private const val TAG = "TextInjectionBridge"
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Writes [text] into the focused app, or falls back to the clipboard.
     *
     * [lease] is re-validated as the very last thing before the write. It is required rather
     * than optional because this is the point of no return: text handed to another app cannot
     * be recalled, so an injection that has not yet happened is the last thing that can still
     * be refused, and the caller must be able to say whose text it is.
     */
    suspend fun inject(text: String, lease: AccessLease): Boolean {
        val injector = TextInjectorService.instance
        if (injector != null) {
            // Every step of injection is a blocking call into the focused app, and
            // getSurroundingText is documented as slow, so it must not run on the main
            // dispatcher the bubble calls this from.
            val injectionResult = withContext(Dispatchers.Default) {
                // Inside the dispatch, immediately before the call: nothing suspends between
                // this line and the write. Checking at the top of the function instead would
                // leave the preference read and the thread switch inside the window.
                lease.requireValid()
                injector.injectText(text)
            }
            if (injectionResult is InjectionResult.Success) return true

            if (injectionResult is InjectionResult.BlockedSensitive) {
                Log.d(TAG, "Injection blocked: sensitive field")
                mainHandler.post {
                    Toast.makeText(context, "Dictation blocked in sensitive field", Toast.LENGTH_SHORT).show()
                }
                return false
            }
        }

        // The clipboard is an effect too: it leaves the dictation where any app can read it.
        val autoClipboard = preferencesDataStore.preferences.first().autoClipboard
        if (autoClipboard) {
            lease.requireValid()
            copyToClipboard(text)
        } else {
            mainHandler.post {
                Toast.makeText(context, "Could not inject text (clipboard fallback disabled)", Toast.LENGTH_SHORT).show()
            }
        }
        return false
    }

    val isAccessibilityServiceActive: Boolean
        get() = TextInjectorService.instance != null

    val focusedAppName: String?
        get() = currentAppContext?.displayName

    val currentAppContext: AppContext?
        get() {
            val injector = TextInjectorService.instance ?: return null
            val packageName = injector.getFocusedAppPackageName()?.takeIf { isUsableTargetPackage(it) }
                ?: return null
            val appLabel = injector.getAppName(packageName)?.takeIf { isMeaningfulLabel(it) }
                ?: labelFromPackage(packageName)
            return AppContext(label = appLabel, packageName = packageName)
        }

    private fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("dictation", text))
        mainHandler.post {
            Toast.makeText(context, R.string.clipboard_fallback_toast, Toast.LENGTH_SHORT).show()
        }
    }

    private fun isUsableTargetPackage(packageName: String): Boolean {
        val trimmed = packageName.trim()
        return trimmed.isNotBlank() && trimmed != context.packageName
    }

    private fun isMeaningfulLabel(label: String): Boolean {
        val trimmed = label.trim()
        return trimmed.isNotBlank() && !trimmed.equals("App", ignoreCase = true)
    }

    private fun labelFromPackage(packageName: String): String? {
        val ignoredSegments = setOf(
            "android",
            "app",
            "apps",
            "client",
            "com",
            "debug",
            "im",
            "io",
            "mobile",
            "net",
            "org",
            "release",
            "x"
        )
        val segment = packageName.split('.')
            .firstOrNull { part ->
                val normalized = part.lowercase()
                normalized.length > 1 && normalized !in ignoredSegments
            }
            ?: packageName.substringAfterLast('.').takeIf { it.isNotBlank() }
        return segment?.replace('_', ' ')?.replace('-', ' ')?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.split(' ')
            ?.joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
    }
}
