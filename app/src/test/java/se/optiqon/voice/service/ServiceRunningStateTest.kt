package se.optiqon.voice.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.optiqon.voice.testing.MODULE_DIR
import java.io.File

/**
 * Whether the home screen can be trusted about the bubble being on.
 *
 * `BubbleService.runningState` is what the hero reads, and what decides whether its button starts
 * the service or stops it. Nothing in this module can start the service — it is an
 * `@AndroidEntryPoint` Service putting `WindowManager` overlays on screen, and there is no
 * `hilt-android-testing` here — so what can be checked is that the two lifecycle callbacks really
 * are what publish it, and that the fact is held in one place.
 *
 * That matters because it was held in two. A `@Volatile var isRunning` whose private setter also
 * wrote the flow looked, to a search for readers of the var, like dead code: the debt review found
 * none and recommended deleting the line. The var was the flow's only writer, so deleting it would
 * have frozen the home screen on "The bubble is off" — with the button then starting a service that
 * was already running. The two halves are now one, and this test is what keeps them that way.
 */
class ServiceRunningStateTest {

    private val source: String =
        File(MODULE_DIR, "src/main/java/se/optiqon/voice/service/BubbleService.kt").readText()

    @Test
    fun `nothing is running before a service says so`() {
        assertFalse(
            "runningState starts out true, so the home screen claims the bubble is on before any " +
                "service has been created and its button offers to stop what is not there.",
            BubbleService.runningState.value
        )
    }

    @Test
    fun `the lifecycle is what publishes it`() {
        val onCreate = blockOf("override fun onCreate()")
        assertTrue(
            "onCreate does not publish that the service is running. The home screen would show " +
                "the bubble as off while it is on, and its button would offer to start a second " +
                "one.\n$onCreate",
            onCreate.contains("setRunning(true)")
        )

        val onDestroy = blockOf("override fun onDestroy()")
        assertTrue(
            "onDestroy does not publish that the service is gone. The flag then stays true for " +
                "the life of the process: the home screen shows a dead service as running, and " +
                "its button offers to stop something that is already stopped rather than to " +
                "start the bubble the user wants.\n$onDestroy",
            onDestroy.contains("setRunning(false)")
        )
    }

    @Test
    fun `the fact is held in one place`() {
        val companion = blockOf("companion object")
        assertFalse(
            "A second, mutable field holds whether the service runs. Two representations of one " +
                "fact is exactly how this went wrong: a var that looked unread and a flow that " +
                "looked unwritten. Either can be deleted or updated alone, and the home screen is " +
                "what lies afterwards.\n$companion",
            Regex("""(?m)^\s*(@Volatile\s+)?(private\s+)?var\s+\w*[rR]unning""")
                .containsMatchIn(companion)
        )
        assertEquals(
            "The running state is written from more than one place, or from none. One writer is " +
                "what makes it impossible for the flag and the flow to disagree; that is the " +
                "whole reason setRunning exists rather than an assignment at each call site.",
            1,
            Regex("""_runningState\.value\s*=""").findAll(source).count()
        )
    }

    /** The body of a declaration, balanced across nested braces. */
    private fun blockOf(signature: String): String {
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
}
