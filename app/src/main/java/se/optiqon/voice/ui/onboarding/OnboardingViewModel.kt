package se.optiqon.voice.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
enum class OnboardingStep { LANGUAGE, ACCOUNT, CONNECT, PERMISSIONS }

/** What the Connect step is currently able to say about the endpoint it was given. */
sealed interface ConnectionState {
    data object Untested : ConnectionState
    data object Testing : ConnectionState
    data object Verified : ConnectionState
    data class Failed(val message: String) : ConnectionState
}

data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.LANGUAGE,
    val language: String? = DEFAULT_LANGUAGE,
    val preset: ProviderPreset = ProviderPresets.RECOMMENDED,
    val showAdvanced: Boolean = false,
    val baseUrl: String = "",
    val apiKey: String = "",
    val asrModel: String = ProviderPresets.RECOMMENDED.asrModel,
    val connection: ConnectionState = ConnectionState.Untested,
    val finished: Boolean = false
) {
    val isCustomPreset: Boolean get() = preset.id == ProviderPresets.CUSTOM.id

    /** The URL actually sent: a preset supplies its own, a custom provider is typed in. */
    val effectiveBaseUrl: String get() = if (isCustomPreset) baseUrl else preset.baseUrl

    val canVerify: Boolean
        get() = apiKey.isNotBlank() &&
            effectiveBaseUrl.isNotBlank() &&
            asrModel.isNotBlank() &&
            connection != ConnectionState.Testing

    val canLeaveConnectStep: Boolean get() = connection == ConnectionState.Verified

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
            if (result is VerificationResult.Ok) {
                preferencesDataStore.updateAsrConfig(
                    baseUrl = state.effectiveBaseUrl,
                    apiKey = state.apiKey,
                    model = state.asrModel
                )
                if (!state.isCustomPreset) {
                    // Filled in but left switched off, so turning cleanup on later is one tap
                    // rather than another round of endpoint hunting.
                    // Cleanup on by default: the design's Standard profile removes filler and
                    // fixes slips, and it runs on the key and host just verified.
                    preferencesDataStore.updateLlmConfig(
                        baseUrl = state.preset.baseUrl,
                        apiKey = state.apiKey,
                        model = state.preset.llmModel,
                        enabled = true
                    )
                }
                preferencesDataStore.updateProviderPreset(state.preset.id)
                profileRepository.applyProviderToActiveProfile(
                    asrModel = state.asrModel,
                    llmModel = state.preset.llmModel,
                    llmEnabled = !state.isCustomPreset
                )
            }
            _uiState.update { it.copy(connection = result.toConnectionState()) }
        }
    }

    fun back() = _uiState.update { state ->
        state.copy(
            step = when (state.step) {
                OnboardingStep.LANGUAGE -> OnboardingStep.LANGUAGE
                OnboardingStep.ACCOUNT -> OnboardingStep.LANGUAGE
                OnboardingStep.CONNECT -> OnboardingStep.ACCOUNT
                OnboardingStep.PERMISSIONS -> OnboardingStep.CONNECT
            }
        )
    }

    fun next() {
        val state = _uiState.value
        when (state.step) {
            OnboardingStep.LANGUAGE -> {
                viewModelScope.launch {
                    preferencesDataStore.updatePreferredLanguages(listOfNotNull(state.language))
                    preferencesDataStore.updateActiveLanguage(state.language)
                    profileRepository.applyLanguageToActiveProfile(state.language)
                }
                _uiState.update { it.copy(step = OnboardingStep.ACCOUNT) }
            }
            // Whether the account may leave this step is the gate's answer, not this
            // view model's: it is asked where the approved state is observed.
            OnboardingStep.ACCOUNT -> _uiState.update { it.copy(step = OnboardingStep.CONNECT) }
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
