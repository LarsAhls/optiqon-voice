package se.optiqon.voice.domain.provider

/**
 * A provider the app knows how to fill in for you. Everything here is public API surface —
 * a base URL, a model name and where to get a key — so a preset carries no secret and is
 * safe to keep in source.
 */
data class ProviderPreset(
    val id: String,
    val displayName: String,
    val summary: String,
    /**
     * Base URL for both services. The Retrofit interfaces append `v1/...` themselves, so this
     * stops one segment short of the version.
     */
    val baseUrl: String,
    val asrModel: String,
    val llmModel: String,
    /** Where the user goes to create a key. Opened in a browser, never called by the app. */
    val consoleUrl: String,
    val keyPrefixHint: String
)

object ProviderPresets {

    /**
     * Values taken from Groq's public API documentation (console.groq.com/docs, checked
     * 2026-09-06): the OpenAI-compatible base is `https://api.groq.com/openai/v1`, with
     * `whisper-large-v3-turbo` documented as the price/performance choice for multilingual
     * speech and `llama-3.1-8b-instant` as the small instruction-following text model. The
     * app appends `v1/` itself, hence the shorter base here.
     */
    val GROQ = ProviderPreset(
        id = "groq",
        displayName = "Groq",
        summary = "Fast Whisper transcription. Free tier, no card needed.",
        baseUrl = "https://api.groq.com/openai/",
        asrModel = "whisper-large-v3-turbo",
        llmModel = "llama-3.1-8b-instant",
        consoleUrl = "https://console.groq.com/keys",
        keyPrefixHint = "gsk_"
    )

    /** Any other server that speaks the same endpoints. The user supplies every field. */
    val CUSTOM = ProviderPreset(
        id = "custom",
        displayName = "Custom / other compatible provider",
        summary = "Any OpenAI-compatible endpoint, including one you host yourself.",
        baseUrl = "",
        asrModel = "whisper-1",
        llmModel = "gpt-4o-mini",
        consoleUrl = "",
        keyPrefixHint = ""
    )

    /** The order they are offered in. The recommended one is first and is preselected. */
    val ALL = listOf(GROQ, CUSTOM)

    val RECOMMENDED = GROQ

    fun byId(id: String?): ProviderPreset = ALL.firstOrNull { it.id == id } ?: RECOMMENDED
}
