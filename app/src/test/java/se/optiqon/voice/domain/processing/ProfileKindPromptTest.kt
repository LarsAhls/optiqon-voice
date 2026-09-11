package se.optiqon.voice.domain.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.optiqon.voice.domain.model.AppContext
import se.optiqon.voice.domain.model.OutputStyle
import se.optiqon.voice.domain.model.PostProcessingPrompt
import se.optiqon.voice.domain.model.Profile
import se.optiqon.voice.domain.model.ProfileKind
import se.optiqon.voice.domain.model.ProfileKinds
import se.optiqon.voice.domain.model.RewriteMode
import se.optiqon.voice.domain.model.SummarizeMode

/**
 * Mission 2's load-bearing invariant: a declared [ProfileKind] states the tone, and only
 * [ProfileKind.GENERAL] still lets the foreground app guess it.
 *
 * GENERAL is the value every row lands on when the v8 database is migrated, so a tester who
 * upgrades must get the same text out of the same dictation as before. That is asserted here as the
 * *absence* of any new section plus the *presence* of the app guess — the two halves that together
 * mean "the prompt did not change" — rather than against a pasted copy of the old prompt, which
 * would go stale the first time a wording changed and stop asserting anything.
 */
class ProfileKindPromptTest {

    private val builtIn = PostProcessingPrompt(id = 1, title = "Role", prompt = "BUILT_IN_ONE", builtIn = true)

    private fun profile(kind: ProfileKind, name: String = "Test") = Profile(name = name, profileKind = kind)

    private fun build(profile: Profile, app: AppContext?) =
        SystemPromptBuilder.build(profile, app, listOf(builtIn))

    private val mail = AppContext(label = "Gmail", packageName = "com.google.android.gm")
    private val chat = AppContext(label = "Slack", packageName = "com.Slack")
    // Named for what it is rather than "unknown": `inferStyleForApp` matches the bare substring
    // "x" for social apps, so a package like this one does match something. The sweep below only
    // cares that GENERAL gains no Tone: section, which holds either way.
    private val other = AppContext(label = "Kalkylator", packageName = "se.example.calc")

    private val emailTone = requireNotNull(ProfileKinds.of(ProfileKind.EMAIL).toneHint)
    private val chatTone = requireNotNull(ProfileKinds.of(ProfileKind.CHAT).toneHint)

    private fun everyStyleCombination(kind: ProfileKind): List<Profile> =
        OutputStyle.entries.flatMap { style ->
            RewriteMode.entries.flatMap { rewrite ->
                SummarizeMode.entries.flatMap { summarize ->
                    listOf(true, false).map { emoji ->
                        Profile(
                            name = "Test",
                            profileKind = kind,
                            outputStyle = style,
                            rewriteMode = rewrite,
                            summarizeMode = summarize,
                            emojiAllowed = emoji
                        )
                    }
                }
            }
        }

    // ---- The upgrade invariant ------------------------------------------------------

    @Test
    fun `a GENERAL profile emits no declared tone section at all`() {
        assertNull(SystemPromptBuilder.declaredToneSection(profile(ProfileKind.GENERAL)))
    }

    @Test
    fun `no style combination of GENERAL introduces a tone section`() {
        listOf(null, mail, chat, other).forEach { app ->
            everyStyleCombination(ProfileKind.GENERAL).forEach { p ->
                assertFalse(
                    "GENERAL must produce the prompt this build produced before the kind existed, " +
                        "and app=${app?.packageName} style=${p.outputStyle} gained a Tone: section",
                    build(p, app).contains("Tone:")
                )
            }
        }
    }

    /**
     * The other half of "unchanged": the guess must still reach GENERAL. A gate that accidentally
     * excluded GENERAL too would also pass the assertion above, while silently dropping the app
     * hint every existing profile relies on.
     */
    @Test
    fun `a GENERAL profile still receives the app style hint`() {
        assertTrue(build(profile(ProfileKind.GENERAL), mail).contains("App style hint: $emailTone"))
        assertTrue(build(profile(ProfileKind.GENERAL), chat).contains("App style hint: $chatTone"))
    }

    /** The two paths must say the same thing, or "declare it instead of guessing" changes the text. */
    @Test
    fun `the declared tone and the guessed tone are the same sentence`() {
        val declared = build(profile(ProfileKind.EMAIL), null)
        val guessed = build(profile(ProfileKind.GENERAL), mail)
        assertTrue(declared.contains(emailTone))
        assertTrue(guessed.contains(emailTone))
    }

    // ---- Declared kinds ------------------------------------------------------------

    @Test
    fun `each declaring kind emits its own tone hint with no app at all`() {
        listOf(ProfileKind.EMAIL, ProfileKind.CHAT, ProfileKind.NOTES, ProfileKind.SOCIAL).forEach { kind ->
            val hint = requireNotNull(ProfileKinds.of(kind).toneHint)
            assertTrue(
                "$kind must state its tone even when no app is known — that is what declaring it is for",
                build(profile(kind), null).contains(hint)
            )
        }
    }

    /**
     * The case the field exists for: dictating an email inside a chat app. Before Mission 2 the
     * package name decided, and decided wrong.
     */
    @Test
    fun `a declared kind overrides the app guess instead of joining it`() {
        val prompt = build(profile(ProfileKind.EMAIL), chat)
        assertTrue("the declared tone must be there", prompt.contains(emailTone))
        assertFalse("the app's contradicting guess must not also be there", prompt.contains(chatTone))
        assertFalse("no app style hint line at all for a declared kind", prompt.contains("App style hint:"))
    }

    @Test
    fun `a declared kind keeps the rest of the app context, which is not a tone`() {
        val prompt = build(profile(ProfileKind.EMAIL), chat)
        assertTrue("the target app is still useful context", prompt.contains("Target app: Slack"))
        assertTrue(prompt.contains("Target package: com.Slack"))
    }

    @Test
    fun `VERBATIM sends no tone from either path`() {
        val prompt = build(profile(ProfileKind.VERBATIM), mail)
        assertNull(SystemPromptBuilder.declaredToneSection(profile(ProfileKind.VERBATIM)))
        assertFalse("VERBATIM must not declare a tone", prompt.contains("Tone:"))
        assertFalse("VERBATIM must not inherit the app's guess either", prompt.contains("App style hint:"))
    }

    @Test
    fun `the tone section precedes the output rules, which still win`() {
        val prompt = build(profile(ProfileKind.EMAIL), null)
        val tone = prompt.indexOf(emailTone)
        val rules = prompt.indexOf(SystemPromptBuilder.PRECEDENCE_HEADER)
        assertTrue("the tone hint must be present", tone >= 0)
        assertTrue("the precedence header must be present", rules >= 0)
        assertTrue(
            "a tone hint emitted after the output rules would outrank the style the user chose",
            tone < rules
        )
    }

    @Test
    fun `a declared tone is emitted once, not once per section`() {
        val prompt = build(profile(ProfileKind.EMAIL), mail)
        assertEquals(1, prompt.windowedCount(emailTone))
    }

    private fun String.windowedCount(needle: String): Int {
        var from = 0
        var found = 0
        while (true) {
            val at = indexOf(needle, from)
            if (at < 0) return found
            found++
            from = at + needle.length
        }
    }
}
