package se.optiqon.voice.data.preferences

data class UserPreferences(
    val asrBaseUrl: String = "",
    val asrApiKey: String = "",
    val asrModel: String = "whisper-1",
    val llmBaseUrl: String = "",
    val llmApiKey: String = "",
    val llmModel: String = "gpt-4o-mini",
    val llmEnabled: Boolean = false,
    val autoClipboard: Boolean = true,
    val vibrateOnRecord: Boolean = true,
    val pauseOtherAudio: Boolean = false,
    val silenceThresholdMs: Long = 2000,
    val preferredLanguages: List<String> = emptyList(),
    val activeLanguage: String? = null,
    val historyEnabled: Boolean = true,
    val keepStatsWithoutHistory: Boolean = false,
    val historyRetentionLimit: Int = 500,
    val startOnBoot: Boolean = true,
    /**
     * False until the first-run flow has been walked through. Installs that predate the flow
     * are treated as done, so nobody who already has a working setup is sent back to step one.
     */
    val onboardingComplete: Boolean = false,
    /** Which provider preset filled the endpoint fields in, or "custom" if the user did. */
    val providerPresetId: String = "custom"
)
