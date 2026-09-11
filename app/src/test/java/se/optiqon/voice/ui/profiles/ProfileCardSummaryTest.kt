package se.optiqon.voice.ui.profiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.optiqon.voice.domain.capability.CapabilityEnvironment
import se.optiqon.voice.domain.capability.ProfileCapabilities
import se.optiqon.voice.domain.model.Profile
import se.optiqon.voice.domain.model.ProfileKind
import se.optiqon.voice.domain.model.ProfileKinds
import se.optiqon.voice.domain.model.RewriteMode
import se.optiqon.voice.domain.model.SummarizeMode

/**
 * What the profile card actually says, asserted as the sentences a tester reads.
 *
 * The defect this closes was a wording defect, not a logic one: the card described the profile
 * from `llmEnabled` alone, so it said "Transcribe only, no cleanup" for a profile whose replacement
 * rules run regardless, and "Cleanup on" for a profile with no key to call. Those two sentences are
 * the reason this file exists, and they are asserted literally — a capability-level test would stay
 * green while the card printed something else.
 *
 * Reasons are taken from [ProfileCapabilities], never pasted: a copy here would go stale the moment
 * a reason was reworded, and the assertion would stop matching anything.
 */
class ProfileCardSummaryTest {

    /**
     * `ProfilesUiState` is a data class that carries the environment, so its generated
     * `toString()` is the path a provider key could take into a crash log. The key is kept out of
     * the type rather than out of the log, and this is the assertion that says so.
     */
    @Test
    fun `the ui state cannot print the provider key`() {
        val secret = "sk-live-must-never-be-printed"
        val state = ProfilesUiState(
            environment = CapabilityEnvironment.from(llmBaseUrl = "https://api.example/v1", llmApiKey = secret)
        )
        assertFalse(
            "the profiles UI state leaked the provider key through toString()",
            state.toString().contains(secret)
        )
    }

    private val configured = CapabilityEnvironment.from(llmBaseUrl = "https://api.example/v1", llmApiKey = "sk-test")
    private val noKey = CapabilityEnvironment.from(llmBaseUrl = "https://api.example/v1", llmApiKey = "")

    private fun profile(
        llmEnabled: Boolean = false,
        rules: Set<Long> = emptySet(),
        kind: ProfileKind = ProfileKind.GENERAL,
        rewrite: RewriteMode = RewriteMode.FIX,
        summarize: SummarizeMode = SummarizeMode.NONE,
        emoji: Boolean = false,
        language: String? = null
    ) = Profile(
        name = "Test",
        llmEnabled = llmEnabled,
        selectedRuleIds = rules,
        profileKind = kind,
        rewriteMode = rewrite,
        summarizeMode = summarize,
        emojiAllowed = emoji,
        language = language
    )

    // ---- The summary line ----------------------------------------------------------------

    /** The one case where "no cleanup" is the whole truth: nothing is selected and nothing is on. */
    @Test
    fun `a profile with nothing enabled keeps the settled sentence`() {
        assertEquals(
            "Transcribe only, no cleanup",
            profileSummary(profile(llmEnabled = false), CapabilityEnvironment.EMPTY)
        )
    }

    /** Defect case 1: the rules run before the cleanup gate, and the card used to deny it. */
    @Test
    fun `rules without cleanup are named as the transformation they are`() {
        assertEquals(
            "Replacement rules only · ${ProfileCapabilities.REASON_CLEANUP_OFF}",
            profileSummary(profile(llmEnabled = false, rules = setOf(1L)), CapabilityEnvironment.EMPTY)
        )
    }

    /** Defect case 2: the toggle is on, so the card must say what is still missing, not "off". */
    @Test
    fun `a toggle with no api key names the missing setting`() {
        assertEquals(
            "Transcribe only · ${ProfileCapabilities.REASON_NO_API_KEY}",
            profileSummary(profile(llmEnabled = true), noKey)
        )
    }

    @Test
    fun `rules with a half-configured provider still lead with the rules`() {
        assertEquals(
            "Replacement rules only · ${ProfileCapabilities.REASON_NO_API_KEY}",
            profileSummary(profile(llmEnabled = true, rules = setOf(1L)), noKey)
        )
    }

