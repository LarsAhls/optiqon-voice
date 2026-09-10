package se.optiqon.voice.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.optiqon.voice.testing.MODULE_DIR
import java.io.File

/**
 * The link between the two services, across the accessibility service being turned off and on.
 *
 * F15: the user turns the accessibility service off and on again, and the bubble never reacts to
 * the keyboard afterwards. Everything looks alive — both services run, the bubble can be dragged —
 * and nothing works. The cause is ownership: `keyboardListener` was a free companion field that
 * *`TextInjectorService.onDestroy()`* set to null, while the only thing that ever set it was
 * `BubbleService.onStartCommand()`. One service cancelled the other's registration, and the only
 * code that could restore it had already run.
 *
 * So the registration is now owned by whoever made it: the bubble registers and the bubble
 * unregisters, and the accessibility service coming and going says something about the *keyboard*,
 * never about who is listening for it.
 *
 * These tests render that lifecycle matrix. They cannot start either service — both are real
 * Android services, one an `@AndroidEntryPoint` putting `WindowManager` overlays on screen, and
 * there is no `hilt-android-testing` in this module — so the matrix is played against the link
 * itself, and the two services' wiring into it is read from source.
 */
class KeyboardLinkLifecycleTest {

    private class Spy : TextInjectorService.KeyboardListener {
        val heard = mutableListOf<Boolean>()
        override fun onKeyboardVisibilityChanged(visible: Boolean) {
            heard += visible
        }
    }

    // --- The lifecycle matrix -------------------------------------------------------------------

    @Test
    fun `the bubble still hears the keyboard after the accessibility service is turned off and on`() {
        val link = KeyboardLink()
        val bubble = Spy()
        link.listen(bubble)

        link.accessibilityConnected(imeVisible = false)
        bubble.heard.clear()
        link.report(imeVisible = true, editableFocused = false)
        assertEquals("The bubble did not hear the keyboard before the restart.", listOf(true), bubble.heard)

        // The user turns the accessibility service off and on. BubbleService is not touched:
        // no onStartCommand, no onDestroy, nothing that could re-register anything.
        link.accessibilityGone()
        link.accessibilityConnected(imeVisible = false)
        bubble.heard.clear()

        link.report(imeVisible = true, editableFocused = false)
        assertEquals(
            "This is F15. The accessibility service came back, the user tapped a text field, and " +
                "the bubble heard nothing - because something other than the bubble dropped the " +
                "bubble's registration while it was away. Everything is alive and nothing works.",
            listOf(true),
            bubble.heard
        )
    }

    @Test
    fun `a keyboard cannot stay up while the service that sees it is gone`() {
        val link = KeyboardLink()
        val bubble = Spy()
        link.listen(bubble)
        link.report(imeVisible = true, editableFocused = false)
        bubble.heard.clear()

        link.accessibilityGone()

        assertFalse(
            "The link still claims the keyboard is visible after the only service that can see " +
                "windows went away. That claim can never be corrected by an edge: the next report " +
                "comes from a fresh service, and if it agrees, nothing is reported at all.",
            link.isKeyboardVisible
        )
        assertEquals(
            "The bubble was not told the keyboard is gone. It sits on screen over an app with no " +
                "text field in focus, and the only thing that would hide it is a change it has " +
                "already been told did not happen.",
            listOf(false),
            bubble.heard
        )
    }

    @Test
    fun `coming back states the truth rather than trusting what was cached`() {
        val link = KeyboardLink()
        val bubble = Spy()
        link.listen(bubble)

        link.accessibilityConnected(imeVisible = true)

        assertTrue(link.isKeyboardVisible)
        assertEquals(
            "A service connecting while the keyboard is already up said nothing. The bubble only " +
                "ever learns of edges, so it would wait for the user to dismiss and re-open the " +
                "keyboard before it appeared at all.",
            listOf(true),
            bubble.heard
        )
    }

    @Test
    fun `nobody listening is not an error`() {
        val link = KeyboardLink()
        link.accessibilityConnected(imeVisible = true)
        link.report(imeVisible = false, editableFocused = false)
        link.accessibilityGone()
        assertFalse(link.isKeyboardVisible)
    }

    @Test
    fun `the bubble that registered is the one that unregisters`() {
        val link = KeyboardLink()
        val old = Spy()
        val new = Spy()

        link.listen(old)
        link.listen(new)
        // The old service is destroyed after the new one has taken over - START_STICKY restarts
        // overlap, and onDestroy of the outgoing instance runs whenever it runs.
        link.stopListening(old)

        link.report(imeVisible = true, editableFocused = false)
        assertEquals(
            "A departing service unregistered the listener of the one that replaced it. That is " +
                "the same defect as F15 with the services swapped: the live bubble hears nothing " +
                "and nothing will ever register again.",
            listOf(true),
            new.heard
        )
        assertTrue("The replaced listener is still being called.", old.heard.isEmpty())
    }

