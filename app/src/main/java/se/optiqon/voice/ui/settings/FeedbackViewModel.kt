package se.optiqon.voice.ui.settings

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import se.optiqon.voice.R
import se.optiqon.voice.domain.feedback.FeedbackOutcome
import se.optiqon.voice.domain.feedback.FeedbackQueue
import javax.inject.Inject

sealed interface FeedbackUiState {
    data object Idle : FeedbackUiState
    data object Working : FeedbackUiState
    data class Done(val outcome: FeedbackOutcome) : FeedbackUiState
}

/** The sentence shown under the button, or none before anything has been tried. */
@StringRes
internal fun FeedbackUiState.messageRes(): Int? = when (this) {
    FeedbackUiState.Idle, FeedbackUiState.Working -> null
    is FeedbackUiState.Done -> when (outcome) {
        FeedbackOutcome.Queued -> R.string.feedback_saved
        FeedbackOutcome.Empty -> R.string.feedback_empty
        FeedbackOutcome.TooLong -> R.string.feedback_too_long
        FeedbackOutcome.SignedOut -> R.string.feedback_signed_out
    }
}

@HiltViewModel
class FeedbackViewModel @Inject constructor(
    private val queue: FeedbackQueue
) : ViewModel() {

    private val _state = MutableStateFlow<FeedbackUiState>(FeedbackUiState.Idle)
    val state: StateFlow<FeedbackUiState> = _state.asStateFlow()

    fun submit(message: String, contact: String) {
        _state.value = FeedbackUiState.Working
        viewModelScope.launch {
            _state.value = FeedbackUiState.Done(queue.submit(message, contact.ifBlank { null }))
        }
    }
}
