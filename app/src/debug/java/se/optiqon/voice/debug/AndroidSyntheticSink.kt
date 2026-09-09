package se.optiqon.voice.debug

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.room.Room
import kotlinx.coroutines.flow.first
import se.optiqon.voice.data.access.AccessStateStore
import se.optiqon.voice.data.db.OptiqonVoiceDatabase
import se.optiqon.voice.data.db.dao.DictationDao
import se.optiqon.voice.data.db.dao.ProfileDao
import se.optiqon.voice.data.db.dao.TextReplacementRuleDao
import se.optiqon.voice.data.db.entity.Dictation
import se.optiqon.voice.data.db.entity.ProfileEntity
import se.optiqon.voice.data.db.entity.TextReplacementRuleEntity
import se.optiqon.voice.data.preferences.PreferencesDataStore
import se.optiqon.voice.data.preferences.SecurePreferencesStore
import se.optiqon.voice.data.storage.DeviceDataOwner
import se.optiqon.voice.data.storage.StorageRoot
import se.optiqon.voice.di.DatabaseModule
import se.optiqon.voice.domain.access.AccessSnapshot
import se.optiqon.voice.domain.access.AccountStatus

/**
 * [SyntheticState.Sink] on the real stores.
 *
 * Writes go through the process's injected singletons (the seed refuses to run unless the
 * process root *is* the default root, so those singletons point at the right files). Reads
 * deliberately do not: after the synthetic uid has claimed the default root, a later process
 * with nobody signed in resolves to the `signedout` root, and a dump taken through the
 * singletons would then describe an empty root and prove nothing. The dump therefore opens
 * the default root's own files by name, whatever this process resolved to.
 */
class AndroidSyntheticSink(
    private val context: Context,
    private val processRoot: StorageRoot,
    private val profileDao: ProfileDao,
    private val ruleDao: TextReplacementRuleDao,
    private val dictationDao: DictationDao,
    private val preferences: PreferencesDataStore,
    private val deviceDataOwner: DeviceDataOwner,
    private val accessStateStore: AccessStateStore
) : SyntheticState.Sink {

    override val processRootName: String get() = processRoot.name

    override suspend fun insertProfile(profile: ProfileEntity) { profileDao.insert(profile) }
    override suspend fun insertRule(rule: TextReplacementRuleEntity) { ruleDao.insert(rule) }
    override suspend fun insertDictation(dictation: Dictation) { dictationDao.insert(dictation) }

    override suspend fun seedSettings(asrApiKey: String, llmApiKey: String) {
        preferences.updateAsrConfig("https://asr.synthetic.invalid/v1", asrApiKey, "whisper-synthetic")
        preferences.updateLlmConfig("https://llm.synthetic.invalid/v1", llmApiKey, "llm-synthetic", true)
        preferences.updateGeneralSettings(
            autoClipboard = false, vibrateOnRecord = false, pauseOtherAudio = true,
            silenceThresholdMs = 3210L, historyEnabled = true, keepStatsWithoutHistory = true,
            historyRetentionLimit = 123, startOnBoot = false
        )
        preferences.updatePreferredLanguages(listOf("sv", "en"))
        preferences.updateActiveLanguage("sv")
        preferences.updateProviderPreset("synthetic")
        preferences.setOnboardingComplete(true)
    }

    override fun claimDefaultAndActivate(uid: String) {
        deviceDataOwner.claimDefault(uid)
        deviceDataOwner.setActiveUid(uid)
    }

    override suspend fun recordApproved(uid: String) {
        accessStateStore.record(
            AccessSnapshot(
                uid = uid,
                status = AccountStatus.APPROVED,
                verifiedAtWallMs = System.currentTimeMillis(),
                verifiedAtElapsedMs = SystemClock.elapsedRealtime()
            )
        )
    }

    override suspend fun readDefaultRoot(): SyntheticState.DefaultRootState {
        val root = StorageRoot.DEFAULT
        val db = Room.databaseBuilder(context, OptiqonVoiceDatabase::class.java, root.databaseName)
            .addMigrations(*DatabaseModule.ALL_MIGRATIONS)
            .build()
        try {
            val userVersion = db.query("PRAGMA user_version", null)
                .use { c -> if (c.moveToFirst()) c.getInt(0) else -1 }
            val profileNames = strings(db, "SELECT name FROM profiles ORDER BY id")
            val activeNames = strings(db, "SELECT name FROM profiles WHERE isActive = 1 ORDER BY id")
            val ruleNames = strings(db, "SELECT name FROM text_replacement_rules ORDER BY id")
            val dictationTexts = strings(db, "SELECT text FROM dictations WHERE historyVisible = 1 ORDER BY id")
            val words = db.query("SELECT COALESCE(SUM(wordCount), 0) FROM dictations WHERE historyVisible = 1", null)
                .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

            val secure = SecurePreferencesStore(context, root)
            val prefs = PreferencesDataStore(context, secure, root).preferences.first()
            val settings = linkedMapOf(
                "asr_base_url" to prefs.asrBaseUrl,
                "asr_model" to prefs.asrModel,
                "llm_base_url" to prefs.llmBaseUrl,
                "llm_model" to prefs.llmModel,
                "llm_enabled" to prefs.llmEnabled.toString(),
                "auto_clipboard" to prefs.autoClipboard.toString(),
                "vibrate_on_record" to prefs.vibrateOnRecord.toString(),
                "pause_other_audio" to prefs.pauseOtherAudio.toString(),
                "silence_threshold_ms" to prefs.silenceThresholdMs.toString(),
                "preferred_languages" to prefs.preferredLanguages.joinToString(","),
                "active_language" to prefs.activeLanguage.toString(),
                "history_enabled" to prefs.historyEnabled.toString(),
                "keep_stats_without_history" to prefs.keepStatsWithoutHistory.toString(),
                "history_retention_limit" to prefs.historyRetentionLimit.toString(),
                "start_on_boot" to prefs.startOnBoot.toString(),
                "onboarding_complete" to prefs.onboardingComplete.toString(),
                "provider_preset_id" to prefs.providerPresetId
            )
            val owner = deviceDataOwner.defaultOwner()
            val access = owner?.let { accessStateStore.snapshot(it).first()?.status?.name }
            return SyntheticState.DefaultRootState(
                processRoot = processRoot.name,
                dbUserVersion = userVersion,
                profileNames = profileNames,
                activeProfileNames = activeNames,
                ruleNames = ruleNames,
                dictationCount = dictationTexts.size,
                dictationWordTotal = words,
                dictationTexts = dictationTexts,
                settings = settings,
                asrApiKey = secure.getAsrApiKey(),
                llmApiKey = secure.getLlmApiKey(),
                defaultOwner = owner,
                activeUid = deviceDataOwner.activeUid(),
                accessStatus = access
            )
        } finally {
            db.close()
        }
    }

    override fun signingReport(): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return listOf("signing_api=unavailable_below_28")
        val info = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
            ?: return listOf("signing_info=null")
        val current = info.apkContentsSigners.map { SyntheticState.sha256Hex(it.toByteArray()) }
        val history = if (info.hasMultipleSigners()) emptyList()
            else info.signingCertificateHistory.map { SyntheticState.sha256Hex(it.toByteArray()) }
        return listOf(
            "signers=${current.joinToString(",")}",
            "history=${history.joinToString(",")}",
            "multiple_signers=${info.hasMultipleSigners()}"
        )
    }

    private fun strings(db: OptiqonVoiceDatabase, sql: String): List<String> =
        db.query(sql, null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
}
