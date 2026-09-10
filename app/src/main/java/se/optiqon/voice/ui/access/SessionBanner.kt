package se.optiqon.voice.ui.access

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import se.optiqon.voice.R
import se.optiqon.voice.domain.access.AccessSession
import se.optiqon.voice.domain.access.AccessSessionState
import se.optiqon.voice.domain.access.DegradedKind
import se.optiqon.voice.ui.theme.Sand50
import se.optiqon.voice.ui.theme.Sand100
import se.optiqon.voice.ui.theme.Slate80
import se.optiqon.voice.ui.theme.Slate110
import se.optiqon.voice.ui.theme.TextSecondary
import javax.inject.Inject

@HiltViewModel
class SessionBannerViewModel @Inject constructor(
    private val accessSession: AccessSession
) : ViewModel() {

    val state: StateFlow<AccessSessionState> = accessSession.state

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun retry() {
        viewModelScope.launch {
            _busy.value = true
            try {
                accessSession.refreshNow()
            } finally {
                _busy.value = false
            }
        }
    }
}

/**
 * The rev. 11 session banner: 11h when verification failed, 11i when the device is offline.
 *
 * A banner and never a screen. `Degraded` means the account is fine and the app still works;
 * turning that into a full-screen state would tell the user they are locked out when they are
 * not. `Blocked` is the opposite case and is handled by the account gate, not here.
 */
@Composable
fun SessionBanner(
    modifier: Modifier = Modifier,
    viewModel: SessionBannerViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()

    val degraded = state as? AccessSessionState.Degraded ?: return

    when (degraded.kind) {
        DegradedKind.VERIFICATION_FAILED -> Banner(
            modifier = modifier,
            background = Sand100,
            foreground = Sand50,
            title = stringResource(R.string.session_verification_failed_title),
            body = stringResource(R.string.session_verification_failed_body),
            action = stringResource(R.string.session_try_again),
            actionEnabled = !busy,
            onAction = viewModel::retry
        )

        DegradedKind.OFFLINE -> Banner(
            modifier = modifier,
            background = Slate110,
            foreground = Slate80,
            title = stringResource(R.string.session_offline_title),
            body = stringResource(R.string.session_offline_body),
            action = null,
            actionEnabled = false,
            onAction = {}
        )
    }
}

@Composable
private fun Banner(
    modifier: Modifier,
    background: Color,
    foreground: Color,
    title: String,
    body: String,
    action: String?,
    actionEnabled: Boolean,
    onAction: () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = foreground)
            Text(body, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }

        if (action != null) {
            TextButton(onClick = onAction, enabled = actionEnabled) {
                Text(action, color = foreground)
            }
        }
    }
}
