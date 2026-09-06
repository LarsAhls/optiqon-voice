package se.optiqon.voice.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Intent
import android.net.Uri
import se.optiqon.voice.domain.model.TranscriptionLanguages
import se.optiqon.voice.domain.provider.ProviderPresets
import se.optiqon.voice.ui.common.GhostButton
import se.optiqon.voice.ui.common.InlineStatus
import se.optiqon.voice.ui.common.OptionRow
import se.optiqon.voice.ui.common.OptiqonMark
import se.optiqon.voice.ui.common.PermissionChecklist
import se.optiqon.voice.ui.common.PrimaryButton
import se.optiqon.voice.ui.common.SecondaryButton
import se.optiqon.voice.ui.common.SectionEyebrow
import se.optiqon.voice.ui.common.Wordmark
import se.optiqon.voice.ui.common.rememberAccessibilityPermissionState
import se.optiqon.voice.ui.common.rememberMicrophonePermissionState
import se.optiqon.voice.ui.common.rememberNotificationPermissionState
import se.optiqon.voice.ui.common.rememberOverlayPermissionState
import se.optiqon.voice.ui.theme.AppIcons

/**
 * The first-run flow: what you dictate in, what transcribes it, and what Android has to let
 * the app do. Three steps because each one fails for a different reason, and a single long
 * form makes it impossible to tell which of them went wrong.
 */
@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.finished) {
        if (state.finished) onFinished()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Spacer(Modifier.height(24.dp))
        StepHeader(state.step)

        when (state.step) {
            OnboardingStep.LANGUAGE -> LanguageStep(state, viewModel)
            OnboardingStep.CONNECT -> ConnectStep(state, viewModel)
            OnboardingStep.PERMISSIONS -> PermissionsStep()
        }

        StepActions(state, viewModel)
        Spacer(Modifier.height(32.dp))
    }
}

/** The app's name on the left, how far you have got on the right. Same on all three steps. */
@Composable
private fun StepHeader(current: OnboardingStep) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Wordmark()
        Text(
            text = "${current.ordinal + 1} of ${OnboardingStep.entries.size}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StepHeading(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(text = title, style = MaterialTheme.typography.headlineLarge)
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LanguageStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        // The mark, once and large: the first screen is the only place the app introduces
        // itself, and everything below it is a question.
        OptiqonMark(modifier = Modifier.align(Alignment.CenterHorizontally), size = 120.dp)
        StepHeading(
            title = "Speak.\nIt types.",
            body = "A small bubble floats over your apps. Tap it, talk, and clean text lands " +
                "where your cursor is."
        )
        SectionEyebrow("Which language do you speak most?")
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            LANGUAGE_CHOICES.forEach { choice ->
                OptionRow(
                    title = choice.label,
                    subtitle = choice.subtitle,
                    badge = choice.badge,
                    selected = choice.code == state.language,
                    onClick = { viewModel.selectLanguage(choice.code) }
                )
            }
        }
    }
}

