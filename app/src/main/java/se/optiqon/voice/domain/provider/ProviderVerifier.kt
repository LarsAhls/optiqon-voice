package se.optiqon.voice.domain.provider

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import se.optiqon.voice.data.api.ApiClientFactory
import se.optiqon.voice.data.api.AsrApiService
import se.optiqon.voice.data.api.LlmApiService
import se.optiqon.voice.data.api.model.ChatCompletionRequest
import se.optiqon.voice.data.api.model.ChatMessage
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The outcome of pointing the app at a transcription endpoint. The three failures are kept
 * apart because they need different words in front of the user: a rejected key is something
 * they can fix on the provider's site, an unreachable host is usually the network, and a
 * malformed URL never left the device.
 */
sealed interface VerificationResult {
    data object Ok : VerificationResult
    /** The server answered, and said no. [status] is its HTTP code. */
    data class Rejected(val status: Int, val detail: String) : VerificationResult
    /** The request never got an answer. */
    data class Unreachable(val detail: String) : VerificationResult
    /** The settings could not be turned into a request at all. */
    data class Invalid(val detail: String) : VerificationResult
}

/**
 * Checks that a base URL, key and model actually transcribe, by sending a tenth of a second
 * of silence. Extracted from the settings screen so first-run onboarding and settings ask the
 * same question in the same way and read the same answers.
 */
@Singleton
class ProviderVerifier @Inject constructor(
    private val apiClientFactory: ApiClientFactory
) {
    suspend fun verifyTranscription(
        baseUrl: String,
        apiKey: String,
        model: String
    ): VerificationResult {
        val service = try {
            apiClientFactory.create(AsrApiService::class.java, baseUrl, apiKey)
        } catch (e: IllegalArgumentException) {
            return VerificationResult.Invalid(e.message ?: "Endpoint is not usable.")
        }

        return try {
            val filePart = MultipartBody.Part.createFormData(
                "file",
                "verify.wav",
                silentWav().toRequestBody("audio/wav".toMediaType())
            )
            service.transcribe(filePart, model.trim().toRequestBody("text/plain".toMediaType()))
            VerificationResult.Ok
        } catch (e: HttpException) {
            VerificationResult.Rejected(e.code(), readErrorBody(e))
        } catch (e: IOException) {
            VerificationResult.Unreachable(e.message ?: "Could not reach the server.")
        } catch (e: IllegalArgumentException) {
            VerificationResult.Invalid(e.message ?: "Endpoint is not usable.")
        }
    }

    /**
     * The same question for the text model. Transcription working says nothing about the
     * cleanup pass: a decommissioned chat model answers 404 while the ASR model on the same
     * host and the same key answers 200, and `TextProcessor` then falls back to the raw
     * transcript without telling anyone (smoke finding F17). One token is asked for, because
     * the point is to make the provider resolve the model name, not to generate anything.
     */
    suspend fun verifyCompletion(
        baseUrl: String,
        apiKey: String,
        model: String
    ): VerificationResult {
        val service = try {
            apiClientFactory.create(LlmApiService::class.java, baseUrl, apiKey)
        } catch (e: IllegalArgumentException) {
            return VerificationResult.Invalid(e.message ?: "Endpoint is not usable.")
        }

        return try {
            service.chatCompletion(
                ChatCompletionRequest(
                    model = model.trim(),
                    messages = listOf(ChatMessage(role = "user", content = "ping")),
                    maxTokens = 1
                )
            )
            VerificationResult.Ok
        } catch (e: HttpException) {
            VerificationResult.Rejected(e.code(), readErrorBody(e))
        } catch (e: IOException) {
            VerificationResult.Unreachable(e.message ?: "Could not reach the server.")
        } catch (e: IllegalArgumentException) {
            VerificationResult.Invalid(e.message ?: "Endpoint is not usable.")
        }
    }

    private fun readErrorBody(e: HttpException): String = try {
        e.response()?.errorBody()?.string()?.take(300).orEmpty().ifBlank { "No details" }
    } catch (_: Exception) {
        "Could not read the error body"
    }

    /**
     * A tenth of a second of 16 kHz mono silence, as a WAV. Real audio is not needed — the
     * point is to make the provider run the whole request, key and model included, rather
     * than only checking that the host resolves.
     */
    internal fun silentWav(): ByteArray {
        val sampleRate = 16_000
        val numSamples = sampleRate / 10
        val dataSize = numSamples * 2 // 16-bit mono
        val buffer = ByteBuffer.allocate(WAV_HEADER_BYTES + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray())
        buffer.putInt(WAV_HEADER_BYTES - 8 + dataSize)
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)
        buffer.putShort(1) // PCM
        buffer.putShort(1) // mono
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * 2) // byte rate
        buffer.putShort(2) // block align
        buffer.putShort(16) // bits per sample
        buffer.put("data".toByteArray())
        buffer.putInt(dataSize)
        return buffer.array()
    }

    private companion object {
        const val WAV_HEADER_BYTES = 44
    }
}
