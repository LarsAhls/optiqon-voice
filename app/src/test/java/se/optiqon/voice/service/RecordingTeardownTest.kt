package se.optiqon.voice.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import se.optiqon.voice.testing.MODULE_DIR
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * What happens to a dictation when the service carrying it is destroyed mid-recording.
 *
 * The defect these tests pin cost the user their audio in silence. `onDestroy` called a function
 * named `stopRecordingAndWait()` that did not wait — it signalled the recorder and returned — and
 * then ran `scope.cancel()` two lines later on the very scope the recording's coroutine belonged
 * to. Nothing converted the PCM, nothing wrote a history row, nothing told the user anything. The
 * name promising the wait is what made it survive review.
 *
 * `BubbleService` itself cannot be driven here: it is an `@AndroidEntryPoint` Service that puts
 * `WindowManager` overlays on screen and reads a real `AudioRecord`, and this module has no
 * `hilt-android-testing`. So the teardown was extracted into [cancelKeepingRecording], which is
 * what production calls, and the part no test can reach — that `onDestroy` really does route
 * through it — is pinned by reading the source in the last test below. Neither half is sufficient
 * alone: the behaviour tests would pass against a function nobody calls, and the source check
 * would pass against a function that loses the audio.
 *
 * The fixtures model the recorder as it actually behaves: an unbuffered stream inside `use {}`,
 * writing its last buffer on the way out. That last buffer is the whole reason the preserving has
 * to join the recording coroutine instead of reading the file while it is still open.
 */
class RecordingTeardownTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()
    private val dispatchers = mutableListOf<java.util.concurrent.ExecutorService>()

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        dispatchers.forEach { it.shutdownNow() }
    }

    @Test
    fun `a recording outlives the service that was carrying it`() {
        val f = Fixture()

        f.destroyService()
        f.awaitPreserving()

        assertArrayEquals(
            "The dictation the user had just spoken never reached preservation. Destroying the " +
                "service dropped it: no WAV, no history row, nothing said. If the preserving is " +
                "launched in the service's own scope, cancelling that scope one line later means " +
                "the coroutine never runs at all.",
            HEAD + TAIL,
            f.preservedBytes()
        )
        assertEquals(
            "The duration handed to preservation is not the one measured when the recording was " +
                "taken; the history row would misreport how long the user spoke.",
            DURATION_MS,
            f.preservedDuration()
        )
    }

    @Test
    fun `the recorder is allowed to finish writing before the file is read`() {
        val f = Fixture()

        f.destroyService()
        f.awaitPreserving()

        // Cancelling the service scope cancels the recording coroutine too — by design, the
        // recorder must stop. What must not happen is reading the file before that coroutine has
        // run its way out of `use {}`: its last buffer is still to come.
        assertTrue(
            "What was preserved is missing the tail of the recording, so the file was read while " +
                "the recorder still had it open. The preserving has to join the recording " +
                "coroutine; signalling the recorder to stop is not the same as waiting for it.",
            f.preservedBytes().size == (HEAD + TAIL).size
        )
    }

    @Test
    fun `the working files go and the recorder is let go of, whatever happened`() {
        val f = Fixture()

        f.destroyService()
        f.awaitPreserving()

        assertFalse(
            "The PCM was left behind in the cache. On the destroy path nothing else will ever " +
                "come back for it.",
            f.pcm.exists()
        )
        assertFalse("The intermediate WAV was left behind in the cache.", f.wav.exists())
        assertEquals(
            "The teardown callback did not run exactly once; the recorder and the silence " +
                "detector are either still held or were released twice.",
            1,
            f.doneCalls.get()
        )
    }

    @Test
    fun `nothing recorded is not an error, and still cleans up`() {
        val f = Fixture(writeAnything = false)

        f.destroyService()
        f.awaitPreserving()

        assertFalse(
            "An empty recording was handed to preservation. A zero-length history row tells the " +
                "user nothing and cannot be retried.",
            f.preserveCalled
        )
        assertFalse("The empty PCM was left behind in the cache.", f.pcm.exists())
        assertEquals("The teardown callback did not run.", 1, f.doneCalls.get())
    }

    @Test
    fun `a cancellation arriving mid-preserve does not lose the audio`() {
        val f = Fixture(blockInPreserve = true)

        f.destroyService()
        runBlocking { withTimeout(TIMEOUT_MS) { f.insidePreserve.await() } }
        // Whoever owns the surviving scope can be torn down too — the process is ending, the user
        // signed out, a test finished. Once the audio exists, that must not be what loses it.
        f.applicationScope.cancel()
        f.releasePreserve.complete(Unit)
        f.awaitPreserving()

        assertTrue(
            "Preservation was interrupted by a cancellation of the scope it runs in, and the " +
                "audio went with it. The body has to run under NonCancellable: the point of " +
                "cancellation is to stop the effects, not to throw away what was already " +
                "captured.",
            f.preserveCompleted
        )
    }

    @Test
    fun `the service really does route its destroy through this teardown`() {
        // None of the above touches BubbleService, so on its own it would pass while production
        // kept its own copy of the teardown. This reads the source instead. It is the half of the
        // defect no behaviour test can reach: the choice of scope is made here, at the call site,
        // and a call site passing the wrong one leaves every test above green.
        val source = File(MODULE_DIR, "src/main/java/se/optiqon/voice/service/BubbleService.kt")
            .readText()
        val onDestroy = blockOf(source, "override fun onDestroy()")

        assertTrue(
            "onDestroy does not hand the recording to cancelKeepingRecording. Whatever it does " +
                "instead is untested, and the last version of it lost the user's audio. " +
                "onDestroy reads:\n$onDestroy",
            Regex("""cancelKeepingRecording\(\s*scope\s*,\s*applicationScope\s*,""")
                .containsMatchIn(onDestroy)
        )
        assertFalse(
            "onDestroy cancels the service scope itself. That is the defect: whatever is launched " +
                "in that scope to keep the recording dies here, and the order of the two " +
                "statements is left to whoever edits this function next. Cancelling belongs " +
                "inside cancelKeepingRecording, after the hand-off.\n$onDestroy",
            onDestroy.contains("scope.cancel()")
        )
        assertFalse(
            "onDestroy passes the service's own scope as the surviving one. Then the recording " +
                "is launched in a scope that is cancelled immediately afterwards and the " +
                "dictation is lost exactly as before, with the fix in place and inoperative.\n" +
                onDestroy,
            Regex("""cancelKeepingRecording\(\s*scope\s*,\s*scope\s*,""").containsMatchIn(onDestroy)
        )

        // Audio focus is the one effect of the teardown that has no surviving owner: the service
        // is the thing that holds it, and `abandonRecordingAudioFocus` touches AudioManager, so
        // there is nothing a unit test here can drive. Leaving it held mutes the user's music
        // until something else happens to take focus. Measured: dropping the call keeps every
        // behaviour test above green, which is why this reads the source instead.
        val taking = blockOf(source, "private fun takeRecordingInProgress()")
        assertTrue(
            "takeRecordingInProgress does not abandon audio focus. Nothing downstream will: the " +
                "service is what holds it, and the scope that would otherwise carry the release " +
                "is the one being cancelled. It has to happen here, synchronously.\n" + taking,
            taking.contains("abandonRecordingAudioFocus()")
        )

        // The state one step later. The behaviour test below drives the seam directly, so it stays
        // green even when the service never reaches it: the dispatch is the only place that says
        // a shutdown mid-transcription is a shutdown with audio worth keeping. Measured: removing
        // this branch keeps every behaviour test in this class green.
        val dispatch = blockOf(source, "private fun takeUnfinishedRecording()")
        assertTrue(
            "takeUnfinishedRecording does not reach the mid-transcription teardown. The recorder " +
                "has stopped, the WAV is on disk and the transcriber has not answered yet - and " +
                "a destroy in that window throws the dictation away silently, which is the " +
                "defect this class exists to keep closed.\n" + dispatch,
            Regex("""is ServiceState\.Transcribing[\s\S]{0,160}?takeAudioAwaitingTranscription\(""")
                .containsMatchIn(dispatch)
        )

        // Where the audio is has to be findable from outside the coroutine that owns it, because
        // that coroutine is the one being cancelled.
        assertTrue(
            "Nothing records the WAV being transcribed, so the mid-transcription teardown has " +
                "no file to find and returns null. The branch above would be present and " +
                "inoperative.",
            Regex("""processingWav\s*=\s*wavFile""").containsMatchIn(source)
        )

        // And it has to be moved, not merely pointed at: the cancelled request deletes the path
        // it knows in its own `finally`, which cancellation is exactly what runs.
        val awaiting = blockOf(source, "private fun takeAudioAwaitingTranscription()")
        assertTrue(
            "The mid-transcription teardown hands over the audio without taking it out of the " +
                "cancelled request's reach. That request's `finally` deletes the path it knows " +
                "about, so the preserving would race a deletion it cannot see.\n" + awaiting,
            awaiting.contains("takeAudioFromCancelledJob(")
        )
        assertTrue(
            "The mid-transcription teardown does not pass on the duration measured when the " +
                "recording stopped, so the preserved history row would misreport how long the " +
                "user spoke. Nothing here can drive the real service, hence the source read.\n" +
                awaiting,
            awaiting.contains("processingDurationMs")
        )

        // The surviving scope has to come from somewhere that really does outlive the service.
        val module = File(MODULE_DIR, "src/main/java/se/optiqon/voice/di/AccessModule.kt").readText()
        assertTrue(
            "No @Singleton CoroutineScope is provided, so the scope injected into BubbleService " +
                "as applicationScope is either absent or not application-lived. A scope bound to " +
                "anything shorter reintroduces the defect on a longer fuse.",
            Regex("""@Singleton[\s\S]{0,200}?CoroutineScope""").containsMatchIn(module)
        )
    }

    @Test
    fun `audio already converted survives a shutdown during transcription`() {
        val f = TranscribingFixture()

        f.destroyService()
        f.awaitPreserving()

        assertArrayEquals(
            "The user had finished speaking and the WAV was on disk, waiting for the transcriber, " +
                "when the service went down — and it was thrown away. This is the same loss as " +
                "mid-recording, one state later: there is no PCM left to convert, so a teardown " +
                "that only looks for a PCM finds nothing to keep and says nothing about it.",
            SPOKEN,
            f.preservedBytes()
        )
        assertEquals(
            "The duration handed to preservation is not the one measured when the recording " +
                "stopped; the history row would misreport how long the user spoke.",
            DURATION_MS,
            f.preservedDuration()
        )
    }

    @Test
    fun `there is nothing to hand over when the file is empty`() {
        val empty = File(tmp.root, "recording_empty.wav").also { it.createNewFile() }
        val to = File(tmp.root, "interrupted_empty.wav")

        assertEquals(
            "An empty working file was handed over as if it were a dictation. The caller builds " +
                "an UnfinishedRecording around whatever comes back, so a path to nothing becomes " +
                "a history row about nothing - and the real audio is not there to be found later.",
            null,
            takeAudioFromCancelledJob(empty, to)
        )
        assertFalse(
            "Nothing was worth taking, and the file was moved anyway. A hand-over that reports " +
                "failure must not also have had an effect.",
            to.exists()
        )
    }

    /** The body of a function, balanced across nested braces. */
    private fun blockOf(source: String, signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "No `$signature` in BubbleService.kt; this guard has gone stale" }
        var depth = 0
        var i = source.indexOf('{', start)
        var j = i
        while (j < source.length) {
            when (source[j]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(i, j + 1)
                }
            }
            j++
        }
        error("Unbalanced braces after `$signature` in BubbleService.kt")
    }

    /**
     * A service mid-recording, modelled the way `BubbleService` is really shaped: a `Main`-like
     * single-threaded scope that the destroy runs on, with the recording coroutine launched in it
     * but doing its IO elsewhere.
     */
    private inner class Fixture(
        writeAnything: Boolean = true,
        private val blockInPreserve: Boolean = false,
    ) {
        private val serviceDispatcher = Executors.newSingleThreadExecutor { r ->
            Thread(r, "service-main")
        }.also { dispatchers += it }

        private val serviceScope =
            CoroutineScope(SupervisorJob() + serviceDispatcher.asCoroutineDispatcher())
                .also { scopes += it }

        val applicationScope =
            CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }

        val pcm: File = File(tmp.root, "recording.pcm")
        val wav: File = File(tmp.root, "recording.wav")

        val doneCalls = AtomicInteger()
        val insidePreserve = CompletableDeferred<Unit>()
        val releasePreserve = CompletableDeferred<Unit>()

        @Volatile var preserveCalled = false
        @Volatile var preserveCompleted = false
        @Volatile private var preservedFrom: ByteArray? = null
        @Volatile private var preservedDurationMs: Long = -1

        private val started = CompletableDeferred<Unit>()
        private val recordingJob: Job = serviceScope.launch(Dispatchers.IO) {
            FileOutputStream(pcm).use { out ->
                try {
                    if (writeAnything) out.write(HEAD)
                    started.complete(Unit)
                    awaitCancellation()
                } finally {
                    // The recorder's last buffer, reaching disk while the stream is still open.
                    // Unbuffered, like AudioRecorder's: the bytes are not what a cancellation
                    // loses — the preserving is.
                    if (writeAnything) {
                        Thread.sleep(RECORDER_TAIL_MS)
                        out.write(TAIL)
                    }
                }
            }
        }

        private var preserving: Job? = null

        init {
            runBlocking { withTimeout(TIMEOUT_MS) { started.await() } }
        }

        /** What `onDestroy` does, through the same function. */
        fun destroyService() {
            val unfinished = UnfinishedRecording(
                recordingJob = recordingJob,
                pcm = pcm,
                wav = wav,
                durationMs = DURATION_MS,
                convert = { from, to -> to.writeBytes(from.readBytes()) },
                preserve = { audio, duration ->
                    preserveCalled = true
                    if (blockInPreserve) {
                        insidePreserve.complete(Unit)
                        releasePreserve.await()
                    }
                    preservedFrom = audio.readBytes()
                    preservedDurationMs = duration
                    preserveCompleted = true
                },
                onDone = { doneCalls.incrementAndGet() },
            )
            // On the service's own thread, as onDestroy runs: that is what makes the old defect
            // deterministic rather than a race. A coroutine launched in a Main-dispatched scope
            // cannot start before the function that launched it returns, so a cancel on the next
            // line always wins.
            preserving = runBlocking(serviceDispatcher.asCoroutineDispatcher()) {
                cancelKeepingRecording(serviceScope, applicationScope, unfinished)
            }
        }

        fun awaitPreserving() {
            val job = requireNotNull(preserving) { "destroyService() was not called" }
            runBlocking { withTimeout(TIMEOUT_MS) { job.join() } }
        }

        fun preservedBytes(): ByteArray = requireNotNull(preservedFrom) {
            "Nothing was ever handed to preservation; the user's audio was dropped silently."
        }

        fun preservedDuration(): Long = preservedDurationMs
    }

    /**
     * A service one state later: the recorder is done, the WAV is on disk, and a transcription
     * request is in flight. Nothing is still writing the file — what can still lose it is the
     * teardown, and the `finally` of the very coroutine the teardown cancels.
     */
    private inner class TranscribingFixture {
        private val serviceDispatcher = Executors.newSingleThreadExecutor { r ->
            Thread(r, "service-main")
        }.also { dispatchers += it }

        private val serviceScope =
            CoroutineScope(SupervisorJob() + serviceDispatcher.asCoroutineDispatcher())
                .also { scopes += it }

        val applicationScope =
            CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }

        /** The working WAV, in the cache, exactly as the transcription coroutine left it. */
        val wav: File = File(tmp.root, "recording_1.wav").also { it.writeBytes(SPOKEN) }

        @Volatile private var preservedFrom: ByteArray? = null
        @Volatile private var preservedDurationMs: Long = -1

        private val transcribing = CompletableDeferred<Unit>()

        /**
         * Opened once the cancelled request has run its own `finally`. The preserving waits for it,
         * because otherwise whether the audio survives depends on which of two coroutines happens
         * to get there first - and measured, the lucky order is the common one.
         */
        private val deletionDone = CompletableDeferred<Unit>()

        /** The in-flight request, and the `finally` that deletes the file it knows about. */
        private val transcriptionJob: Job = serviceScope.launch {
            try {
                transcribing.complete(Unit)
                awaitCancellation()
            } finally {
                wav.delete()
            }
        }

        private var preserving: Job? = null

        init {
            runBlocking { withTimeout(TIMEOUT_MS) { transcribing.await() } }
        }

        /** What `onDestroy` does in this state, through the same functions production calls. */
        fun destroyService() {
            val taken = takeAudioFromCancelledJob(wav, File(tmp.root, "interrupted_1.wav"))
            val unfinished = UnfinishedRecording(
                recordingJob = null,
                pcm = null,
                wav = requireNotNull(taken) { "The audio was not taken out of the job's reach." },
                durationMs = DURATION_MS,
                convert = { _, _ -> error("There is no PCM left to convert in this state") },
                preserve = { audio, duration ->
                    deletionDone.await()
                    // Defensively, so that a lost file fails with the message below rather than an
                    // IOException out of a coroutine nobody is awaiting.
                    preservedFrom = if (audio.exists()) audio.readBytes() else null
                    preservedDurationMs = duration
                },
            )
            preserving = runBlocking(serviceDispatcher.asCoroutineDispatcher()) {
                cancelKeepingRecording(serviceScope, applicationScope, unfinished)
            }
            // The cancelled request's own cleanup, which deletes the path it knows about. If the
            // hand-over left the audio at that path, this is what loses it.
            runBlocking { withTimeout(TIMEOUT_MS) { transcriptionJob.join() } }
            deletionDone.complete(Unit)
        }

        fun awaitPreserving() {
            val job = requireNotNull(preserving) { "destroyService() was not called" }
            runBlocking { withTimeout(TIMEOUT_MS) { job.join() } }
        }

        fun preservedBytes(): ByteArray = requireNotNull(preservedFrom) {
            "Nothing was ever handed to preservation; the user's audio was dropped silently."
        }

        fun preservedDuration(): Long = preservedDurationMs
    }

    private companion object {
        val HEAD: ByteArray = ByteArray(4096) { (it % 251).toByte() }
        val SPOKEN: ByteArray = ByteArray(2048) { (it % 193).toByte() }
        val TAIL: ByteArray = ByteArray(512) { (it % 97).toByte() }
        const val DURATION_MS = 7531L
        const val RECORDER_TAIL_MS = 50L
        const val TIMEOUT_MS = 20_000L
    }
}
