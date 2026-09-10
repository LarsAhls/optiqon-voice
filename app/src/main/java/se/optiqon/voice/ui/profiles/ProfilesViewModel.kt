package se.optiqon.voice.ui.profiles

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import se.optiqon.voice.data.preferences.PreferencesDataStore
import se.optiqon.voice.data.repository.ProcessingRepository
import se.optiqon.voice.data.repository.ProfileRepository
import se.optiqon.voice.domain.capability.CapabilityEnvironment
import se.optiqon.voice.domain.model.PostProcessingPrompt
import se.optiqon.voice.domain.model.Profile
import se.optiqon.voice.domain.model.TextReplacementRule
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@Immutable
data class ProfilesUiState(
    val profiles: List<Profile> = emptyList(),
    val rules: List<TextReplacementRule> = emptyList(),
    val prompts: List<PostProcessingPrompt> = emptyList(),
    /**
     * The provider settings, which are global rather than per profile but decide what a profile can
     * actually do. The card used to answer "does this clean up my text?" from the profile alone and
     * got it wrong in both directions; the answer needs both halves.
     */
    val environment: CapabilityEnvironment = CapabilityEnvironment.EMPTY
)

@HiltViewModel
class ProfilesViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
    processingRepository: ProcessingRepository,
    preferences: PreferencesDataStore
) : ViewModel() {
    val uiState: StateFlow<ProfilesUiState> = combine(
        profileRepository.profiles,
        processingRepository.rules,
        processingRepository.prompts,
        preferences.preferences
    ) { profiles, rules, prompts, prefs ->
        ProfilesUiState(
            profiles = profiles,
            rules = rules,
            prompts = prompts,
            environment = CapabilityEnvironment(
                llmBaseUrl = prefs.llmBaseUrl,
                llmApiKey = prefs.llmApiKey
            )
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ProfilesUiState())

    fun save(profile: Profile) {
        viewModelScope.launch {
            val id = profileRepository.save(profile)
            if (profile.isActive) profileRepository.activate(id)
        }
    }

    fun activate(id: Long) {
        viewModelScope.launch { profileRepository.activate(id) }
    }

    fun duplicate(profile: Profile) {
        viewModelScope.launch { profileRepository.duplicate(profile) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { profileRepository.delete(id) }
    }
}

