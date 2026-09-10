package se.optiqon.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.optiqon.voice.testing.MODULE_DIR
import java.io.File

/**
 * Keeps the R8 keep rules honest about which types are read reflectively.
 *
 * This is the one defect class in this module that a green unit suite cannot see. Unit tests run
 * on the JVM against un-shrunk classes, so nothing they assert depends on R8 having kept
 * anything. The proof is on the record: `FeedbackPayload` was added to
 * `se.optiqon.voice.domain.feedback`, outside the two packages `proguard-rules.pro` enumerates,
 * and a real release build obfuscated its six fields to `a`…`f` while every test stayed green.
 * A wire contract was broken in a way only a release APK could show.
 *
 * What cannot be fixed by a test here is therefore split in two, and neither half is sufficient:
 *
 *  - this class, on the JVM, checks the *cause* — that every type on the contract is covered by
 *    a keep rule that preserves field names, and that the rules file it reads is the file R8 is
 *    actually pointed at. That is statically visible, so it can be checked cheaply and early.
 *  - `verifyReleaseReflectionContract`, wired into the release build, checks the *effect* — that
 *    R8's own `mapping.txt` shows each field mapped to itself. That is the only check that can
 *    fail for the real reason, and it cannot run here.
 *
 * The key-set assertions the debt review also asked for already exist, and are deliberately not
 * duplicated: `FeedbackPayloadTest` pins the encoded JSON's exact key set and
 * `FeedbackDocumentTest` pins the document's. Those catch a field added and forgotten. They
 * cannot catch a missing keep rule, because on the JVM there is no keep rule to miss.
 */
class ReleaseReflectionContractTest {

    @Test
    fun `every type whose field names are a wire contract is under a keep rule that preserves them`() {
        val rules = rulesFile().readText()
        val preserving = fieldPreservingKeepPatterns(rules)
        assertTrue(
            "proguard-rules.pro has no keep rule that preserves field names at all. Every type " +
                "on the contract in ${CONTRACT.name} would be obfuscated in release.",
            preserving.isNotEmpty()
        )
        for (type in contractTypes()) {
            assertTrue(
                "$type is listed in ${CONTRACT.name} as having field names that are read " +
                    "reflectively, but no rule in proguard-rules.pro keeps its <fields>. The " +
                    "release build will rename them and write {\"a\":…} on the wire, and no " +
                    "unit test can see it because unit tests run un-shrunk. Add a rule such as " +
                    "-keep class $type { <fields>; } — do not widen an existing rule to a " +
                    "whole package that does not need it.",
                preserving.any { it.matches(type) }
            )
        }
    }

    @Test
    fun `the rules these tests read are the rules R8 is given`() {
        // Without this the guard above can pass against a file the release build no longer uses:
        // rename the file, drop it out of proguardFiles, or turn minification off, and the whole
        // check becomes a statement about a text file nobody reads.
        //
        // It has to read the proguardFiles call rather than the whole build script. Searching the
        // file for the rules filename found the `inputs.file(…"proguard-rules.pro")` line added
        // further down for this very test, so pointing the release build at a different file left
        // the guard green — it was matching its own scaffolding.
        val build = File(MODULE_DIR, "build.gradle.kts").readText()
        val proguardFiles = callArguments(build, "proguardFiles(")
        assertTrue(
            "The release build's proguardFiles does not name $RULES_NAME, so the keep rules " +
                "this test reads are not the ones R8 applies. It passes: ${proguardFiles.trim()}",
            proguardFiles.contains("\"$RULES_NAME\"")
        )
        // Only the release block sets this today, so reading the whole file is safe — but it
        // would be wrong the moment a second build type sets it too, and silently so. Asserting
        // the count says that out loud instead of leaving the guard scoped to whichever match
        // happened to come first.
        val minify = Regex("""isMinifyEnabled\s*=\s*(\w+)""").findAll(build).toList()
        assertEquals(
            "More than one build type sets isMinifyEnabled. This guard can no longer tell which " +
                "is the release one; scope it to the release block.",
            1,
            minify.size
        )
        assertEquals(
            "The release build no longer minifies. The keep rules stop meaning anything, and so " +
                "does every check built on them — including the release-side one.",
            "true",
            minify.single().groupValues[1]
        )
    }

    @Test
    fun `no type reaches Gson through a route nobody checked`() {
        // The contract file can only be trusted if something notices a new reflective call site.
        // Resolving Kotlin types from source text is not reliable, so this guard does the part
        // that is: it pins *where* reflection happens. A new toJson/fromJson or a new @Body in
        // a file nobody has looked at fails here, and whoever added it decides whether the type
        // carries @SerializedName or belongs on the contract.
        val found = mainSources()
            .filter { source ->
                val text = source.readText()
                REFLECTION_MARKERS.any { text.contains(it) }
            }
            .map { it.name }
            .sorted()
        assertEquals(
            "The set of files that hand a type to Gson has changed. Every field of every type " +
                "serialised from a new site must either carry @SerializedName or have its class " +
                "added to ${CONTRACT.name} together with a keep rule. Update this list once " +
                "that is true.",
            REFLECTION_SITES.sorted(),
            found
        )
    }

