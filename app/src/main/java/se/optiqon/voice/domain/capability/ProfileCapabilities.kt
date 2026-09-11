package se.optiqon.voice.domain.capability

import se.optiqon.voice.domain.model.Profile
import se.optiqon.voice.domain.model.ProfileKind
import se.optiqon.voice.domain.model.ProfileKinds

/**
 * The four things a profile can or cannot do to your text, and why not when it cannot.
 *
 * This exists because the same question — "does this profile change my text?" — used to be
 * answered separately in the profile card, the chips under it, the prompt builder and the
 * processing path, and the answers disagreed. The card claimed "Transcribe only, no cleanup" for a
 * profile whose replacement rules run regardless of the LLM toggle, and claimed "Cleanup on" for a
 * profile whose toggle is on but which has no provider key to call. One evaluated answer, printed
 * wherever the question is asked.
 */
enum class ProfileCapability {
    /** The LLM post-processing pass runs at all. */
    POST_PROCESSING,

    /** The profile's replacement rules are applied. Local; needs no provider. */
    REPLACEMENT_RULES,

    /** Style, rewrite and summarize settings reach the model. */
    STYLE_CONTROLS,

    /** A tone line is emitted into the system prompt. */
    TONE_HINT
}

/**
 * What a capability resolves to. An unavailable capability always carries the reason, because the
 * reason is what the UI prints — a bare boolean is what let the card lie in the first place.
 */
sealed interface CapabilityState {
    data object Available : CapabilityState
    data class Unavailable(val reason: String) : CapabilityState

    val isAvailable: Boolean get() = this is Available
}

/**
 * The configured provider, reduced to the two facts that decide whether a call can be made.
 *
 * Deliberately a value object rather than the preferences themselves: it keeps [evaluate] a pure
 * function with no DataStore, no `Context` and no `suspend`, which is what lets every capability
 * rule be tested without a single new test dependency.
 *
 * [hasApiKey] is the fact about the key rather than the key, deliberately. Nothing here ever
 * needed more than `isNotBlank()`, while this type is carried by
 * [se.optiqon.voice.ui.profiles.ProfilesUiState], a data class whose generated `toString()`
 * reaches crash logs and Compose state dumps. A key that is never in the type cannot be printed
 * by one.
 */
data class CapabilityEnvironment(
    val llmBaseUrl: String,
    val hasApiKey: Boolean
) {
    val hasProvider: Boolean get() = llmBaseUrl.isNotBlank() && hasApiKey

    companion object {
        /** No provider configured. */
        val EMPTY = CapabilityEnvironment(llmBaseUrl = "", hasApiKey = false)

        /**
         * The one place a key value is reduced to the fact about it. Callers upstream of this
         * hold the key; nothing downstream of it does.
         */
        fun from(llmBaseUrl: String, llmApiKey: String) = CapabilityEnvironment(
            llmBaseUrl = llmBaseUrl,
            hasApiKey = llmApiKey.isNotBlank()
        )
    }
}

object ProfileCapabilities {

    const val REASON_CLEANUP_OFF = "Cleanup is off for this profile"
    const val REASON_NO_PROVIDER_URL = "No provider address is set in Settings"
    const val REASON_NO_API_KEY = "No API key is set in Settings"
    const val REASON_NO_RULES_SELECTED = "No replacement rules are selected"
    const val REASON_VERBATIM = "Verbatim profiles send no tone hint"
    const val REASON_TONE_FROM_APP = "Tone is guessed from the app you dictate into"

    /**
     * Pure. Same inputs, same answer, no IO.
     *
     * The order of the post-processing checks matters for what the user is told: the profile's own
     * toggle is reported before the missing provider, because turning the toggle on is the thing
     * the user did and the thing they expect to be blamed for.
     */
    fun evaluate(
        profile: Profile,
        environment: CapabilityEnvironment
    ): Map<ProfileCapability, CapabilityState> {
        val postProcessing = evaluatePostProcessing(profile, environment)
        return mapOf(
            ProfileCapability.POST_PROCESSING to postProcessing,
            ProfileCapability.REPLACEMENT_RULES to evaluateReplacementRules(profile),
            // Style settings are instructions inside the system prompt. With no LLM pass there is
            // no prompt, so they reach nothing — the same reason, not a separate one.
            ProfileCapability.STYLE_CONTROLS to postProcessing,
            ProfileCapability.TONE_HINT to evaluateToneHint(profile, postProcessing)
        )
    }

    fun state(
        capability: ProfileCapability,
        profile: Profile,
        environment: CapabilityEnvironment
    ): CapabilityState = evaluate(profile, environment).getValue(capability)

    /**
     * True when the profile changes the text at all — by either path.
     *
     * This is the question the profile card actually asks, and answering it from `llmEnabled` alone
     * is what made the card wrong: replacement rules are applied before the LLM toggle is even
     * consulted, so a profile with rules and no provider does transform what you dictated.
     */
    fun transformsText(profile: Profile, environment: CapabilityEnvironment): Boolean {
        val states = evaluate(profile, environment)
        return states.getValue(ProfileCapability.POST_PROCESSING).isAvailable ||
            states.getValue(ProfileCapability.REPLACEMENT_RULES).isAvailable
    }

    private fun evaluatePostProcessing(
        profile: Profile,
        environment: CapabilityEnvironment
    ): CapabilityState = when {
        !profile.llmEnabled -> CapabilityState.Unavailable(REASON_CLEANUP_OFF)
        environment.llmBaseUrl.isBlank() -> CapabilityState.Unavailable(REASON_NO_PROVIDER_URL)
        !environment.hasApiKey -> CapabilityState.Unavailable(REASON_NO_API_KEY)
        else -> CapabilityState.Available
    }

    private fun evaluateReplacementRules(profile: Profile): CapabilityState =
        if (profile.selectedRuleIds.isEmpty()) {
            CapabilityState.Unavailable(REASON_NO_RULES_SELECTED)
        } else {
            CapabilityState.Available
        }

    /**
     * A tone hint needs somewhere to go and something to say. [ProfileKind.GENERAL] has something
     * to say only once an app is known at dictation time, so from a profile alone it is reported as
     * deferred to the app rather than as available.
     */
    private fun evaluateToneHint(
        profile: Profile,
        postProcessing: CapabilityState
    ): CapabilityState = when {
        postProcessing is CapabilityState.Unavailable -> postProcessing
        profile.profileKind == ProfileKind.VERBATIM -> CapabilityState.Unavailable(REASON_VERBATIM)
        ProfileKinds.of(profile.profileKind).toneHint == null ->
            CapabilityState.Unavailable(REASON_TONE_FROM_APP)
        else -> CapabilityState.Available
    }
}
