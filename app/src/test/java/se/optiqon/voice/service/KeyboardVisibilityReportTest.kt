package se.optiqon.voice.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule behind the bubble appearing when a text field is tapped.
 *
 * The bug this pins down: `isKeyboardVisible` lives in a companion object and so outlives the
 * accessibility service and the bubble it describes. Once it says "visible" while no bubble is
 * on screen, an edge-triggered check can never correct it — the user taps field after field
 * and nothing appears, until the app is restarted and the bubble is shown from the cached flag
 * at start-up. Observed twice on device c1f9837c during the G3 smoke, intermittently.
 */
class KeyboardVisibilityReportTest {

    @Test
    fun `a change is reported`() {
        assertTrue(shouldReportKeyboard(imeVisible = true, lastReported = false, editableFocused = false))
        assertTrue(shouldReportKeyboard(imeVisible = false, lastReported = true, editableFocused = false))
    }

    @Test
    fun `focusing a text field re-states a visible keyboard even when nothing changed`() {
        assertTrue(shouldReportKeyboard(imeVisible = true, lastReported = true, editableFocused = true))
    }

    @Test
    fun `an unchanged keyboard is not re-reported without a focused field`() {
        assertFalse(shouldReportKeyboard(imeVisible = true, lastReported = true, editableFocused = false))
        assertFalse(shouldReportKeyboard(imeVisible = false, lastReported = false, editableFocused = false))
    }

    @Test
    fun `focusing a text field with no keyboard up does not conjure a bubble`() {
        assertFalse(shouldReportKeyboard(imeVisible = false, lastReported = false, editableFocused = true))
    }
}