@Composable
private fun ConnectStep(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        StepHeading(
            title = "Connect a\nspeech service",
            body = "Your voice is sent to a service that turns it into text. Groq is free to " +
                "start, fast, and works well with Swedish."
        )

        SectionEyebrow("Provider")
        OptionRow(
            title = ProviderPresets.GROQ.displayName,
            subtitle = ProviderPresets.GROQ.summary,
            badge = "Recommended",
            selected = state.preset.id == ProviderPresets.GROQ.id,
            onClick = { viewModel.selectPreset(ProviderPresets.GROQ) }
        )

        if (state.preset.consoleUrl.isNotBlank()) {
            GhostButton(
                text = "Get a free key",
                icon = AppIcons.OpenInNew,
                accent = true,
                onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(state.preset.consoleUrl))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            )
        }

        OutlinedTextField(
            value = state.apiKey,
            onValueChange = viewModel::updateApiKey,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("API key") },
            placeholder = { Text(state.preset.keyPrefixHint.ifBlank { "Paste your key" }) },
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
        )

        TextButton(onClick = { viewModel.setShowAdvanced(!state.showAdvanced) }) {
            Text(if (state.showAdvanced) "Hide advanced" else "Advanced")
        }

        if (state.showAdvanced) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OptionRow(
                    title = ProviderPresets.CUSTOM.displayName,
                    subtitle = ProviderPresets.CUSTOM.summary,
                    selected = state.isCustomPreset,
                    onClick = { viewModel.selectPreset(ProviderPresets.CUSTOM) }
                )
                if (state.isCustomPreset) {
                    OutlinedTextField(
                        value = state.baseUrl,
                        onValueChange = viewModel::updateBaseUrl,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Base URL") },
                        placeholder = { Text("https://example.com/") },
                        supportingText = { Text("HTTPS only. The app appends v1/audio/transcriptions.") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.small,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                    )
                }
                OutlinedTextField(
                    value = state.asrModel,
                    onValueChange = viewModel::updateAsrModel,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Transcription model") },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small
                )
            }
        }

        when (val connection = state.connection) {
            is ConnectionState.Untested -> Unit
            is ConnectionState.Testing -> Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                Text("Sending a test clip…", style = MaterialTheme.typography.bodyMedium)
            }
            is ConnectionState.Verified -> InlineStatus("Connected. Transcription is working.")
            is ConnectionState.Failed -> Text(
                text = connection.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun PermissionsStep() {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        StepHeading(
            // Four rows, not the handoff's three: notifications are their own Android
            // permission from 13 on, and the headline must not promise a shorter list.
            title = "A few things\nAndroid asks for",
            body = "Each one opens a system screen. Flip the switch there and come back — " +
                "this list updates by itself."
        )
        SectionEyebrow("Required")
        PermissionChecklist(
            listOf(
                rememberMicrophonePermissionState(),
                rememberOverlayPermissionState(),
                rememberAccessibilityPermissionState(),
                rememberNotificationPermissionState()
            )
        )
        Text(
            text = "Voice does not type into password fields or other protected fields. " +
                "Your voice goes only to the speech service you chose. OPTIQON hears from " +
                "you only if you send feedback.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StepActions(state: OnboardingUiState, viewModel: OnboardingViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (state.step) {
            OnboardingStep.LANGUAGE -> PrimaryButton("Continue", onClick = viewModel::next)

            OnboardingStep.CONNECT -> {
                if (state.canLeaveConnectStep) {
                    PrimaryButton("Continue", onClick = viewModel::next)
                } else {
                    PrimaryButton(
                        text = if (state.connection is ConnectionState.Failed) "Try again" else "Verify and save",
                        enabled = state.canVerify,
                        onClick = viewModel::verifyAndSave
                    )
                }
                SecondaryButton("Back", onClick = viewModel::back)
            }

            OnboardingStep.PERMISSIONS -> {
                PrimaryButton("Start using Optiqon Voice", onClick = viewModel::finish)
                // Leaving early is allowed: Home lists whatever is still missing, so a
                // permission screen is never a dead end.
                SecondaryButton("Finish later", onClick = viewModel::finish)
                GhostButton("Back", onClick = viewModel::back)
            }
        }
    }
}

private data class LanguageChoice(
    val code: String?,
    val label: String,
    val subtitle: String? = null,
    val badge: String? = null
)

/**
 * One answer, not a set: naming the language you actually speak is what makes the
 * transcription good, and `null` is the honest "let it detect" option rather than a blank.
 */
private val LANGUAGE_CHOICES: List<LanguageChoice> = listOf(
    LanguageChoice(TranscriptionLanguages.DEFAULT_CODE, "Svenska", badge = "Rekommenderat"),
    LanguageChoice("en", "English"),
    LanguageChoice(null, "Let the app detect it", subtitle = "Slightly less accurate")
)
