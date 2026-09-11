package se.optiqon.voice.domain.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.optiqon.voice.domain.model.Profile
import se.optiqon.voice.domain.model.ProfileKind

/**
 * Every branch of the single evaluation the card, the chips and [se.optiqon.voice.domain.processing.TextProcessor]
 * all read from.
 *
 * Two of these cases are the defect this class was written to close, and they are marked as such:
 * a profile with replacement rules and no LLM *does* change the text, and a profile whose toggle is
 * on but whose provider is unconfigured does *not*. Both were answered wrongly by the old `llmEnabled`
 * boolean, in opposite directions.
 */
class ProfileCapabilitiesTest {

    private val configured = CapabilityEnvironment.from(llmBaseUrl = "https://api.example/v1", llmApiKey = "sk-test")

    private fun profile(
        llmEnabled: Boolean = false,
        rules: Set<Long> = emptySet(),
        kind: ProfileKind = ProfileKind.GENERAL
    ) = Profile(name = "Test", llmEnabled = llmEnabled, selectedRuleIds = rules, profileKind = kind)

    private fun reasonOf(state: CapabilityState): String =
        (state as? CapabilityState.Unavailable)?.reason ?: error("expected Unavailable, was $state")

    private fun state(capability: ProfileCapability, profile: Profile, environment: CapabilityEnvironment) =
        ProfileCapabilities.state(capability, profile, environment)

    // ---- Post-processing: the three reasons, in the order the user is told them ----------

    @Test
    fun `the profile's own toggle is blamed before the missing provider`() {
        // Both are wrong at once. The toggle is the thing the user set, so it is reported first.
        val state = state(ProfileCapability.POST_PROCESSING, profile(llmEnabled = false), CapabilityEnvironment.EMPTY)
        assertEquals(ProfileCapabilities.REASON_CLEANUP_OFF, reasonOf(state))
    }

    @Test
    fun `a missing provider address is named as the reason`() {
        val environment = CapabilityEnvironment.from(llmBaseUrl = "", llmApiKey = "sk-test")
        val state = state(ProfileCapability.POST_PROCESSING, profile(llmEnabled = true), environment)
        assertEquals(ProfileCapabilities.REASON_NO_PROVIDER_URL, reasonOf(state))
    }

    /** Defect case 1: the old card said "Cleanup on" here, for a profile that cannot call anything. */
    @Test
    fun `a toggle with no api key is unavailable and says which setting is missing`() {
        val environment = CapabilityEnvironment.from(llmBaseUrl = "https://api.example/v1", llmApiKey = "")
        val state = state(ProfileCapability.POST_PROCESSING, profile(llmEnabled = true), environment)
        assertEquals(ProfileCapabilities.REASON_NO_API_KEY, reasonOf(state))
    }

    @Test
    fun `post-processing is available when the toggle and both settings are present`() {
        assertTrue(state(ProfileCapability.POST_PROCESSING, profile(llmEnabled = true), configured).isAvailable)
    }

    @Test
    fun `blank is not the same as absent, but is treated the same`() {
        // DataStore hands back whatever was typed; a field of spaces is an unconfigured provider.
        val environment = CapabilityEnvironment.from(llmBaseUrl = "   ", llmApiKey = "  ")
        val state = state(ProfileCapability.POST_PROCESSING, profile(llmEnabled = true), environment)
        assertEquals(ProfileCapabilities.REASON_NO_PROVIDER_URL, reasonOf(state))
    }

    // ---- Replacement rules: local, and independent of the provider -----------------------

    @Test
    fun `no selected rules is its own reason`() {
        val state = state(ProfileCapability.REPLACEMENT_RULES, profile(), CapabilityEnvironment.EMPTY)
        assertEquals(ProfileCapabilities.REASON_NO_RULES_SELECTED, reasonOf(state))
    }

    /** Defect case 2: rules run before the LLM gate, so this profile does transform the text. */
    @Test
    fun `selected rules are available with no provider and no cleanup at all`() {
        val profile = profile(llmEnabled = false, rules = setOf(1L))
        assertTrue(state(ProfileCapability.REPLACEMENT_RULES, profile, CapabilityEnvironment.EMPTY).isAvailable)
        assertTrue(
            "a profile with rules changes the dictation even with cleanup off — the card said otherwise",
            ProfileCapabilities.transformsText(profile, CapabilityEnvironment.EMPTY)
        )
    }

    @Test
    fun `a profile with neither rules nor a usable provider changes nothing`() {
        assertFalse(ProfileCapabilities.transformsText(profile(llmEnabled = true), CapabilityEnvironment.EMPTY))
        assertFalse(ProfileCapabilities.transformsText(profile(llmEnabled = false), configured))
    }

    // ---- Style controls: the same answer as post-processing, never a second one ----------

