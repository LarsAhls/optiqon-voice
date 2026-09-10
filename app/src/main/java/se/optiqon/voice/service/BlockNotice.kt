package se.optiqon.voice.service

import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import se.optiqon.voice.R
import se.optiqon.voice.domain.access.BlockReason

/** The sentence that explains a refused dictation, in the words the person is shown. */
@StringRes
internal fun blockedMessageRes(reason: BlockReason): Int = when (reason) {
    BlockReason.NOT_REGISTERED -> R.string.access_blocked_not_registered
    BlockReason.AWAITING_APPROVAL -> R.string.access_blocked_pending
    BlockReason.REJECTED -> R.string.access_blocked_rejected
    BlockReason.REVOKED -> R.string.access_blocked_revoked
    BlockReason.GRACE_EXPIRED -> R.string.access_blocked_grace_expired
}

/**
 * Tells the person why the dictation was refused, and hands back the same words for the
 * bubble to carry.
 */
internal fun announceBlock(context: Context, reason: BlockReason): String {
    val message = context.getString(blockedMessageRes(reason))
    // LENGTH_LONG rather than SHORT: unlike a failed transcription, none of these are fixed by
    // trying again, and the sentence names a step the person has to take somewhere else.
    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    return message
}
