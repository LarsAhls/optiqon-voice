package se.optiqon.voice.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import se.optiqon.voice.data.preferences.PreferencesDataStore
import javax.inject.Inject

/**
 * Which half of the app opens. Deciding this needs a disk read, so the third state is
 * "not known yet" — showing the main shell and then yanking it away for onboarding would be
 * worse than a blank frame.
 */
enum class StartDestination { ONBOARDING, MAIN }

@HiltViewModel
class RootViewModel @Inject constructor(
    preferencesDataStore: PreferencesDataStore
) : ViewModel() {

    val startDestination: StateFlow<StartDestination?> = preferencesDataStore.preferences
        .map { if (it.onboardingComplete) StartDestination.MAIN else StartDestination.ONBOARDING }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
}
