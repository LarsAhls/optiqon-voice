package se.optiqon.voice.debug

import se.optiqon.voice.data.db.entity.Dictation
import se.optiqon.voice.data.db.entity.ProfileEntity
import se.optiqon.voice.data.db.entity.TextReplacementRuleEntity
import se.optiqon.voice.data.storage.StorageRoot
import java.io.File
import java.security.MessageDigest

/**
 * Seeds the *default* storage root with a known, synthetic data set and renders a
 * deterministic description of it, so that an in-place update of the app (Mission L1, B.2:
 * a signing-key rotation) can be shown to leave every persisted thing exactly as it was.
 *
 * Debug source set only; reached through [AccessDebugReceiver] from adb. The Android plumbing
 * (Room, DataStore, EncryptedSharedPreferences, the binding prefs) sits behind [Sink] so this
 * class is unit-testable with a fake, and so the seed data is defined in exactly one place.
 *
 * What the rendered state contains: counts, names, settings values, the binding/claim of the
 * default root, the recorded access verdict, and SHA-256 digests of the two API keys read back
 * *decrypted* from the keystore-backed store. The key values themselves are never written
 * anywhere: a digest that is equal before and after proves the AndroidKeyStore master key
 * still unwraps them, which is the point of the check, without copying a secret into a file.
 */
class SyntheticState(private val sink: Sink, private val filesDir: File) {

    data class Outcome(val ok: Boolean, val description: String)

    /** Everything the dump reads from the default root, plus the process root for context. */
    data class DefaultRootState(
        val processRoot: String,
        val dbUserVersion: Int,
        val profileNames: List<String>,
        val activeProfileNames: List<String>,
        val ruleNames: List<String>,
        val dictationCount: Int,
        val dictationWordTotal: Int,
        val dictationTexts: List<String>,
        val settings: Map<String, String>,
        val asrApiKey: String,
        val llmApiKey: String,
        val defaultOwner: String?,
        val activeUid: String?,
        val accessStatus: String?
    )

    interface Sink {
        /** The root this process resolved at start-up; seeding only makes sense on the default one. */
        val processRootName: String
        suspend fun insertProfile(profile: ProfileEntity)
        suspend fun insertRule(rule: TextReplacementRuleEntity)
        suspend fun insertDictation(dictation: Dictation)
        suspend fun seedSettings(asrApiKey: String, llmApiKey: String)
        /** Must throw [IllegalStateException] when the default root is already claimed. */
        fun claimDefaultAndActivate(uid: String)
        suspend fun recordApproved(uid: String)
        suspend fun readDefaultRoot(): DefaultRootState
    }

    suspend fun seed(): Outcome {
        if (sink.processRootName != StorageRoot.DEFAULT.name) {
            return Outcome(
                false,
                "process root is '${sink.processRootName}', not '${StorageRoot.DEFAULT.name}'; not seeding"
            )
        }
        val before = sink.readDefaultRoot()
        if (before.profileNames.any { it.startsWith(PROFILE_PREFIX) }) {
            return Outcome(false, "already seeded (a '$PROFILE_PREFIX*' profile exists); nothing written")
        }
        if (before.defaultOwner != null) {
            return Outcome(false, "default root already claimed by another uid; nothing written")
        }

        PROFILES.forEach { sink.insertProfile(it) }
        RULES.forEach { sink.insertRule(it) }
        DICTATIONS.forEach { sink.insertDictation(it) }
        sink.seedSettings(ASR_API_KEY, LLM_API_KEY)
        sink.claimDefaultAndActivate(UID)
        sink.recordApproved(UID)

        val after = sink.readDefaultRoot()
        return Outcome(
            true,
            "seeded: profiles=${after.profileNames.size} rules=${after.ruleNames.size} " +
                "dictations=${after.dictationCount} owner=${after.defaultOwner} access=${after.accessStatus}"
        )
    }

