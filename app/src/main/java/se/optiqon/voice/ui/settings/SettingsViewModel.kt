package se.optiqon.voice.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import se.optiqon.voice.data.api.ApiClientFactory
import se.optiqon.voice.data.api.LlmApiService
import se.optiqon.voice.data.api.model.ChatCompletionRequest
import se.optiqon.voice.data.api.model.ChatMessage
import se.optiqon.voice.data.preferences.PreferencesDataStore
import se.optiqon.voice.data.preferences.UserPreferences
import se.optiqon.voice.data.repository.ProcessingRepository
import se.optiqon.voice.domain.model.PostProcessingPrompt
import se.optiqon.voice.domain.provider.ProviderVerifier
import se.optiqon.voice.domain.provider.VerificationResult
import se.optiqon.voice.domain.model.TextReplacementRule
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import retrofit2.HttpException
import javax.inject.Inject

sealed class TestState {
    data object Idle : TestState()
    data object Testing : TestState()
    data class Success(val message: String) : TestState()
    data class Error(val message: String) : TestState()
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val preferencesDataStore: PreferencesDataStore,
    private val apiClientFactory: ApiClientFactory,
    private val processingRepository: ProcessingRepository,
    private val providerVerifier: ProviderVerifier
) : ViewModel() {
    private var asrSavedResetJob: Job? = null
    private var llmSavedResetJob: Job? = null
    private var asrTestJob: Job? = null
    private var llmTestJob: Job? = null
    private var asrTestResetJob: Job? = null
    private var llmTestResetJob: Job? = null

    val preferences: StateFlow<UserPreferences> = preferencesDataStore.preferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserPreferences())

    val rules: StateFlow<List<TextReplacementRule>> = processingRepository.rules
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val prompts: StateFlow<List<PostProcessingPrompt>> = processingRepository.prompts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    override fun onCleared() {
        asrTestJob?.cancel()
        llmTestJob?.cancel()
        asrTestResetJob?.cancel()
        llmTestResetJob?.cancel()
        asrSavedResetJob?.cancel()
        llmSavedResetJob?.cancel()
        super.onCleared()
    }

    private val _asrTestState = MutableStateFlow<TestState>(TestState.Idle)
    val asrTestState: StateFlow<TestState> = _asrTestState.asStateFlow()

    private val _llmTestState = MutableStateFlow<TestState>(TestState.Idle)
    val llmTestState: StateFlow<TestState> = _llmTestState.asStateFlow()

    private val _asrSaved = MutableStateFlow(false)
    val asrSaved: StateFlow<Boolean> = _asrSaved.asStateFlow()

    private val _llmSaved = MutableStateFlow(false)
    val llmSaved: StateFlow<Boolean> = _llmSaved.asStateFlow()

    fun saveAsrConfig(baseUrl: String, apiKey: String, model: String) {
        viewModelScope.launch {
            preferencesDataStore.updateAsrConfig(baseUrl, apiKey, model)
            _asrSaved.value = true
            asrSavedResetJob?.cancel()
            asrSavedResetJob = viewModelScope.launch {
                delay(2000)
                _asrSaved.value = false
            }
        }
    }

    fun saveLlmConfig(baseUrl: String, apiKey: String, model: String, enabled: Boolean) {
        viewModelScope.launch {
            preferencesDataStore.updateLlmConfig(baseUrl, apiKey, model, enabled)
            _llmSaved.value = true
            llmSavedResetJob?.cancel()
            llmSavedResetJob = viewModelScope.launch {
                delay(2000)
                _llmSaved.value = false
            }
        }
    }

    fun saveGeneralSettings(
        autoClipboard: Boolean,
        vibrateOnRecord: Boolean,
        pauseOtherAudio: Boolean,
        silenceThresholdMs: Long,
        historyEnabled: Boolean,
        keepStatsWithoutHistory: Boolean,
        historyRetentionLimit: Int,
        startOnBoot: Boolean
    ) {
        viewModelScope.launch {
            preferencesDataStore.updateGeneralSettings(
                autoClipboard,
                vibrateOnRecord,
                pauseOtherAudio,
                silenceThresholdMs,
                historyEnabled,
                keepStatsWithoutHistory,
                historyRetentionLimit,
                startOnBoot
            )
        }
    }

    fun saveRule(rule: TextReplacementRule) {
        viewModelScope.launch { processingRepository.saveRule(rule) }
    }

    fun deleteRule(id: Long) {
        viewModelScope.launch { processingRepository.deleteRule(id) }
    }

    fun savePrompt(prompt: PostProcessingPrompt) {
        viewModelScope.launch { processingRepository.savePrompt(prompt) }
    }

    fun deletePrompt(id: Long) {
        viewModelScope.launch { processingRepository.deletePrompt(id) }
    }

    fun addPreferredLanguage(code: String) {
        viewModelScope.launch {
            val current = preferences.value.preferredLanguages
            val normalized = code.trim().lowercase()
            if (normalized.isNotBlank() && normalized !in current) {
                preferencesDataStore.updatePreferredLanguages(current + normalized)
            }
        }
    }

    fun removePreferredLanguage(code: String) {
        viewModelScope.launch {
            val current = preferences.value.preferredLanguages
            preferencesDataStore.updatePreferredLanguages(current - code)
        }
    }

    fun testAsrConnection(baseUrl: String, apiKey: String, model: String) {
        asrTestJob?.cancel()
        asrTestJob = viewModelScope.launch {
            asrTestResetJob?.cancel()
            _asrTestState.value = TestState.Testing
            _asrTestState.value = when (val result = providerVerifier.verifyTranscription(baseUrl, apiKey, model)) {
                is VerificationResult.Ok -> TestState.Success("Connected")
                is VerificationResult.Rejected -> TestState.Error("HTTP ${result.status}: ${result.detail}")
                is VerificationResult.Unreachable -> TestState.Error(result.detail)
                is VerificationResult.Invalid -> TestState.Error(result.detail)
            }
            asrTestResetJob = viewModelScope.launch {
                delay(5000)
                _asrTestState.value = TestState.Idle
            }
        }
    }

    fun testLlmConnection(baseUrl: String, apiKey: String, model: String) {
        llmTestJob?.cancel()
        llmTestJob = viewModelScope.launch {
            llmTestResetJob?.cancel()
            _llmTestState.value = TestState.Testing
            try {
                val service = apiClientFactory.create(LlmApiService::class.java, baseUrl, apiKey)
                val response = service.chatCompletion(
                    ChatCompletionRequest(
                        model = model,
                        messages = listOf(ChatMessage("user", "Say 'ok'"))
                    )
                )
                _llmTestState.value = TestState.Success("OK: ${response.text.take(50)}")
            } catch (e: HttpException) {
                val errorBody = try {
                    e.response()?.errorBody()?.string()?.take(300) ?: "No details"
                } catch (_: Exception) { "Could not read error body" }
                _llmTestState.value = TestState.Error("HTTP ${e.code()}: $errorBody")
            } catch (e: Exception) {
                _llmTestState.value = TestState.Error(e.message ?: "Unknown error")
            }
            llmTestResetJob = viewModelScope.launch {
                delay(5000)
                _llmTestState.value = TestState.Idle
            }
        }
    }

    /** Create a minimal valid WAV file (0.1s of silence) for testing the ASR endpoint */
}
