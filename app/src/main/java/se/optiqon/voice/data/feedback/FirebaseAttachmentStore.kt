package se.optiqon.voice.data.feedback

import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import se.optiqon.voice.domain.feedback.AttachmentStore
import se.optiqon.voice.domain.feedback.StoreResult
import se.optiqon.voice.domain.sync.SendFailure
import javax.inject.Provider

/**
 * Screenshots in the one Feedback bucket, named explicitly by the caller.
 *
 * [storage] is a provider so that nothing touches Firebase until a screenshot is actually
 * moved: a build without `google-services.json` still starts. The SDK's own retry is kept
 * short; retrying is the outbox's job, and it knows about accounts and ordering.
 */
class FirebaseAttachmentStore(
    private val storage: Provider<FirebaseStorage>
) : AttachmentStore {

    override suspend fun put(path: String, bytes: ByteArray, mime: String): StoreResult = try {
        // Content type only. Any other metadata is refused by the rules, and a download token
        // in particular would be a way round them.
        val metadata = StorageMetadata.Builder().setContentType(mime).build()
        withTimeout(TIMEOUT_MS) { ref(path).putBytes(bytes, metadata).await() }
        StoreResult.Ok
    } catch (timeout: TimeoutCancellationException) {
        StoreResult.Failed(SendFailure.Transient("Timed out."))
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: StorageException) {
        if (failure.errorCode == StorageException.ERROR_NOT_AUTHORIZED) StoreResult.Denied
        else StoreResult.Failed(classify(failure))
    } catch (failure: Exception) {
        StoreResult.Failed(SendFailure.Transient(failure.message ?: "Upload failed."))
    }

    override suspend fun exists(path: String): Boolean? = try {
        withTimeout(TIMEOUT_MS) { ref(path).metadata.await() }
        true
    } catch (timeout: TimeoutCancellationException) {
        null
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: StorageException) {
        // Refused reads a missing object the same as a present one the caller may not see;
        // neither is "it is there and it is mine".
        when (failure.errorCode) {
            StorageException.ERROR_OBJECT_NOT_FOUND,
            StorageException.ERROR_NOT_AUTHORIZED -> false
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    override suspend fun getBytes(path: String, maxBytes: Long): ByteArray? = try {
        withTimeout(TIMEOUT_MS) { ref(path).getBytes(maxBytes).await() }
    } catch (timeout: TimeoutCancellationException) {
        null
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        null
    }

    private fun ref(path: String) = storage.get().apply {
        maxUploadRetryTimeMillis = SDK_RETRY_MS
        maxOperationRetryTimeMillis = SDK_RETRY_MS
    }.reference.child(path)

    private fun classify(failure: StorageException): SendFailure = when (failure.errorCode) {
        StorageException.ERROR_NOT_AUTHENTICATED,
        StorageException.ERROR_OBJECT_NOT_FOUND,
        StorageException.ERROR_BUCKET_NOT_FOUND,
        StorageException.ERROR_PROJECT_NOT_FOUND,
        StorageException.ERROR_INVALID_CHECKSUM -> SendFailure.Permanent(failure.message ?: "Upload refused.")
        else -> SendFailure.Transient(failure.message ?: "Upload failed.")
    }

    private companion object {
        const val TIMEOUT_MS = 60_000L
        const val SDK_RETRY_MS = 20_000L
    }
}
