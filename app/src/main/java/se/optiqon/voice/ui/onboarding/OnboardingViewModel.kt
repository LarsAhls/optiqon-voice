package se.optiqon.voice.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import se.optiqon.voice.data.preferences.PreferencesDataStore
import se.optiqon.voice.data.repository.ProfileRepository
import se.optiqon.voice.domain.model.TranscriptionLanguages
import se.optiqon.voice.domain.provider.ProviderPreset
import se.optiqon.voice.domain.provider.ProviderPresets
import se.optiqon.voice.domain.provider.ProviderVerifier
import se.optiqon.voice.domain.provider.VerificationResult
import javax.inject.Inject

/**
 * Rev. 11 order: language, then the account, then the speech service, then permissions.
 *
 * The account comes before `CONNECT` for a structural reason, not a cosmetic one: `CONNECT`
 * makes a real call to a speech provider, and doing that before anyone has been admitted to
 * the app means an unapproved install can drive a paid endpoint.
 */
/**
 * The order the first run asks its questions in.
 *
 * The account comes first because it is the only question whose answer can refuse the rest:
 * an unapproved account cannot dictate, so choosing a language and a speech service before
 * knowing that would be setting up an app the person may not be allowed to use. Everything
 * after it is configuration of something already granted.
 *
 * The declaration order is the step order — `ordinal` drives the "N of 4" counter.
 */
enum class OnboardingStep { ACCOUNT, LANGUAGE, CONNECT, PERMISSIONS }

/** What the Connect step is currently able to say about the endpoint it was given. */
sealed interface ConnectionState {
    data object Untested : ConnectionState
    data object Testing : ConnectionState
    data object Verified : ConnectionState

    /**
     * Transcription works, the text model does not. Onboarding continues — dictation is still
     * usable and the raw transcript is still injected — but it is said out loud rather than
     * degraded silently, and cleanup is left switched off instead of pointing at a model the
     * provider refuses (smoke finding F17).
     */
    data class VerifiedWithoutCleanup(val message: String) : ConnectionState

    /**
     * A key this root verified on an earlier run, offered back rather than asked for again.
     *
     * Distinct from [Verified] because the claim is different: [Verified] means a provider
     * answered a moment ago, this means one answered before and the key was kept. Saying
     * "Connected. Transcription is working." on the strength of a past call would be a claim
     * about now that nothing here has checked.
     */
    data object Restored : ConnectionState

