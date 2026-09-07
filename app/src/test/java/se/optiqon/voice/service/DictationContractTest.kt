package se.optiqon.voice.service

import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end guard over the pure decisions that place a dictation in someone else's text
 * field. [TextInjectionTest] pins each function on its own; this pins the composition of
 * them, which is what a user actually experiences.
 *
 * The shell, theme and onboarding work does not touch the injection path, so this test
 * exists to notice if it ever does. A failure here means dictation behaviour changed, not
 * that a rule needs updating.
 */
class DictationContractTest {

    /** Runs the same sequence the injector runs once it holds a focused editable. */
    private fun dictateInto(
        rawText: String,
        hintText: String = "",
        isShowingHintText: Boolean = false,
        charsBeforeCursor: Int? = null,
        dictated: String
    ): String {
        val existing = resolveExistingText(rawText, hintText, isShowingHintText)
        val insertion = if (charsBeforeCursor != null) {
            insertionAtCursor(charsBeforeCursor, dictated)
        } else {
            insertionFor(existing, dictated)
        }
        return existing + insertion
    }

    @Test
    fun `dictating into an empty field yields the dictation alone`() {
        assertEquals("Hej, testar Optiqon Voice", dictateInto(rawText = "", dictated = " Hej, testar Optiqon Voice"))
    }

    @Test
    fun `dictating into a field showing a placeholder does not keep the placeholder`() {
        assertEquals(
            "Hej",
            dictateInto(rawText = "Message", hintText = "Message", isShowingHintText = true, dictated = " Hej")
        )
    }

    @Test
    fun `dictating after typed text appends without eating the separator`() {
        assertEquals(
            "see you at 8 or later",
            dictateInto(rawText = "see you at 8", hintText = "Message", dictated = " or later")
        )
    }

    @Test
    fun `an editor that declines to report the cursor keeps the speaker's spacing`() {
        assertEquals(
            "see you at 8 or later",
            dictateInto(rawText = "see you at 8", charsBeforeCursor = null, dictated = " or later")
        )
    }

    @Test
    fun `a cursor at the start of the field drops the leading space`() {
        assertEquals("Hej", dictateInto(rawText = "", charsBeforeCursor = 0, dictated = " Hej"))
    }

    @Test
    fun `protected fields are refused and ordinary ones are not`() {
        assertTrue(
            isSensitiveInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        )
        assertFalse(
            isSensitiveInputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        )
    }
}