    @Test
    fun `the contract names types that exist and really do need the rule`() {
        val sources = mainSources()
        for (type in contractTypes()) {
            val simple = type.substringAfterLast('.')
            val declaring = sources.filter { it.readText().contains("class $simple") }
            assertTrue(
                "${CONTRACT.name} lists $type, but no source in this module declares it. A " +
                    "renamed or deleted type leaves a keep rule guarding nothing, which reads " +
                    "like coverage.",
                declaring.isNotEmpty()
            )
            // A type every field of which is annotated is already rename-proof; listing it
            // anyway suggests the keep rule is what protects it, and someone will delete the
            // annotations trusting the rule, or delete the rule trusting the annotations.
            assertTrue(
                "$type is on the contract but its source names SerializedName, which already " +
                    "survives obfuscation. Either drop it from ${CONTRACT.name} or drop the " +
                    "annotations — as it stands neither protection is clearly the load-bearing one.",
                declaring.none { it.readText().contains("SerializedName") }
            )
        }
    }

    /**
     * The argument list of a call, balanced across nested parentheses, so that
     * `getDefaultProguardFile("…")` inside `proguardFiles(…)` does not end the match early.
     */
    private fun callArguments(text: String, call: String): String {
        val open = text.indexOf(call)
        check(open >= 0) { "No $call in build.gradle.kts; this guard has gone stale" }
        var depth = 0
        var i = open + call.length - 1
        while (i < text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return text.substring(open + call.length, i)
                }
            }
            i++
        }
        error("Unbalanced parentheses after $call in build.gradle.kts")
    }

    /**
     * A keep-rule class pattern, with R8's wildcards translated once.
     *
     * Translated character by character rather than by running replacements over an escaped
     * string: `Regex.escape` wraps the whole pattern in `\Q...\E` instead of backslashing each
     * metacharacter, so a replacement looking for an escaped star found nothing and every
     * wildcard pattern silently matched nothing at all. The exact rule on the contract has no
     * wildcards, so the guard passed and the bug only showed when a package-wide rule was tried.
     */
    private class KeepPattern(pattern: String) {
        private val regex = Regex(
            buildString {
                var i = 0
                while (i < pattern.length) {
                    val c = pattern[i]
                    when {
                        // `**` crosses package separators; a single `*` stops at one.
                        c == '*' && pattern.getOrNull(i + 1) == '*' -> { append(".*"); i += 2 }
                        c == '*' -> { append("[^.]*"); i++ }
                        c == '?' -> { append("[^.]"); i++ }
                        c in REGEX_META -> { append('\\').append(c); i++ }
                        else -> { append(c); i++ }
                    }
                }
            }
        )

        fun matches(type: String) = regex.matches(type)

        private companion object {
            const val REGEX_META = ".[]{}()+^$|"
        }
    }

    private fun fieldPreservingKeepPatterns(rules: String): List<KeepPattern> =
        KEEP_WITH_MEMBERS.findAll(withoutComments(rules))
            .filter { match ->
                val members = match.groupValues[2]
                // `<fields>` names them explicitly; `*` covers them along with everything else.
                members.contains("<fields>") || members.contains("*")
            }
            .map { KeepPattern(it.groupValues[1]) }
            .toList()

    /**
     * A commented-out rule keeps nothing. Matching the regex against the raw text found
     * `-keep` inside `# -keep …` and reported the rule as present, so a rule disabled rather
     * than deleted passed this guard while the release build was already obfuscating.
     */
    private fun withoutComments(rules: String): String =
        rules.lineSequence().joinToString("\n") { it.substringBefore('#') }

    private fun rulesFile(): File = File(MODULE_DIR, RULES_NAME).also {
        check(it.isFile) { "No $RULES_NAME in $MODULE_DIR; the rules moved and this guard is stale" }
    }

    private fun contractTypes(): List<String> = CONTRACT.readLines()
        .map { it.substringBefore('#').trim() }
        .filter { it.isNotEmpty() }
        .also { check(it.isNotEmpty()) { "${CONTRACT.name} lists no types; the contract is empty" } }

    private fun mainSources(): List<File> =
        File(MODULE_DIR, "src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
            .also { check(it.isNotEmpty()) { "No main sources under $MODULE_DIR; this guard has gone stale" } }

    private companion object {
        const val RULES_NAME = "proguard-rules.pro"

        val CONTRACT: File = File(MODULE_DIR, "release-reflection-contract.txt")

        /**
         * `-keep`, `-keepclassmembers` and their `,allow…` variants, up to the member block.
         * Rules without braces keep no members and so cannot preserve field names.
         */
        val KEEP_WITH_MEMBERS =
            Regex("""-keep\w*(?:,\w+)*\s+(?:class|interface)\s+([^\s{]+)\s*\{([^}]*)}""")

        /** What handing a type to Gson looks like in source. */
        val REFLECTION_MARKERS = listOf("toJson(", "fromJson(", "GsonConverterFactory", "@Body")

        /**
         * Every file that serialises or deserialises reflectively today. `FeedbackPayload.kt`
         * builds its own Gson; `ApiClientFactory.kt` installs the converter Retrofit uses, and
         * `LlmApiService.kt` is the one `@Body` going through it — all three of that route's
         * types are annotated, so none of them is on the contract.
         */
        val REFLECTION_SITES = listOf(
            "ApiClientFactory.kt",
            "FeedbackPayload.kt",
            "LlmApiService.kt"
        )
    }
}
