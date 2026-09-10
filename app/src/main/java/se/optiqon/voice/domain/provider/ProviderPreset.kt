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
) {
    /** Custom is the one preset that supplies nothing: every field is the user's own. */
    val isCustom: Boolean get() = id == ProviderPresets.CUSTOM.id
}

object ProviderPresets {

    /**
     * Values taken from Groq's public API documentation: the OpenAI-compatible base is
     * `https://api.groq.com/openai/v1`, with `whisper-large-v3-turbo` documented as the
     * price/performance choice for multilingual speech and `openai/gpt-oss-20b` as the small
     * instruction-following text model. The app appends `v1/` itself, hence the shorter base.
     *
     * Checked against **console.groq.com/docs/deprecations**, not only the model list —
     * 2026-09-09. The model list is the trap: it still showed `llama-3.1-8b-instant` as
     * production on 2026-09-06 when this file was first written, while the deprecation page
     * says that model was shut down on 2026-08-16. The device confirmed it empirically on
     * 2026-09-09 at 21:22 CEST — `POST /v1/chat/completions` returned 404 in 72 ms while
     * `/v1/audio/transcriptions` returned 200 (smoke finding F17). Re-check the deprecation
     * page, not the model page, before changing either name here.
     */
    val GROQ = ProviderPreset(
        id = "groq",
        displayName = "Groq",
        summary = "Fast Whisper transcription. Free tier, no card needed.",
        baseUrl = "https://api.groq.com/openai/",
        asrModel = "whisper-large-v3-turbo",
        llmModel = "openai/gpt-oss-20b",
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