    data class Failed(val message: String) : ConnectionState
}

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.ACCOUNT,
    val language: String? = DEFAULT_LANGUAGE,
    val preset: ProviderPreset = ProviderPresets.RECOMMENDED,
    val showAdvanced: Boolean = false,
    val baseUrl: String = "",
    val apiKey: String = "",
    val asrModel: String = ProviderPresets.RECOMMENDED.asrModel,
    val connection: ConnectionState = ConnectionState.Untested,
    val finished: Boolean = false
) {
    val isCustomPreset: Boolean get() = preset.isCustom

    /** The URL actually sent: a preset supplies its own, a custom provider is typed in. */
    val effectiveBaseUrl: String get() = if (isCustomPreset) baseUrl else preset.baseUrl

    val canVerify: Boolean
        get() = apiKey.isNotBlank() &&
            effectiveBaseUrl.isNotBlank() &&
            asrModel.isNotBlank() &&
            connection != ConnectionState.Testing

    val canLeaveConnectStep: Boolean
        get() = connection == ConnectionState.Verified ||
            connection == ConnectionState.Restored ||
            connection is ConnectionState.VerifiedWithoutCleanup

    companion object {
        const val DEFAULT_LANGUAGE = TranscriptionLanguages.DEFAULT_CODE
    }
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val preferencesDataStore: PreferencesDataStore,
    private val providerVerifier: ProviderVerifier,
    private val profileRepository: ProfileRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    init {
        restoreVerifiedEndpoint()
    }

    /**
     * Offers back the endpoint this root has already verified (smoke finding F19).
     *
     * Onboarding runs again after a sign-out — the completion flag is cleared, the stored key
     * is not — and until now the Connect step opened empty, which left the person unable to
     * finish a step they had already completed unless they could produce the key a second
     * time. Nothing had been lost; it simply was not offered back.
     *
     * A key only reaches storage through [verifyAndSave], which writes nothing unless the
     * provider accepted it, so a stored key is by construction one that has worked. That is
     * enough to leave the step, and it costs no further call to a paid endpoint.
     */
    private fun restoreVerifiedEndpoint() {
        viewModelScope.launch {
            val saved = preferencesDataStore.preferences.first()
            if (saved.asrApiKey.isBlank() || saved.asrBaseUrl.isBlank()) return@launch

            _uiState.update { state ->
                // Anything answered since this screen opened is the newer answer, and a
                // restore arriving late must not overwrite it.
                if (state.apiKey.isNotBlank() || state.connection != ConnectionState.Untested) {
                    return@update state
                }
                val preset = ProviderPresets.byId(saved.providerPresetId)
                // The URL that was verified is the stored one, not whatever the preset names
                // today. If they have drifted apart, the stored one is shown as a custom
                // endpoint so that `effectiveBaseUrl` is the address that actually worked.
                val presetStillMatches = !preset.isCustom &&
                    saved.asrBaseUrl.trim() == preset.baseUrl.trim()
                state.copy(
                    preset = if (presetStillMatches) preset else ProviderPresets.CUSTOM,
                    baseUrl = if (presetStillMatches) "" else saved.asrBaseUrl,
                    apiKey = saved.asrApiKey,
                    asrModel = saved.asrModel,
                    connection = ConnectionState.Restored
                )
            }
        }
    }

    /** `null` is the deliberate "let the provider detect it" choice, not an absent answer. */
    fun selectLanguage(code: String?) = _uiState.update { it.copy(language = code) }

    fun selectPreset(preset: ProviderPreset) {
        _uiState.update { state ->
            state.copy(
                preset = preset,
                asrModel = preset.asrModel,
                baseUrl = if (preset.id == ProviderPresets.CUSTOM.id) state.baseUrl else "",
                connection = ConnectionState.Untested
            )
        }
    }

    fun setShowAdvanced(show: Boolean) = _uiState.update { it.copy(showAdvanced = show) }

    fun updateApiKey(value: String) =
        _uiState.update { it.copy(apiKey = value, connection = ConnectionState.Untested) }

    fun updateBaseUrl(value: String) =
        _uiState.update { it.copy(baseUrl = value, connection = ConnectionState.Untested) }

    fun updateAsrModel(value: String) =
        _uiState.update { it.copy(asrModel = value, connection = ConnectionState.Untested) }

    /**
     * Verifying is also what saves. Storing an endpoint the app has never successfully called
     * is how someone ends up with a bubble that fails at the moment they first need it.
     */
    fun verifyAndSave() {
        val state = _uiState.value
        if (!state.canVerify) return
        _uiState.update { it.copy(connection = ConnectionState.Testing) }
        viewModelScope.launch {
            val result = providerVerifier.verifyTranscription(
                baseUrl = state.effectiveBaseUrl,
                apiKey = state.apiKey,
                model = state.asrModel
            )
            if (result !is VerificationResult.Ok) {
                _uiState.update { it.copy(connection = result.toConnectionState()) }
                return@launch
            }

            preferencesDataStore.updateAsrConfig(
                baseUrl = state.effectiveBaseUrl,
                apiKey = state.apiKey,
                model = state.asrModel
            )

            // A preset also carries a text model, and that model is the one that goes stale
            // without anyone noticing — the ASR call above says nothing about it. Probe it,
            // and only switch cleanup on if the provider actually served it.
            val cleanup = if (state.isCustomPreset) {
                null
            } else {
                providerVerifier.verifyCompletion(
                    baseUrl = state.preset.baseUrl,
                    apiKey = state.apiKey,
                    model = state.preset.llmModel
                )
            }
            val cleanupWorks = cleanup is VerificationResult.Ok

            if (!state.isCustomPreset) {
                // Cleanup on by default when it works: the design's Standard profile removes
                // filler and fixes slips, and it runs on the key and host just verified. When
                // the probe failed the config is still filled in, so turning it on later is
                // one tap rather than another round of endpoint hunting.
                preferencesDataStore.updateLlmConfig(
                    baseUrl = state.preset.baseUrl,
                    apiKey = state.apiKey,
                    model = state.preset.llmModel,
                    enabled = cleanupWorks
                )
            }
            preferencesDataStore.updateProviderPreset(state.preset.id)
            profileRepository.applyProviderToActiveProfile(
                asrModel = state.asrModel,
                llmModel = state.preset.llmModel,
                llmEnabled = cleanupWorks
            )

            val connection = when {
                cleanup == null || cleanupWorks -> ConnectionState.Verified
                else -> ConnectionState.VerifiedWithoutCleanup(cleanup.cleanupMessage())
            }
            _uiState.update { it.copy(connection = connection) }
        }
    }

    fun back() = _uiState.update { state ->
        state.copy(
            step = when (state.step) {
                OnboardingStep.ACCOUNT -> OnboardingStep.ACCOUNT
                OnboardingStep.LANGUAGE -> OnboardingStep.ACCOUNT
                OnboardingStep.CONNECT -> OnboardingStep.LANGUAGE
                OnboardingStep.PERMISSIONS -> OnboardingStep.CONNECT
            }
        )
    }

    fun next() {
        val state = _uiState.value
        when (state.step) {
            // Whether the account may leave this step is the gate's answer, not this
            // view model's: it is asked where the approved state is observed.
            OnboardingStep.ACCOUNT -> _uiState.update { it.copy(step = OnboardingStep.LANGUAGE) }
            OnboardingStep.LANGUAGE -> {
                viewModelScope.launch {
                    preferencesDataStore.updatePreferredLanguages(listOfNotNull(state.language))
                    preferencesDataStore.updateActiveLanguage(state.language)
                    profileRepository.applyLanguageToActiveProfile(state.language)
                }
                _uiState.update { it.copy(step = OnboardingStep.CONNECT) }
            }
            OnboardingStep.CONNECT ->
                if (state.canLeaveConnectStep) _uiState.update { it.copy(step = OnboardingStep.PERMISSIONS) }
            OnboardingStep.PERMISSIONS -> finish()
        }
    }

    fun finish() {
        viewModelScope.launch {
            preferencesDataStore.setOnboardingComplete(true)
            _uiState.update { it.copy(finished = true) }
        }
    }
}

