package se.optiqon.voice.domain.model

/**
 * What a profile is *for*, as the user declared it.
 *
 * Before this existed, tone was guessed from the foreground app's package name
 * ([se.optiqon.voice.domain.processing.SystemPromptBuilder]), which guesses wrong in exactly the
 * cases a dictation user hits: mail written in a browser, chat in a webview, notes taken inside a
 * chat app. The guess is still there, but only for [GENERAL] — every other kind says it outright.
 *
 * [GENERAL] is the default for every migrated row and must keep producing a byte-identical system
 * prompt to the build before this field existed, so upgrading changes no existing profile's output.
 * [VERBATIM] is the one kind that adds an answer rather than a string: it emits no tone hint at all.
 */
enum class ProfileKind {
    GENERAL,
    EMAIL,
    CHAT,
    NOTES,
    SOCIAL,
    VERBATIM
}

/**
 * A kind's label, the tone line it emits, and the style settings it suggests.
 *
 * [suggested] is applied when the user picks the kind in the profile editor. That is an explicit
 * action on a screen that shows the style controls right below the picker, so the change is visible
 * and can be undone before saving — it is never applied to a stored profile behind the user's back,
 * and never by the migration, which leaves every existing row on [ProfileKind.GENERAL].
 */
data class ProfileKindPreset(
    val kind: ProfileKind,
    val label: String,
    val description: String,
    /**
     * The tone line emitted into the system prompt, or null when the kind emits none.
     *
     * For [ProfileKind.GENERAL] this is null because the app-name guess still runs; for
     * [ProfileKind.VERBATIM] it is null because no tone hint is wanted at all. The two nulls mean
     * different things, which is why [ProfileCapabilities] reports them with different reasons.
     */
    val toneHint: String?,
    val suggested: SuggestedStyle
)

/** The style fields a kind suggests. Mirrors the matching fields on [Profile] exactly. */
data class SuggestedStyle(
    val outputStyle: OutputStyle = OutputStyle.STANDARD,
    val rewriteMode: RewriteMode = RewriteMode.FIX,
    val summarizeMode: SummarizeMode = SummarizeMode.NONE,
    val emojiAllowed: Boolean = false
)

object ProfileKinds {

    /**
     * The four tone strings are **copied verbatim** out of the app-name guess they replace, so the
     * prompt text emitted for a given tone is unchanged by this mission. Reword them and the
     * emitted prompt changes for every profile of that kind — which is a product decision, not a
     * tidy-up.
     */
    val GENERAL = ProfileKindPreset(
        kind = ProfileKind.GENERAL,
        label = "General",
        description = "Guess the tone from whichever app you are dictating into.",
        toneHint = null,
        suggested = SuggestedStyle()
    )

    val EMAIL = ProfileKindPreset(
        kind = ProfileKind.EMAIL,
        label = "Email",
        description = "Professional tone, greetings and sign-offs kept.",
        toneHint = "Use a professional written tone with proper greetings and sign-offs if present.",
        suggested = SuggestedStyle()
    )

    val CHAT = ProfileKindPreset(
        kind = ProfileKind.CHAT,
        label = "Chat",
        description = "Casual and concise, the way people write in chat.",
        toneHint = "Use a casual conversational tone. Keep it concise and natural for chat.",
        suggested = SuggestedStyle(
            outputStyle = OutputStyle.RELAXED,
            emojiAllowed = true
        )
    )

    val NOTES = ProfileKindPreset(
        kind = ProfileKind.NOTES,
        label = "Notes and documents",
        description = "Structured writing, with rambling cut.",
        toneHint = "Use a clear, structured writing style suitable for documents and notes.",
        // Decided by Lars 2026-09-10: notes get light condensing by default, because a dictated
        // note is the case where repetition is least wanted. It is the one suggestion in this
        // registry that drops material rather than reshaping it.
        suggested = SuggestedStyle(summarizeMode = SummarizeMode.LIGHT)
    )

    val SOCIAL = ProfileKindPreset(
        kind = ProfileKind.SOCIAL,
        label = "Social media",
        description = "Short enough to post.",
        toneHint = "Keep it concise and suitable for social media posts.",
        suggested = SuggestedStyle(
            outputStyle = OutputStyle.RELAXED,
            emojiAllowed = true
        )
    )

    val VERBATIM = ProfileKindPreset(
        kind = ProfileKind.VERBATIM,
        label = "Verbatim",
        description = "No tone hint at all. Closest to what you actually said.",
        toneHint = null,
        suggested = SuggestedStyle(
            rewriteMode = RewriteMode.NONE,
            summarizeMode = SummarizeMode.NONE
        )
    )

    /** The order they are offered in. [GENERAL] is first because it is the default. */
    val ALL: List<ProfileKindPreset> = listOf(GENERAL, EMAIL, CHAT, NOTES, SOCIAL, VERBATIM)

    fun of(kind: ProfileKind): ProfileKindPreset =
        ALL.firstOrNull { it.kind == kind } ?: GENERAL
}
