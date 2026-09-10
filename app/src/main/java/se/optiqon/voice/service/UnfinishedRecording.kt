package se.optiqon.voice.service

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.optiqon.voice.domain.access.runCatchingCancellable
import java.io.File

private const val TAG = "UnfinishedRecording"

/**
 * Why a preserved dictation has no text: the recorder was stopped before it could be
 * transcribed. The message is what the user sees on the history row.
 */
class RecordingStoppedException :
    IllegalStateException("Dictation stopped before it could be transcribed")

/**
 * What is left of a recording that will never be transcribed, gathered before the fields holding
 * it are cleared.
 *
 * @param recordingJob the coroutine writing the PCM. Joined, never cancelled, so the tail of the
 *   recording is on disk before anything reads it. The caller has already signalled the recorder
 *   to stop; without that this would wait for a recording that is still running.
 * @param convert PCM in, WAV out, on whichever dispatcher the caller considers right for IO. Only
 *   called when there is a [pcm] still to convert; a recording taken after the conversion already
 *   happened has nothing for it to do.
 * @param preserve hands the WAV and its duration to the user's own storage.
 * @param onDone runs after the temp files go, whether or not anything was preserved.
 */
internal class UnfinishedRecording(
    val recordingJob: Job?,
    val pcm: File?,
    val wav: File,
    val durationMs: Long,
    val convert: suspend (File, File) -> Unit = { _, _ -> },
    val preserve: suspend (File, Long) -> Unit,
    val onDone: () -> Unit = {},
)

/**
 * Tears down the coroutines a service owns without losing a recording that was still running.
 *
 * **Both scopes are parameters, and that is the whole point.** The preserving used to be launched
 * in the service's own scope. `onDestroy` read:
 *
 * ```
 * stopRecordingAndWait()   // signalled the recorder, did not wait, returned
 * removeBubble()
 * scope.cancel()           // the scope the recording's coroutine belongs to
 * ```
 *
 * so stopping the service mid-recording dropped the dictation: no WAV, no history row, no
 * message. The user lost what they had just said and was not told. The name said the opposite of
 * what the code did, which is the expensive part — the next reader believes the wait is there.
 *
 * Cancelling the service scope lives here rather than at the call site so that handing the
 * recording over is not something a caller can forget to do: the two are now one step with one
 * name. The order of the two statements, measured, is not what carries the fix — swapping them
 * keeps every test green, because the recording coroutine is joined either way. The scope choice
 * is what carries it. Pass the same scope twice and the defect is back, which is why the call
 * site is pinned by a test that reads it.
 *
 * @return the job doing the preserving, or null when nothing was recording.
 */
internal fun cancelKeepingRecording(
    serviceScope: CoroutineScope,
    survivingScope: CoroutineScope,
    unfinished: UnfinishedRecording?,
): Job? {
    val preserving = unfinished?.let { preserveUnfinishedRecording(survivingScope, it) }
    serviceScope.cancel()
    return preserving
}

/**
 * Moves audio out of the reach of a coroutine that is about to be cancelled, and returns where it
 * now is — or null when there was nothing worth taking, in which case nothing was moved.
 *
 * A coroutine that owns a working file deletes it in its `finally`, and cancelling it is what
 * makes that run. Telling it not to would mean a flag the two sides have to agree about, on
 * whichever threads they happen to be on; renaming the file needs nobody to agree about anything.
 * Afterwards the path the dying coroutine knows is empty, and what the user said is somewhere
 * else.
 *
 * Both paths are in the same directory, so the rename is a directory operation rather than a copy.
 * The copy is there for the case that cannot happen, because losing the dictation is worse than
 * copying a few hundred kilobytes.
 */
internal fun takeAudioFromCancelledJob(audio: File, to: File): File? {
    if (!audio.exists() || audio.length() == 0L) {
        Log.w(TAG, "No audio to take from the cancelled job")
        return null
    }
    if (audio.renameTo(to)) return to
    return try {
        audio.copyTo(to, overwrite = true).also {
            Log.w(TAG, "Renaming the audio failed; copied it instead")
        }
    } catch (e: java.io.IOException) {
        Log.e(TAG, "Could not take the audio out of the cancelled job's reach", e)
        null
    }
}

/**
 * Waits for the recorder to let go of the file, converts what it managed to write, and hands
 * that to the user's storage before the working files go.
 *
 * The body runs under [NonCancellable] for the second half of the same problem: once the audio
 * exists, a cancellation arriving mid-write would lose it by way of the mechanism meant to stop
 * the effects rather than the data. Cleanup is in `finally`, so the temp files go either way.
 */
internal fun preserveUnfinishedRecording(
    scope: CoroutineScope,
    unfinished: UnfinishedRecording,
): Job = scope.launch {
    val pcm = unfinished.pcm
    try {
        withContext(NonCancellable) {
            // runCatchingCancellable, not runCatching: a swallowed CancellationException here
            // would carry on inside a coroutine that is already dead. See
            // domain/access/Cancellation.kt.
            runCatchingCancellable { unfinished.recordingJob?.join() }
                .onFailure { Log.w(TAG, "Waiting for the recorder to finish failed", it) }
            val wav = unfinished.wav
            when {
                // Mid-recording: the recorder wrote PCM and nothing has converted it yet.
                pcm != null ->
                    if (pcm.exists() && pcm.length() > 0L) {
                        unfinished.convert(pcm, wav)
                        unfinished.preserve(wav, unfinished.durationMs)
                    } else {
                        Log.w(TAG, "Nothing recorded to preserve")
                    }
                // Mid-transcription: the PCM is already gone and the WAV is what the user said.
                // Without this branch a shutdown one state later loses the dictation exactly as
                // a shutdown mid-recording used to, and just as quietly.
                wav.exists() && wav.length() > 0L ->
                    unfinished.preserve(wav, unfinished.durationMs)
                else -> Log.w(TAG, "Nothing recorded to preserve")
            }
        }
    } finally {
        // Temp files in cacheDir only. Anything the user is entitled to keep has by now been
        // copied into their own storage, under their own history and retention settings.
        pcm?.delete()
        unfinished.wav.delete()
        unfinished.onDone()
    }
}
