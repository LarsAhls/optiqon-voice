package se.optiqon.voice.debug

import java.io.File

/**
 * What [AccessDebugReceiver] does once the Android plumbing is peeled off, so it can be
 * unit-tested without Hilt or a broadcast.
 */
class AccessDebugCommands(
    private val controls: AccessDebugControls,
    private val filesDir: File,
    private val idToken: () -> String?,
    private val synthetic: SyntheticState? = null,
    private val signOut: (suspend () -> Unit)? = null
) {
    data class Outcome(val ok: Boolean, val description: String)

    suspend fun handle(action: String?, args: Map<String, Any>): Outcome = when (action) {
        ACTION_FAIL_REFRESH -> {
            val enabled = args[EXTRA_ENABLED] as? Boolean
                ?: return Outcome(false, "$ACTION_FAIL_REFRESH needs --ez $EXTRA_ENABLED true|false")
            controls.failRefresh = enabled
            Outcome(true, "failRefresh=$enabled")
        }
        ACTION_CLOCK_OFFSET -> {
            val offset = (args[EXTRA_OFFSET_MS] as? Number)?.toLong()
                ?: return Outcome(false, "$ACTION_CLOCK_OFFSET needs --el $EXTRA_OFFSET_MS <millis>")
            controls.clockOffsetMs = offset
            Outcome(true, "clockOffsetMs=$offset")
        }
        ACTION_EXPORT_ID_TOKEN -> {
            val token = idToken()
            if (token == null) {
                Outcome(false, "no signed-in Firebase user; nothing written")
            } else {
                val target = File(File(filesDir, "debug"), TOKEN_FILE_NAME)
                target.parentFile?.mkdirs()
                target.writeText(token)
                // Length only. The token itself must never reach a log or a broadcast result.
                Outcome(true, "id token written to files/debug/$TOKEN_FILE_NAME (${token.length} chars)")
            }
        }
        // The app ends its own process on the way out of a sign-out, so this hook usually
        // reports a dead broadcast rather than a result. That is the sign-out working, not
        // failing; what it did is read back from disk.
        ACTION_SIGN_OUT -> signOut?.let {
            it()
            Outcome(true, "signed out")
        } ?: Outcome(false, "sign-out is not wired in this process")
        ACTION_SEED_SYNTHETIC -> synthetic?.seed()?.let { Outcome(it.ok, it.description) }
            ?: Outcome(false, "synthetic state is not wired in this process")
        ACTION_DUMP_STATE -> synthetic?.dump()?.let { Outcome(it.ok, it.description) }
            ?: Outcome(false, "synthetic state is not wired in this process")
        else -> Outcome(false, "unknown action: $action")
    }

    companion object {
        const val ACTION_FAIL_REFRESH = "se.optiqon.voice.debug.FAIL_REFRESH"
        const val ACTION_CLOCK_OFFSET = "se.optiqon.voice.debug.CLOCK_OFFSET"
        const val ACTION_EXPORT_ID_TOKEN = "se.optiqon.voice.debug.EXPORT_ID_TOKEN"
        const val ACTION_SIGN_OUT = "se.optiqon.voice.debug.SIGN_OUT"
        const val ACTION_SEED_SYNTHETIC = "se.optiqon.voice.debug.SEED_SYNTHETIC"
        const val ACTION_DUMP_STATE = "se.optiqon.voice.debug.DUMP_STATE"
        const val EXTRA_ENABLED = "enabled"
        const val EXTRA_OFFSET_MS = "offsetMs"
        const val TOKEN_FILE_NAME = "id-token.txt"
    }
}
