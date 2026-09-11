package se.optiqon.voice.domain.processing

import android.util.Log
import se.optiqon.voice.data.api.ApiClientFactory
import se.optiqon.voice.data.api.LlmApiService
import se.optiqon.voice.data.api.model.ChatCompletionRequest
import se.optiqon.voice.data.api.model.ChatMessage
import se.optiqon.voice.data.preferences.PreferencesDataStore
import se.optiqon.voice.data.repository.ProcessingRepository
import se.optiqon.voice.domain.capability.CapabilityEnvironment
import se.optiqon.voice.domain.capability.ProfileCapabilities
import se.optiqon.voice.domain.capability.ProfileCapability
import se.optiqon.voice.domain.model.AppContext
import se.optiqon.voice.domain.model.Profile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TextProcessor @Inject constructor(
    private val apiClientFactory: ApiClientFactory,
    private val preferencesDataStore: PreferencesDataStore,
    private val processingRepository: ProcessingRepository
) {
    /**
     * @param onPostProcessing invoked immediately before the LLM request, so callers can
     *   distinguish the network-bound post-processing phase from transcription.
     */
    suspend fun process(
        rawText: String,
        profile: Profile,
        appContext: AppContext? = null,
        onPostProcessing: (() -> Unit)? = null
    ): String {
        val ruleProcessedText = try {
            processingRepository.applySelectedRules(rawText, profile.selectedRuleIds)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Replacement rules failed", e)
            rawText
        }

        return try {
            val prefs = preferencesDataStore.preferences.first()
            // The same evaluation the profile card prints, rather than a second copy of the same
            // three clauses. They disagreed before: the card said "no cleanup" while the rules above
            // had already run, and said "Cleanup on" for a profile with no key to call.
            val environment = CapabilityEnvironment.from(
                llmBaseUrl = prefs.llmBaseUrl,
                llmApiKey = prefs.llmApiKey
            )
            if (!ProfileCapabilities.state(ProfileCapability.POST_PROCESSING, profile, environment).isAvailable) {
                return ruleProcessedText
            }

            onPostProcessing?.invoke()

            val service = apiClientFactory.create(
                LlmApiService::class.java,
                prefs.llmBaseUrl,
                prefs.llmApiKey
            )

            val systemPrompt = processingRepository.buildSystemPrompt(profile, appContext)

            val request = ChatCompletionRequest(
                model = profile.llmModel,
                messages = listOf(
                    ChatMessage(role = "system", content = systemPrompt),
                    ChatMessage(role = "user", content = ruleProcessedText)
                )
            )

            val response = service.chatCompletion(request)
            response.text.ifBlank { ruleProcessedText }
        } catch (e: CancellationException) {
            // The user cancelled mid-request; never fall through to injecting text.
            throw e
        } catch (e: Exception) {
            // Fall back to the rule-processed text rather than the raw transcript, so a
            // failed LLM call does not also discard the local replacement rules.
            Log.e(TAG, "Post-processing failed; using rule-processed text", e)
            ruleProcessedText
        }
    }

    private companion object {
        const val TAG = "TextProcessor"
    }
}
