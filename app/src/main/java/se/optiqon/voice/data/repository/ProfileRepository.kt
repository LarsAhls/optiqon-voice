package se.optiqon.voice.data.repository

import se.optiqon.voice.data.db.dao.PostProcessingPromptDao
import se.optiqon.voice.data.db.dao.ProfileDao
import se.optiqon.voice.data.db.entity.PostProcessingPromptEntity
import se.optiqon.voice.data.db.entity.ProfileEntity
import se.optiqon.voice.data.db.entity.toEntity
import se.optiqon.voice.data.preferences.PreferencesDataStore
import se.optiqon.voice.domain.capability.CapabilityEnvironment
import se.optiqon.voice.domain.model.Profile
import se.optiqon.voice.domain.model.TranscriptionLanguages
import se.optiqon.voice.domain.processing.BuiltInPrompt
import se.optiqon.voice.domain.processing.BuiltInPrompts
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileRepository @Inject constructor(
    private val profileDao: ProfileDao,
    private val promptDao: PostProcessingPromptDao,
    private val preferencesDataStore: PreferencesDataStore
) {
    val profiles: Flow<List<Profile>> = profileDao.observeProfiles()
        .map { profiles -> profiles.map(ProfileEntity::toDomain) }

    val activeProfile: Flow<Profile?> = profileDao.observeActiveProfile()
        .map { it?.toDomain() }

    /**
     * What the configured provider lets a profile do — the global half of the answer the
     * profile card prints.
     *
     * It is exposed here rather than by handing the preferences to the view model because this
     * repository already holds them, and because the key must not travel: the flow emits
     * [CapabilityEnvironment], which carries whether a key is set and never the key itself.
     */
    val capabilityEnvironment: Flow<CapabilityEnvironment> = preferencesDataStore.preferences
        .map { prefs -> CapabilityEnvironment.from(prefs.llmBaseUrl, prefs.llmApiKey) }

    suspend fun ensureDefaults() {
        seedBuiltInPrompts()
        if (profileDao.countProfiles() == 0) {
            val prefs = preferencesDataStore.preferences.first()
            profileDao.insert(
                ProfileEntity(
                    name = "Standard",
                    isActive = true,
                    asrModel = prefs.asrModel,
                    // A fresh install has no language yet — onboarding runs after this seed —
                    // so the profile starts on the app's default rather than on Auto.
                    language = prefs.activeLanguage
                        ?: prefs.preferredLanguages.firstOrNull()
                        ?: TranscriptionLanguages.DEFAULT_CODE,
                    llmEnabled = prefs.llmEnabled,
                    llmModel = prefs.llmModel
                )
            )
        } else if (profileDao.activeCount() == 0) {
            profileDao.firstProfileId()?.let { profileDao.activate(it) }
        }
    }

    /**
     * Onboarding answers belong on the profile the bubble actually uses, not only in
     * preferences — otherwise the first dictation runs on the seeded defaults.
     */
    suspend fun applyLanguageToActiveProfile(language: String?) {
        ensureDefaults()
        val active = profileDao.getActiveProfile() ?: return
        profileDao.update(active.copy(language = language))
    }

    suspend fun applyProviderToActiveProfile(asrModel: String, llmModel: String, llmEnabled: Boolean) {
        ensureDefaults()
        val active = profileDao.getActiveProfile() ?: return
        profileDao.update(active.copy(asrModel = asrModel, llmModel = llmModel, llmEnabled = llmEnabled))
    }

    suspend fun getActiveProfile(): Profile {
        ensureDefaults()
        return profileDao.getActiveProfile()?.toDomain()
            ?: Profile(name = "Standard", isActive = true)
    }

    suspend fun getProfile(id: Long): Profile? = profileDao.getProfile(id)?.toDomain()

    suspend fun save(profile: Profile): Long {
        val cleanProfile = profile.copy(name = profile.name.trim().ifBlank { "Untitled profile" })
        return if (cleanProfile.id == 0L) {
            profileDao.insert(cleanProfile.toEntity())
        } else {
            val existing = profileDao.getProfile(cleanProfile.id)
            profileDao.update(cleanProfile.toEntity().copy(createdAt = existing?.createdAt ?: System.currentTimeMillis()))
            cleanProfile.id
        }
    }

    suspend fun activate(id: Long) {
        profileDao.activate(id)
    }

    suspend fun duplicate(profile: Profile) {
        profileDao.insert(
            profile.copy(
                id = 0,
                name = "${profile.name} copy",
                isActive = false
            ).toEntity()
        )
    }

    suspend fun delete(id: Long) {
        val wasActive = profileDao.getProfile(id)?.isActive == true
        profileDao.deleteIfNotLast(id)
        if (wasActive && profileDao.activeCount() == 0) {
            profileDao.firstProfileId()?.let { profileDao.activate(it) }
        }
    }

    private suspend fun seedBuiltInPrompts(): Set<Long> {
        val existingBuiltIns = promptDao.getBuiltIns()

        BUILT_IN_PROMPTS.forEach { seed ->
            val matchingPrompts = existingBuiltIns
                .filter { prompt -> prompt.title == seed.title || prompt.title in seed.legacyTitles }
                .sortedBy(PostProcessingPromptEntity::createdAt)

            val retainedPrompt = matchingPrompts.firstOrNull()
            if (retainedPrompt == null) {
                promptDao.insert(seed.toEntity())
            } else {
                promptDao.update(
                    retainedPrompt.copy(
                        title = seed.title,
                        prompt = seed.prompt,
                        builtIn = true
                    )
                )
                matchingPrompts.drop(1).forEach { duplicate ->
                    promptDao.deleteBuiltInById(duplicate.id)
                }
            }
        }
        return promptDao.getBuiltIns().map { it.id }.toSet()
    }

    private companion object {
        private val BUILT_IN_PROMPTS = BuiltInPrompts.ALL

        private fun BuiltInPrompt.toEntity(): PostProcessingPromptEntity = PostProcessingPromptEntity(
            title = title,
            prompt = prompt,
            builtIn = true
        )
    }
}
