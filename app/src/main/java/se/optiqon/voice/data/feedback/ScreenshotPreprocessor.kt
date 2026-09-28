package se.optiqon.voice.data.feedback

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import se.optiqon.voice.data.storage.UserScopedStorage
import se.optiqon.voice.domain.feedback.FeedbackLimits
import se.optiqon.voice.domain.feedback.PreparedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.UUID

sealed interface PrepareResult {
    data class Ready(val image: PreparedImage) : PrepareResult

    /** Not a PNG, JPEG or WebP, or too large to read at all. */
    data object Unsupported : PrepareResult

    /** The bytes do not decode as an image. */
    data object Unreadable : PrepareResult
}

/**
 * Turns a picked image into the copy that is queued: decoded, turned upright, re-encoded and
 * under the size ceiling.
 *
 * Re-encoding is the privacy step. The copy is built from pixels alone, so none of the
 * original's EXIF — location, device, time — survives into it. The original is never touched
 * and never copied as it is.
 */
class ScreenshotPreprocessor(
    private val files: UserScopedStorage,
    private val newId: () -> String = { UUID.randomUUID().toString() }
) {

    fun prepare(uid: String, mime: String?, open: () -> InputStream?): PrepareResult {
        if (mime !in FeedbackLimits.IMAGE_MIME) return PrepareResult.Unsupported
        val original = runCatching { open()?.use { it.readAtMost(MAX_INPUT_BYTES) } }.getOrNull()
            ?: return PrepareResult.Unreadable
        if (original.size > MAX_INPUT_BYTES) return PrepareResult.Unsupported

        val decoded = decode(original) ?: return PrepareResult.Unreadable
        val upright = upright(decoded, orientationOf(original))
        val (bytes, outMime) = encode(upright, preferPng = mime == "image/png")
            ?: return PrepareResult.Unreadable

        val name = newId() + if (outMime == "image/png") ".png" else ".jpg"
        File(files.attachmentsDir(uid), name).writeBytes(bytes)
        return PrepareResult.Ready(PreparedImage(file = name, mime = outMime, bytes = bytes.size))
    }

    /** Removes a prepared copy that the user took back out before sending. */
    fun discard(uid: String, file: String) {
        val dir = files.attachmentsDir(uid)
        val target = File(dir, file)
        if (target.canonicalFile.parentFile == dir.canonicalFile) target.delete()
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE_PX) sample *= 2
        return BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }

    private fun orientationOf(bytes: ByteArray): Int = runCatching {
        ExifInterface(bytes.inputStream())
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun upright(bitmap: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
    }

    /**
     * A screenshot of text stays PNG when it fits; anything else, or a PNG that does not fit,
     * becomes JPEG, stepping quality and then size down until it is under the ceiling.
     */
    private fun encode(bitmap: Bitmap, preferPng: Boolean): Pair<ByteArray, String>? {
        if (preferPng) {
            compress(bitmap, Bitmap.CompressFormat.PNG, 100)
                .takeIf { it.size <= FeedbackLimits.MAX_IMAGE_BYTES }
                ?.let { return it to "image/png" }
        }
        var current = bitmap
        repeat(MAX_SHRINK_STEPS) {
            for (quality in JPEG_QUALITIES) {
                val out = compress(current, Bitmap.CompressFormat.JPEG, quality)
                if (out.size in 1..FeedbackLimits.MAX_IMAGE_BYTES) return out to "image/jpeg"
            }
            current = Bitmap.createScaledBitmap(
                current, maxOf(1, current.width * 3 / 4), maxOf(1, current.height * 3 / 4), true
            )
        }
        return null
    }

    private fun compress(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(format, quality, it) }.toByteArray()

    private fun InputStream.readAtMost(limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (out.size() <= limit) {
            val n = read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    companion object {
        const val MAX_INPUT_BYTES = 25 * 1024 * 1024
        const val MAX_EDGE_PX = 2560
        private const val MAX_SHRINK_STEPS = 6
        private val JPEG_QUALITIES = intArrayOf(85, 70)
    }
}