    @Test
    fun `style controls resolve identically to post-processing in every case`() {
        val environments = listOf(
            CapabilityEnvironment.EMPTY,
            CapabilityEnvironment.from(llmBaseUrl = "https://api.example/v1", llmApiKey = ""),
            configured
        )
        for (environment in environments) {
            for (enabled in listOf(true, false)) {
                val states = ProfileCapabilities.evaluate(profile(llmEnabled = enabled), environment)
                assertEquals(
                    "style settings are instructions inside the prompt; with no prompt they reach nothing",
                    states.getValue(ProfileCapability.POST_PROCESSING),
                    states.getValue(ProfileCapability.STYLE_CONTROLS)
                )
            }
        }
    }

    // ---- Tone hint -----------------------------------------------------------------------

    @Test
    fun `a declaring kind emits a tone hint once the provider is usable`() {
        val profile = profile(llmEnabled = true, kind = ProfileKind.EMAIL)
        assertTrue(state(ProfileCapability.TONE_HINT, profile, configured).isAvailable)
    }

    @Test
    fun `GENERAL reports that the tone comes from the app instead`() {
        val profile = profile(llmEnabled = true, kind = ProfileKind.GENERAL)
        assertEquals(
            ProfileCapabilities.REASON_TONE_FROM_APP,
            reasonOf(state(ProfileCapability.TONE_HINT, profile, configured))
        )
    }

    @Test
    fun `VERBATIM reports that it sends no tone at all`() {
        val profile = profile(llmEnabled = true, kind = ProfileKind.VERBATIM)
        assertEquals(
            ProfileCapabilities.REASON_VERBATIM,
            reasonOf(state(ProfileCapability.TONE_HINT, profile, configured))
        )
    }

    /**
     * With no prompt there is nowhere to put a tone. Reporting "verbatim" or "guessed from the app"
     * there would send the user to the profile editor when the thing to fix is in Settings.
     */
    @Test
    fun `an unusable provider outranks every tone reason`() {
        for (kind in ProfileKind.entries) {
            val state = state(ProfileCapability.TONE_HINT, profile(llmEnabled = true, kind = kind), CapabilityEnvironment.EMPTY)
            assertEquals(
                "$kind must point at the missing provider, not at its own kind",
                ProfileCapabilities.REASON_NO_PROVIDER_URL,
                reasonOf(state)
            )
        }
    }

    // ---- The map itself ------------------------------------------------------------------

    @Test
    fun `evaluate answers every capability, so getValue never throws in the UI`() {
        val states = ProfileCapabilities.evaluate(profile(), CapabilityEnvironment.EMPTY)
        assertEquals(ProfileCapability.entries.toSet(), states.keys)
    }

    @Test
    fun `every reason is a sentence the card can print unchanged`() {
        val reasons = listOf(
            ProfileCapabilities.REASON_CLEANUP_OFF,
            ProfileCapabilities.REASON_NO_PROVIDER_URL,
            ProfileCapabilities.REASON_NO_API_KEY,
            ProfileCapabilities.REASON_NO_RULES_SELECTED,
            ProfileCapabilities.REASON_VERBATIM,
            ProfileCapabilities.REASON_TONE_FROM_APP
        )
        reasons.forEach {
            assertTrue("a blank reason prints as a bare separator", it.isNotBlank())
            assertFalse("the card appends the reason after a separator, so it must not end in one", it.endsWith("."))
        }
        assertEquals("two capabilities sharing a reason string cannot be told apart", reasons.size, reasons.toSet().size)
    }

    @Test
    fun `hasProvider is the conjunction the post-processing rule relies on`() {
        assertTrue(configured.hasProvider)
        assertFalse(CapabilityEnvironment.EMPTY.hasProvider)
        assertFalse(CapabilityEnvironment.from(llmBaseUrl = "https://api.example/v1", llmApiKey = " ").hasProvider)
        assertFalse(CapabilityEnvironment.from(llmBaseUrl = " ", llmApiKey = "sk-test").hasProvider)
    }

    /**
     * The environment is carried by a data class that the UI state also is, so anything that
     * prints state — a crash log, a Compose dump, a failing assertion — prints this. The key is
     * therefore not in the type: [CapabilityEnvironment.from] reduces it to a boolean at the one
     * place it is still known, and this pins that there is no second place.
     */
    @Test
    fun `a rendered environment cannot contain the api key`() {
        val secret = "sk-live-must-never-be-printed"
        val environment = CapabilityEnvironment.from(llmBaseUrl = "https://api.example/v1", llmApiKey = secret)

        assertTrue("the fact about the key is what the rules need", environment.hasProvider)
        assertFalse(
            "toString() of the environment leaked the provider key",
            environment.toString().contains(secret)
        )
    }
}
