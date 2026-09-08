package se.optiqon.voice.domain.transcription

import android.content.Context
import android.util.Log
import se.optiqon.voice.data.db.dao.DictationDao
import se.optiqon.voice.data.db.dao.LifetimeStatsDao
import se.optiqon.voice.data.db.entity.Dictation
import se.optiqon.voice.data.preferences.PreferencesDataStore
import se.optiqon.voice.data.repository.ProfileRepository
import se.optiqon.voice.domain.model.AppContext
import se.optiqon.voice.domain.model.DictationStatus
import se.optiqon.voice.data.storage.StorageRoot
import se.optiqon.voice.domain.access.AccessGrant
import se.optiqon.voice.domain.access.AccessGuard
import se.optiqon.voice.domain.access.AccessLease
import se.optiqon.voice.domain.access.AccessRevokedException
import se.optiqon.voice.domain.model.Profile
import se.optiqon.voice.domain.processing.TextProcessor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TranscriptionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val whisperEngine: WhisperEngine,
    private val textProcessor: TextProcessor,
    private val dictationDao: DictationDao,
    private val lifetimeStatsDao: LifetimeStatsDao,
    private val preferencesDataStore: PreferencesDataStore,
    private val profileRepository: ProfileRepository,
    private val accessGuard: AccessGuard,
    private val storageRoot: StorageRoot = StorageRoot.DEFAULT
) {
    companion object {
        private const val TAG = "TranscriptionManager"
    }

    /**
     * @param lease the authorisation this dictation was started under. Omitting it makes this
     * class obtain its own, which is what closes the history-retry hole: before, the gate lived
     * in the caller, and one of the four callers simply did not have one. A gate that a caller
     * can forget is not a gate.
     */
    suspend fun transcribe(
        audioFile: File,
        durationMs: Long,
        appContext: AppContext?,
        lease: AccessLease? = null,
        onPostProcessing: (() -> Unit)? = null
    ): Result<String> {
        val granted = leaseOrFailure(lease).getOrElse { return Result.failure(it) }
        val profile = profileRepository.getActiveProfile()
        return transcribeWithProfile(
            audioFile,
            durationMs,
            appContext,
            profile,
            retryEntryId = null,
            lease = granted,
            onPostProcessing = onPostProcessing
        )
    }

    suspend fun retry(dictationId: Long, lease: AccessLease? = null): Result<String> {
        val granted = leaseOrFailure(lease).getOrElse { return Result.failure(it) }
        val entry = dictationDao.getById(dictationId)
            ?: return Result.failure(IllegalArgumentException("History entry not found."))
        val audioPath = entry.audioPath
            ?: return Result.failure(IllegalStateException("No saved audio is available for retry."))
        val audioFile = File(audioPath)
        if (!audioFile.exists()) {
            return Result.failure(IllegalStateException("Saved audio file is missing."))
        }

        val profile = entry.profileId?.let { profileRepository.getProfile(it) }
            ?: profileRepository.getActiveProfile()
        return transcribeWithProfile(
            audioFile,
            entry.durationMs,
            entry.toAppContext(),
            profile,
            retryEntryId = entry.id,
            lease = granted
        )
    }

    suspend fun latestRetriableFailureId(): Long? = dictationDao.getLatestRetriableFailureId()

    private suspend fun transcribeWithProfile(
        audioFile: File,
        durationMs: Long,
        appContext: AppContext?,
        profile: Profile,
        retryEntryId: Long?,
        lease: AccessLease,
        onPostProcessing: (() -> Unit)? = null
    ): Result<String> {
        val prefs = preferencesDataStore.preferences.first()
        if (prefs.asrBaseUrl.isBlank() || prefs.asrApiKey.isBlank()) {
            val error = Exception("ASR not configured. Go to Settings to set up your Whisper endpoint.")
            persistFailure(audioFile, durationMs, appContext, profile, prefs.historyEnabled, error, retryEntryId)
            return Result.failure(error)
        }

        if (!whisperEngine.isAvailable()) {
            val error = Exception("No network connection. Cannot reach ASR server.")
            persistFailure(audioFile, durationMs, appContext, profile, prefs.historyEnabled, error, retryEntryId)
            return Result.failure(error)
        }

        // The last moment before the audio leaves the device. Everything above this line was
        // local; everything below is visible to a third party and cannot be taken back.
        lease.requireValid()

        Log.d(TAG, "Sending ${audioFile.length()} bytes to Whisper API (model=${profile.asrModel})")
        val rawResult = whisperEngine.transcribe(audioFile, profile.asrModel, profile.language)
        val rawText = rawResult.getOrElse { error ->
            Log.e(TAG, "Whisper API error", error)
            persistFailure(audioFile, durationMs, appContext, profile, prefs.historyEnabled, error, retryEntryId)
            return Result.failure(error)
        }

        if (rawText.isBlank()) {
            persistSuccess("", "", durationMs, appContext, profile, prefs.historyEnabled, prefs.keepStatsWithoutHistory, audioFile, retryEntryId)
            return Result.success("")
        }

        // A second outgoing call, and a second boundary. The ASR round trip above can take
        // seconds, which is long enough for an approval to be withdrawn between the two.
        lease.requireValid()

        val processedText = textProcessor.process(rawText, profile, appContext, onPostProcessing)
        recordLifetimeStats(processedText, durationMs)
        persistSuccess(
            text = processedText,
            rawText = rawText,
            durationMs = durationMs,
            appContext = appContext,
            profile = profile,
            historyEnabled = prefs.historyEnabled,
            keepStatsWithoutHistory = prefs.keepStatsWithoutHistory,
            audioFile = audioFile,
            retryEntryId = retryEntryId
        )
        return Result.success(processedText)
    }

    /**
     * Keeps a dictation that was stopped part-way, on exactly the terms the user already chose.
     *
     * Cancelling a dictation because the session ended is not a reason to destroy what was
     * recorded. It is also not a reason to start keeping audio for someone who turned that off,
     * so this reuses the ordinary failure path: history off means nothing is written and
     * nothing is retained, which is the user's own standing instruction rather than a new
     * decision taken on their behalf.
     *
     * The row is not auto-retried. It is retriable only by its owner, and only through the
     * guard, which refuses while that owner is blocked.
     */
    suspend fun preserveInterrupted(
        audioFile: File,
        durationMs: Long,
        appContext: AppContext?,
        cause: Throwable
    ) {
        if (!audioFile.exists() || audioFile.length() == 0L) return
        val prefs = preferencesDataStore.preferences.first()
        val profile = profileRepository.getActiveProfile()
        persistFailure(
            audioFile = audioFile,
            durationMs = durationMs,
            appContext = appContext,
            profile = profile,
            historyEnabled = prefs.historyEnabled,
            error = cause,
            retryEntryId = null
        )
    }

    /**
     * Resolves the authorisation for this dictation, without writing a history row when there
     * is none. A refusal on account grounds is not a transcription failure: recording it as one
     * would leave a blocked user a list of failed dictations they could not have avoided, and a
     * retriable entry for work that must not be retried.
     */
    private suspend fun leaseOrFailure(lease: AccessLease?): Result<AccessLease> {
        if (lease != null) return Result.success(lease)
        return when (val grant = accessGuard.authorize()) {
            is AccessGrant.Granted -> Result.success(grant.lease)
            is AccessGrant.Denied -> Result.failure(AccessRevokedException(grant.reason))
        }
    }

    private suspend fun persistSuccess(
        text: String,
        rawText: String,
        durationMs: Long,
        appContext: AppContext?,
        profile: Profile,
        historyEnabled: Boolean,
        keepStatsWithoutHistory: Boolean,
        audioFile: File,
        retryEntryId: Long?
    ) {
        if (!historyEnabled && !keepStatsWithoutHistory && retryEntryId == null) return
        val wordCount = countWords(text)
        val savedAudioPath = if (historyEnabled) retainAudio(audioFile, retryEntryId) else null
        upsertHistory(
            retryEntryId = retryEntryId,
            text = if (historyEnabled) text else "",
            rawText = if (historyEnabled) rawText else "",
            wordCount = wordCount,
            durationMs = durationMs,
            appContext = if (historyEnabled) appContext else null,
            profile = profile,
            status = DictationStatus.SUCCESS,
            errorMessage = null,
            audioPath = savedAudioPath,
            historyVisible = historyEnabled
        )
        pruneHistory()
    }

    private suspend fun persistFailure(
        audioFile: File,
        durationMs: Long,
        appContext: AppContext?,
        profile: Profile,
        historyEnabled: Boolean,
        error: Throwable,
        retryEntryId: Long?
    ) {
        if (!historyEnabled && retryEntryId == null) return
        val savedAudioPath = if (historyEnabled) retainAudio(audioFile, retryEntryId) else null
        upsertHistory(
            retryEntryId = retryEntryId,
            text = "",
            rawText = "",
            wordCount = 0,
            durationMs = durationMs,
            appContext = appContext,
            profile = profile,
            status = DictationStatus.FAILURE,
            errorMessage = error.message ?: "Unknown transcription error",
            audioPath = savedAudioPath,
            historyVisible = historyEnabled
        )
        pruneHistory()
    }

    private suspend fun upsertHistory(
        retryEntryId: Long?,
        text: String,
        rawText: String,
        wordCount: Int,
        durationMs: Long,
        appContext: AppContext?,
        profile: Profile,
        status: DictationStatus,
        errorMessage: String?,
        audioPath: String?,
        historyVisible: Boolean
    ) {
        val timestamp = System.currentTimeMillis()
        if (retryEntryId == null) {
            dictationDao.insert(
                Dictation(
                    text = text,
                    rawText = rawText,
                    wordCount = wordCount,
                    timestamp = timestamp,
                    sourceApp = appContext?.label,
                    sourceAppPackage = appContext?.packageName,
                    durationMs = durationMs,
                    historyVisible = historyVisible,
                    status = status.name,
                    errorMessage = errorMessage,
                    profileId = profile.id.takeIf { it != 0L },
                    audioPath = audioPath
                )
            )
        } else {
            val previousAudioPath = dictationDao.getAudioPath(retryEntryId)
            if (previousAudioPath != null && previousAudioPath != audioPath) {
                deleteRetainedAudio(previousAudioPath)
            }
            dictationDao.updateRetryResult(
                id = retryEntryId,
                text = text,
                rawText = rawText,
                wordCount = wordCount,
                timestamp = timestamp,
                status = status.name,
                errorMessage = errorMessage,
                sourceApp = appContext?.label,
                sourceAppPackage = appContext?.packageName,
                profileId = profile.id.takeIf { it != 0L },
                audioPath = audioPath,
                historyVisible = historyVisible
            )
        }
    }

    private fun countWords(text: String): Int =
        text.split("\\s+".toRegex()).count { it.isNotBlank() }

    /**
     * Lifetime counters are content-free and deliberately independent of the history
     * settings: they must keep accumulating when history is off, when an entry is
     * deleted, and when [pruneHistory] drops the row this dictation is stored in.
     * A counter failure must never fail the dictation itself.
     */
    private suspend fun recordLifetimeStats(text: String, durationMs: Long) {
        val wordCount = countWords(text)
        if (wordCount == 0) return
        try {
            lifetimeStatsDao.record(wordCount, durationMs, System.currentTimeMillis())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record lifetime stats", e)
        }
    }

    private suspend fun pruneHistory() {
        val limit = preferencesDataStore.preferences.first().historyRetentionLimit
        dictationDao.getPrunableAudioPaths(limit).forEach(::deleteRetainedAudio)
        dictationDao.pruneOldEntries(limit)
    }

    private fun retainAudio(audioFile: File, retryEntryId: Long?): String? {
        if (retryEntryId != null && audioFile.exists() && audioFile.parentFile == storageRoot.retainedAudioDir(context.filesDir)) {
            return audioFile.absolutePath
        }
        return runCatching {
            val dir = storageRoot.retainedAudioDir(context.filesDir).apply { mkdirs() }
            val retained = File(dir, "dictation_${System.currentTimeMillis()}.wav")
            audioFile.copyTo(retained, overwrite = true)
            retained.absolutePath
        }.onFailure { Log.e(TAG, "Could not retain audio", it) }.getOrNull()
    }

    private fun deleteRetainedAudio(path: String) {
        runCatching {
            val file = File(path)
            if (file.canonicalPath.startsWith(context.filesDir.canonicalPath)) {
                file.delete()
            }
        }
    }

    private fun Dictation.toAppContext(): AppContext? {
        val storedLabel = sourceApp?.takeIf { it.isNotBlank() && !it.equals("App", ignoreCase = true) }
        val inferredPackage = sourceAppPackage ?: storedLabel?.takeIf { it.contains('.') }
        val context = AppContext(
            label = storedLabel?.takeUnless { sourceAppPackage == null && it.contains('.') },
            packageName = inferredPackage
        )
        return context.takeIf { it.hasData }
    }
}