    @Test
    fun `a working profile describes what it does to the text, not what is missing`() {
        assertEquals(
            "Removes filler, fixes slips · tightened a little",
            profileSummary(
                profile(llmEnabled = true, rewrite = RewriteMode.FIX, summarize = SummarizeMode.LIGHT),
                configured
            )
        )
    }

    @Test
    fun `a working profile never prints a reason`() {
        val summary = profileSummary(profile(llmEnabled = true, rules = setOf(1L)), configured)
        assertFalse(summary, summary.contains(ProfileCapabilities.REASON_CLEANUP_OFF))
        assertFalse(summary, summary.contains(ProfileCapabilities.REASON_NO_API_KEY))
        assertFalse(summary, summary.contains(ProfileCapabilities.REASON_NO_PROVIDER_URL))
    }

    @Test
    fun `the summary is never blank, whatever the profile holds`() {
        val environments = listOf(CapabilityEnvironment.EMPTY, noKey, configured)
        for (environment in environments) {
            for (enabled in listOf(true, false)) {
                for (rewrite in RewriteMode.entries) {
                    for (summarize in SummarizeMode.entries) {
                        val summary = profileSummary(
                            profile(llmEnabled = enabled, rewrite = rewrite, summarize = summarize),
                            environment
                        )
                        assertTrue("an empty card line tells the tester nothing", summary.isNotBlank())
                    }
                }
            }
        }
    }

    // ---- The chips -----------------------------------------------------------------------

    @Test
    fun `cleanup off is only claimed when the tester turned it off`() {
        assertTrue("Cleanup off" in profileChips(profile(llmEnabled = false), CapabilityEnvironment.EMPTY))
    }

    /** Defect case 2, as a chip: the switch is on, so the chip must not contradict it. */
    @Test
    fun `a toggle with no key reads as unfinished setup, not as off`() {
        val chips = profileChips(profile(llmEnabled = true), noKey)
        assertTrue(chips.toString(), "Cleanup needs setup" in chips)
        assertFalse(chips.toString(), "Cleanup off" in chips)
        assertFalse(chips.toString(), "Cleanup on" in chips)
    }

    @Test
    fun `cleanup on requires the toggle and both provider settings`() {
        assertTrue("Cleanup on" in profileChips(profile(llmEnabled = true), configured))
    }

    @Test
    fun `selected rules get their own chip regardless of the provider`() {
        assertTrue("Rules on" in profileChips(profile(rules = setOf(1L)), CapabilityEnvironment.EMPTY))
        assertFalse("Rules on" in profileChips(profile(), configured))
    }

    /** Emoji is an instruction inside the system prompt, so without the pass it reaches nothing. */
    @Test
    fun `emoji is only advertised when there is a prompt to put it in`() {
        assertTrue("Emoji ok" in profileChips(profile(llmEnabled = true, emoji = true), configured))
        assertFalse("Emoji ok" in profileChips(profile(llmEnabled = true, emoji = true), noKey))
        assertFalse("Emoji ok" in profileChips(profile(llmEnabled = false, emoji = true), configured))
    }

    @Test
    fun `the language chip falls back to Auto and is otherwise upper case`() {
        assertEquals("Auto", profileChips(profile(language = null), configured).first())
        assertEquals("SV", profileChips(profile(language = "sv"), configured).first())
    }

    @Test
    fun `a declared kind is shown by its label and GENERAL is not`() {
        val email = ProfileKinds.of(ProfileKind.EMAIL).label
        assertTrue(email in profileChips(profile(kind = ProfileKind.EMAIL), configured))
        assertFalse(
            "GENERAL is the absence of a declared kind, so a chip for it is noise on every card",
            ProfileKinds.of(ProfileKind.GENERAL).label in profileChips(profile(), configured)
        )
    }

    @Test
    fun `the chip order is stable, because the card lays them out in one row`() {
        val chips = profileChips(
            profile(llmEnabled = true, rules = setOf(1L), kind = ProfileKind.EMAIL, emoji = true, language = "sv"),
            configured
        )
        assertEquals(
            listOf("SV", ProfileKinds.of(ProfileKind.EMAIL).label, "Cleanup on", "Rules on", "Emoji ok"),
            chips
        )
    }
}