    @Test
    fun `unregistering stops the reports`() {
        val link = KeyboardLink()
        val bubble = Spy()
        link.listen(bubble)
        link.stopListening(bubble)

        link.report(imeVisible = true, editableFocused = false)
        assertTrue(
            "A destroyed bubble service is still being called about the keyboard. Its scope is " +
                "cancelled and its window is gone, so every report is at best wasted and at worst " +
                "an attempt to add an overlay from a dead service.",
            bubble.heard.isEmpty()
        )
    }

    @Test
    fun `the reporting rule is the one that was already pinned down`() {
        val link = KeyboardLink()
        val bubble = Spy()
        link.listen(bubble)

        link.report(imeVisible = true, editableFocused = false)
        link.report(imeVisible = true, editableFocused = false)
        assertEquals("An unchanged keyboard was re-reported.", listOf(true), bubble.heard)

        link.report(imeVisible = true, editableFocused = true)
        assertEquals(
            "Focusing a text field did not re-state a visible keyboard. See " +
                "KeyboardVisibilityReportTest: that re-statement is what rescues a cached flag " +
                "that says `visible` while no bubble is on screen.",
            listOf(true, true),
            bubble.heard
        )
    }

    // --- The wiring, read from source ----------------------------------------------------------

    private val injector: String = source("TextInjectorService.kt")
    private val bubble: String = source("BubbleService.kt")

    @Test
    fun `the accessibility service does not cancel a registration it did not make`() {
        val onDestroy = blockOf(injector, "TextInjectorService.kt", "override fun onDestroy()")
        assertFalse(
            "TextInjectorService.onDestroy() clears the keyboard listener. That listener belongs " +
                "to BubbleService, which is still running and has no way of noticing: the only " +
                "code that registers it is onStartCommand, which already ran. This is F15, and " +
                "this line is it.\n$onDestroy",
            Regex("""(keyboardListener|listen\w*)\s*=\s*null""").containsMatchIn(onDestroy)
        )
        assertTrue(
            "TextInjectorService.onDestroy() does not tell the link it is gone. The link keeps " +
                "claiming a keyboard that nothing can see any more, and the bubble keeps waiting " +
                "for an edge that will not come.\n$onDestroy",
            onDestroy.contains("accessibilityGone()")
        )
    }

    @Test
    fun `reconnection happens where the service actually comes back`() {
        val onServiceConnected =
            blockOf(injector, "TextInjectorService.kt", "override fun onServiceConnected()")
        assertTrue(
            "onServiceConnected() does not re-state the keyboard. Coming back is the one moment " +
                "the truth is known and the bubble's picture of it is certainly stale, and it is " +
                "the moment the debt review named: reconnection belongs where the service " +
                "reconnects, not in the other service's start command.\n$onServiceConnected",
            onServiceConnected.contains("accessibilityConnected(")
        )
    }

    @Test
    fun `the registration is held in one owned place`() {
        assertFalse(
            "Something still assigns TextInjectorService.keyboardListener directly. A free " +
                "companion field is what let one service clear another's registration; moving the " +
                "fix in while leaving the field writable leaves the defect one assignment away.",
            Regex("""TextInjectorService\.keyboardListener\s*=""").containsMatchIn(bubble + injector)
        )
        assertFalse(
            "TextInjectorService still exposes a mutable keyboard flag of its own. Two " +
                "representations of whether the keyboard is up is how the stale-cache defect in " +
                "KeyboardVisibilityReportTest happened in the first place.",
            Regex("""(?m)^\s*(@Volatile\s+)?var\s+isKeyboardVisible""").containsMatchIn(injector)
        )
    }

    @Test
    fun `the bubble registers and unregisters itself`() {
        assertTrue(
            "BubbleService does not register itself with the link. Nothing reaches the bubble " +
                "about the keyboard at all.",
            Regex("""keyboardLink\.listen\(""").containsMatchIn(bubble)
        )
        assertTrue(
            "BubbleService.onDestroy() does not unregister its listener. A destroyed service " +
                "keeps being called about the keyboard, and its replacement's registration is the " +
                "one that gets overwritten.",
            blockOf(bubble, "BubbleService.kt", "override fun onDestroy()")
                .contains("keyboardLink.stopListening(")
        )
    }

    private fun source(name: String): String =
        File(MODULE_DIR, "src/main/java/se/optiqon/voice/service/$name").readText()

    /** The body of a declaration, balanced across nested braces. */
    private fun blockOf(source: String, file: String, signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "No `$signature` in $file; this guard has gone stale" }
        var depth = 0
        val open = source.indexOf('{', start)
        var j = open
        while (j < source.length) {
            when (source[j]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, j + 1)
                }
            }
            j++
        }
        error("Unbalanced braces after `$signature` in $file")
    }
}