private fun VerificationResult.toConnectionState(): ConnectionState = when (this) {
    is VerificationResult.Ok -> ConnectionState.Verified
    is VerificationResult.Rejected -> ConnectionState.Failed(
        // 401 and 403 are almost always a mistyped or revoked key, and saying so saves the
        // user from reading a raw provider error to work that out.
        if (status == 401 || status == 403) {
            "That key was refused. Check that you copied all of it."
        } else {
            "The provider answered $status: $detail"
        }
    )
    is VerificationResult.Unreachable -> ConnectionState.Failed("Could not reach the provider. $detail")
    is VerificationResult.Invalid -> ConnectionState.Failed(detail)
}

/**
 * The same failures again, but this time they do not block: transcription already works, so
 * the sentence has to say what is lost rather than what went wrong.
 */
private fun VerificationResult.cleanupMessage(): String = when (this) {
    is VerificationResult.Ok -> ""
    is VerificationResult.Rejected ->
        "Transcription works, but the cleanup model answered $status. Dictation will insert " +
            "the raw transcript. You can switch cleanup on in Settings once it is available."
    is VerificationResult.Unreachable ->
        "Transcription works, but the cleanup model could not be reached. Dictation will " +
            "insert the raw transcript."
    is VerificationResult.Invalid ->
        "Transcription works, but the cleanup endpoint is not usable. Dictation will insert " +
            "the raw transcript."
}
