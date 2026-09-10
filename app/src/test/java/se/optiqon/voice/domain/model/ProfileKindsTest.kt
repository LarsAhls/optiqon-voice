package se.optiqon.voice.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The registry is a lookup table with no compiler keeping it honest: nothing stops a seventh enum
 * value being added without a preset, and `ProfileKinds.of` would then quietly answer GENERAL for
 * it — a profile that declares EMAIL and is treated as if it declared nothing. That failure has no
 * symptom a tester could report, so it is asserted here instead.
 */
class ProfileKindsTest {

    @Test
    fun `every enum value has exactly one preset`() {
        assertEquals(
            "a kind without a preset silently falls back to GENERAL",
            ProfileKind.entries.toSet(),
            ProfileKinds.ALL.map { it.kind }.toSet()
        )
        assertEquals(
            "a kind with two presets makes `of` depend on list order",
            ProfileKind.entries.size,
            ProfileKinds.ALL.size
        )
    }

    @Test
    fun `of returns the preset for its own kind`() {
        ProfileKind.entries.forEach { kind ->
            assertEquals(kind, ProfileKinds.of(kind).kind)
        }
    }

    @Test
    fun `every preset has a label and a description the picker can show`() {
        ProfileKinds.ALL.forEach { preset ->
            assertTrue("${preset.kind} has a blank label", preset.label.isNotBlank())
            assertTrue("${preset.kind} has a blank description", preset.description.isNotBlank())
        }
    }

    @Test
    fun `labels are distinct, because the picker shows nothing else`() {
        val labels = ProfileKinds.ALL.map { it.label }
        assertEquals("two kinds share a label and become indistinguishable", labels.size, labels.toSet().size)
    }

    /**
     * GENERAL means "no declared tone, let the app decide" and VERBATIM means "no tone at all".
     * Both must stay null: a tone string on either would start emitting a section into a prompt
     * that is supposed to be unchanged (GENERAL) or untouched (VERBATIM).
     */
    @Test
    fun `only the declaring kinds carry a tone hint`() {
        assertNull("GENERAL must defer to the app guess", ProfileKinds.of(ProfileKind.GENERAL).toneHint)
        assertNull("VERBATIM must send no tone at all", ProfileKinds.of(ProfileKind.VERBATIM).toneHint)
        listOf(ProfileKind.EMAIL, ProfileKind.CHAT, ProfileKind.NOTES, ProfileKind.SOCIAL).forEach {
            assertNotNull("$it exists to state its tone", ProfileKinds.of(it).toneHint)
        }
    }

    @Test
    fun `tone hints are distinct, so the kind is recoverable from the prompt`() {
        val hints = ProfileKinds.ALL.mapNotNull { it.toneHint }
        assertEquals("two kinds emit the same tone line", hints.size, hints.toSet().size)
    }

    /**
     * VERBATIM's whole promise is that nothing is rewritten or dropped. A preset that suggested
     * otherwise would hand the user a profile that contradicts its own name on the next screen.
     */
    @Test
    fun `the verbatim preset suggests no rewriting and no condensing`() {
        val suggested = ProfileKinds.of(ProfileKind.VERBATIM).suggested
        assertEquals(RewriteMode.NONE, suggested.rewriteMode)
        assertEquals(SummarizeMode.NONE, suggested.summarizeMode)
        assertFalse(suggested.emojiAllowed)
    }

    /**
     * GENERAL is the default every migrated row lands on, and the suggestion it carries is what the
     * picker writes into the style fields if a tester taps it. Anything other than the `Profile`
     * defaults would make "pick GENERAL" a change rather than a reset to the plain state.
     */
    @Test
    fun `the general preset suggests exactly the profile defaults`() {
        val suggested = ProfileKinds.of(ProfileKind.GENERAL).suggested
        val default = Profile(name = "default")
        assertEquals(default.outputStyle, suggested.outputStyle)
        assertEquals(default.rewriteMode, suggested.rewriteMode)
        assertEquals(default.summarizeMode, suggested.summarizeMode)
        assertEquals(default.emojiAllowed, suggested.emojiAllowed)
    }

    /** The one preset in Mission 2 that was approved as changing visible output. */
    @Test
    fun `the notes preset is the decided LIGHT`() {
        assertEquals(SummarizeMode.LIGHT, ProfileKinds.of(ProfileKind.NOTES).suggested.summarizeMode)
    }
}