    suspend fun dump(): Outcome {
        val state = sink.readDefaultRoot()
        val text = render(state)
        val target = File(File(filesDir, "debug"), STATE_FILE_NAME)
        target.parentFile?.mkdirs()
        target.writeText(text)
        return Outcome(true, "state written to files/debug/$STATE_FILE_NAME (${text.lines().size} lines)")
    }

    /** Deterministic: same state, same text. Key values appear only as digests. */
    fun render(s: DefaultRootState): String = buildString {
        appendLine("process_root=${s.processRoot}")
        appendLine("db_user_version=${s.dbUserVersion}")
        appendLine("profiles=${s.profileNames.size}")
        appendLine("profile_names=${s.profileNames.sorted().joinToString("|")}")
        appendLine("active_profiles=${s.activeProfileNames.sorted().joinToString("|")}")
        appendLine("rules=${s.ruleNames.size}")
        appendLine("rule_names=${s.ruleNames.sorted().joinToString("|")}")
        appendLine("dictations=${s.dictationCount}")
        appendLine("dictation_words=${s.dictationWordTotal}")
        appendLine("dictation_texts_sha256=${sha256(s.dictationTexts.sorted().joinToString("\n"))}")
        s.settings.toSortedMap().forEach { (k, v) -> appendLine("setting.$k=$v") }
        appendLine("asr_api_key_len=${s.asrApiKey.length}")
        appendLine("asr_api_key_sha256=${sha256(s.asrApiKey)}")
        appendLine("llm_api_key_len=${s.llmApiKey.length}")
        appendLine("llm_api_key_sha256=${sha256(s.llmApiKey)}")
        appendLine("default_owner=${s.defaultOwner}")
        appendLine("active_uid=${s.activeUid}")
        appendLine("access_status=${s.accessStatus}")
    }

    companion object {
        const val STATE_FILE_NAME = "state.txt"
        const val UID = "synthetic-l1-user"
        const val PROFILE_PREFIX = "Synthetic "

        // Synthetic credentials: not valid anywhere, present only so that decryption after an
        // update can be proven by digest. They must never be real.
        const val ASR_API_KEY = "synthetic-asr-key-0123456789abcdef0123456789abcdef"
        const val LLM_API_KEY = "synthetic-llm-key-fedcba9876543210fedcba9876543210"

        val PROFILES = listOf(
            ProfileEntity(
                name = "${PROFILE_PREFIX}Alpha", isActive = true, asrModel = "whisper-synthetic",
                language = "sv", llmEnabled = true, llmModel = "llm-synthetic",
                profilePrompt = "Skriv kort.", emojiAllowed = true, selectedRuleIds = "1,2",
                createdAt = 1_700_000_000_000L, updatedAt = 1_700_000_000_000L
            ),
            ProfileEntity(
                name = "${PROFILE_PREFIX}Beta", isActive = false, language = "en",
                createdAt = 1_700_000_001_000L, updatedAt = 1_700_000_001_000L
            )
        )
        val RULES = listOf(
            TextReplacementRuleEntity(
                name = "synthetic plain", pattern = "optikon", replacement = "OPTIQON",
                createdAt = 1_700_000_002_000L
            ),
            TextReplacementRuleEntity(
                name = "synthetic regex", pattern = "\\bmvh\\b", replacement = "Med vänliga hälsningar",
                isRegex = true, createdAt = 1_700_000_003_000L
            ),
            TextReplacementRuleEntity(
                name = "synthetic unicode", pattern = "aa", replacement = "å",
                createdAt = 1_700_000_004_000L
            )
        )
        val DICTATIONS = (1..5).map { i ->
            val text = "Syntetisk diktering nummer $i med några ord åäö."
            Dictation(
                text = text, rawText = text.lowercase(), wordCount = 6 + i,
                timestamp = 1_700_000_010_000L + i * 60_000L, sourceApp = "Synthetic",
                sourceAppPackage = "se.optiqon.synthetic", durationMs = 1_000L * i, profileId = 1L
            )
        }

        fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
