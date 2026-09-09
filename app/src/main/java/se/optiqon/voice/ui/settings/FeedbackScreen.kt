package se.optiqon.voice.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.optiqon.voice.R
import se.optiqon.voice.domain.feedback.FeedbackPayloads
import se.optiqon.voice.ui.common.PrimaryButton

/**
 * Where a person says what is wrong.
 *
 * The screen is honest about what happens next, because what happens next is nothing: the
 * message is saved on this device and no upload is attempted. That is not a placeholder
 * apology — it is the state the channel is deliberately in, and the copy says so rather than
 * showing a "Sent" that would be a lie.
 */
@Composable
internal fun FeedbackScreen(
    onBack: () -> Unit,
    outerPadding: PaddingValues,
    viewModel: FeedbackViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf("") }

    DrillInScaffold(
        title = stringResource(R.string.feedback_title),
        onBack = onBack,
        onAdd = null,
        outerPadding = outerPadding
    ) { padding ->
        LazyColumn(contentPadding = padding, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            // No eyebrow here: the scaffold's title already says "Report a problem", and
            // repeating it pushes the only sentence that matters further down the screen.
            item("intro") {
                Text(
                    text = stringResource(R.string.feedback_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item("message") {
                OutlinedTextField(
                    value = message,
                    onValueChange = { if (it.length <= FeedbackPayloads.MAX_MESSAGE_CHARS) message = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                    label = { Text(stringResource(R.string.feedback_message_label)) },
                    supportingText = { Text(stringResource(R.string.feedback_message_hint)) }
                )
            }

            item("contact") {
                OutlinedTextField(
                    value = contact,
                    onValueChange = { contact = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.feedback_contact_label)) },
                    supportingText = { Text(stringResource(R.string.feedback_contact_hint)) }
                )
            }

            item("submit") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // The outcome sits above the button, not below it. Below, it landed under
                    // the navigation bar on a 1080x2376 screen, so the one line confirming that
                    // nothing was uploaded was the one line nobody saw.
                    state.messageRes()?.let { res ->
                        Text(
                            text = stringResource(res),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    PrimaryButton(
                        text = stringResource(R.string.feedback_save),
                        onClick = {
                            viewModel.submit(message, contact)
                            message = ""
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = message.isNotBlank() && state !is FeedbackUiState.Working
                    )
                }
            }
        }
    }
}
