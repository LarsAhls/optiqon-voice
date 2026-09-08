package se.optiqon.voice.domain.access

/**
 * What counts as a name, defined once.
 *
 * The product decision is that a name is required and a phone number is not, which only means
 * something if the app and the rules agree on what a name *is*. They previously did not: the
 * client sent whatever it had — including `email.substringBefore('@')`, a string the user never
 * saw, let alone chose — and the rules asked only that it be a non-empty string, which a single
 * space satisfies. So a registration could arrive named `" "`, and the person approving it would
 * be deciding about somebody they cannot identify.
 *
 * [normalize] is the reason the rules can stay simple. The client collapses whitespace and trims,
 * and the rules then require exactly that shape — `displayName.trim() == displayName` plus the
 * same bounds — so a client that skips normalisation is rejected rather than quietly accepted
 * with a name nobody can read.
 */
object DisplayName {

    /** Short enough for a mononym, long enough that a single letter is not a name. */
    const val MIN_LENGTH = 2

    /** Generous, and bounded: an unbounded name is a field somebody will paste a document into. */
    const val MAX_LENGTH = 80

    /**
     * Trims and collapses runs of whitespace. Deliberately does nothing else — no case change,
     * no character filtering — because a name is the user's own, and a rule that "cleans up"
     * unfamiliar scripts is a rule that gets somebody's name wrong.
     */
    fun normalize(raw: String): String = raw.trim().replace(WHITESPACE, " ")

    fun isValid(raw: String): Boolean = normalize(raw).length in MIN_LENGTH..MAX_LENGTH

    private val WHITESPACE = Regex("\\s+")
}
