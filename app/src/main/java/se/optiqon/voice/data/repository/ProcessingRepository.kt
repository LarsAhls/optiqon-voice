package se.optiqon.voice.data.repository

import se.optiqon.voice.data.db.dao.PostProcessingPromptDao
import se.optiqon.voice.data.db.dao.TextReplacementRuleDao
import se.optiqon.voice.data.db.entity.PostProcessingPromptEntity
import se.optiqon.voice.data.db.entity.TextReplacementRuleEntity
import se.optiqon.voice.data.db.entity.toEntity
import se.optiqon.voice.domain.model.AppContext
import se.optiqon.voice.domain.model.PostProcessingPrompt
import se.optiqon.voice.domain.model.Profile
import se.optiqon.voice.domain.model.TextReplacementRule
import se.optiqon.voice.domain.processing.SystemPromptBuilder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProcessingRepository @Inject constructor(
    private val ruleDao: TextReplacementRuleDao,
    private val promptDao: PostProcessingPromptDao
) {
    val rules: Flow<List<TextReplacementRule>> = ruleDao.observeAll()
        .map { rules -> rules.map(TextReplacementRuleEntity::toDomain) }

    val prompts: Flow<List<PostProcessingPrompt>> = promptDao.observeAll()
        .map { prompts -> prompts.map(PostProcessingPromptEntity::toDomain) }

    suspend fun saveRule(rule: TextReplacementRule): Long {
        return if (rule.id == 0L) ruleDao.insert(rule.toEntity()) else {
            ruleDao.update(rule.toEntity())
            rule.id
        }
    }

    suspend fun deleteRule(id: Long) {
        ruleDao.deleteById(id)
    }

    suspend fun savePrompt(prompt: PostProcessingPrompt): Long {
        return if (prompt.id == 0L) promptDao.insert(prompt.toEntity()) else {
            val existing = promptDao.getById(prompt.id) ?: return 0L
            if (!existing.builtIn) promptDao.update(prompt.copy(builtIn = false).toEntity())
            prompt.id
        }
    }

    suspend fun deletePrompt(id: Long) {
        promptDao.deleteCustomById(id)
    }

    suspend fun applySelectedRules(text: String, selectedRuleIds: Set<Long>): String {
        if (selectedRuleIds.isEmpty()) return text
        return ruleDao.getAll()
            .map(TextReplacementRuleEntity::toDomain)
            .filter { it.id in selectedRuleIds && it.pattern.isNotBlank() }
            .fold(text) { current, rule ->
                if (rule.isRegex) {
                    runCatching { Regex(rule.pattern).replace(current, rule.replacement) }
                        .getOrElse { current }
                } else {
                    current.replace(rule.pattern, rule.replacement)
                }
            }
    }

    /**
     * Loads the prompt text for [profile] and hands assembly to [SystemPromptBuilder],
     * which holds the ordering and precedence rules and is unit-tested directly.
     */
    suspend fun buildSystemPrompt(profile: Profile, appContext: AppContext?): String {
        return SystemPromptBuilder.build(
            profile = profile,
            appContext = appContext,
            prompts = promptDao.getAll().map(PostProcessingPromptEntity::toDomain)
        )
    }
}
