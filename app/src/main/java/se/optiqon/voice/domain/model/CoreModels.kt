package se.optiqon.voice.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class Profile(
    val id: Long = 0,
    val name: String,
    val isActive: Boolean = false,
    val asrModel: String = "whisper-1",
    val language: String? = null,
    val llmEnabled: Boolean = false,
    val llmModel: String = "gpt-4o-mini",
    val profilePrompt: String = "",
    val outputStyle: OutputStyle = OutputStyle.STANDARD,
    val rewriteMode: RewriteMode = RewriteMode.FIX,
    val summarizeMode: SummarizeMode = SummarizeMode.NONE,
    val emojiAllowed: Boolean = false,
    val selectedRuleIds: Set<Long> = emptySet(),
    val selectedPromptIds: Set<Long> = emptySet()
)

/**
 * A one-tap language choice for [Profile.language]. [code] is the ISO-639-1 code sent verbatim
 * as the ASR `language` parameter; `null` means Auto (no explicit language sent). The free-text
 * field in the profile editor still accepts any other ISO code directly.
 */
data class TranscriptionLanguageOption(
    val code: String?,
    val label: String
)

object TranscriptionLanguages {
    val quickOptions: List<TranscriptionLanguageOption> = listOf(
        TranscriptionLanguageOption(code = null, label = "Auto"),
        TranscriptionLanguageOption(code = "sv", label = "Svenska")
    )
}

enum class OutputStyle {
    STANDARD,
    RELAXED,
    MINIMAL
}

enum class RewriteMode {
    NONE,
    FIX,
    POLISH
}

enum class SummarizeMode {
    NONE,
    LIGHT,
    HARD
}

@Immutable
data class TextReplacementRule(
    val id: Long = 0,
    val name: String,
    val pattern: String,
    val replacement: String,
    val isRegex: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Immutable
data class PostProcessingPrompt(
    val id: Long = 0,
    val title: String,
    val prompt: String,
    val builtIn: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

enum class DictationStatus {
    SUCCESS,
    FAILURE
}

@Immutable
data class AppContext(
    val label: String?,
    val packageName: String?
) {
    val hasData: Boolean
        get() = !label.isNullOrBlank() || !packageName.isNullOrBlank()

    val displayName: String?
        get() = label?.takeIf { it.isNotBlank() } ?: packageName?.takeIf { it.isNotBlank() }
}
