package se.optiqon.voice.data.feedback

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import se.optiqon.voice.data.storage.UserScopedStorage
import se.optiqon.voice.domain.feedback.FeedbackLimits
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random

/**
 * The copy that is queued is built from pixels alone. Whatever the original carried — where it
 * was taken, on what, when — is not in it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotPreprocessorTest {

    @get:Rule val tmp = TemporaryFolder()

    private val files by lazy { UserScopedStorage(tmp.newFolder("files")) }
    private var ids = 0
    private val preprocessor by lazy { ScreenshotPreprocessor(files) { "img-${ids++}" } }

    private fun bitmap(w: Int, h: Int, noise: Boolean = false): Bitmap {
        val pixels = IntArray(w * h) { i ->
            if (noise) Random(i).nextInt() or (0xFF shl 24) else if (i % w < w / 2) 0xFFFF0000.toInt() else 0xFF0000FF.toInt()
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(pixels, 0, w, 0, 0, w, h) }
    }

    private fun encode(b: Bitmap, format: Bitmap.CompressFormat) =
        ByteArrayOutputStream().also { b.compress(format, 95, it) }.toByteArray()

    /** A JPEG that says it is on its side, and where and on what it was taken. */
    private fun jpegWithExif(): ByteArray {
        val file = tmp.newFile("original.jpg")
        file.writeBytes(encode(bitmap(40, 20), Bitmap.CompressFormat.JPEG))
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "59/1,19/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            setAttribute(ExifInterface.TAG_MAKE, "SecretPhoneMaker")
            saveAttributes()
        }
        return file.readBytes().also {
            check(ExifInterface(file.absolutePath).getAttribute(ExifInterface.TAG_MAKE) == "SecretPhoneMaker") {
                "the fixture did not get its EXIF; this test would prove nothing"
            }
        }
    }

    private fun ready(result: PrepareResult) = (result as PrepareResult.Ready).image

    private fun output(name: String) = File(files.attachmentsDir("uid-a"), name)

    @Test
    fun `EXIF is gone and the picture is turned upright`() {
        val image = ready(preprocessor.prepare("uid-a", "image/jpeg") { jpegWithExif().inputStream() })

        val out = output(image.file)
        val exif = ExifInterface(out.absolutePath)
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertNull(exif.getAttribute(ExifInterface.TAG_MAKE))
        assertFalse(String(out.readBytes(), Charsets.ISO_8859_1).contains("SecretPhoneMaker"))

        val decoded = BitmapFactory.decodeFile(out.absolutePath)
        assertEquals("rotated a quarter turn", 20, decoded.width)
        assertEquals(40, decoded.height)
        assertEquals("image/jpeg", image.mime)
        assertEquals(out.length().toInt(), image.bytes)
    }

    @Test
    fun `a screenshot that fits stays PNG`() {
        val png = encode(bitmap(100, 200), Bitmap.CompressFormat.PNG)
        val image = ready(preprocessor.prepare("uid-a", "image/png") { png.inputStream() })
        assertEquals("image/png", image.mime)
        assertTrue(image.file.endsWith(".png"))
    }

    @Test
    fun `an image over the ceiling comes out under it`() {
        val big = encode(bitmap(1400, 1400, noise = true), Bitmap.CompressFormat.PNG)
        check(big.size > FeedbackLimits.MAX_IMAGE_BYTES) { "fixture is only ${big.size} bytes" }

        val image = ready(preprocessor.prepare("uid-a", "image/png") { big.inputStream() })

        assertTrue("${image.bytes} bytes", image.bytes in 1..FeedbackLimits.MAX_IMAGE_BYTES)
        assertEquals("image/jpeg", image.mime)
        assertEquals(image.bytes, output(image.file).length().toInt())
    }

    @Test
    fun `only PNG, JPEG and WebP are taken`() {
        val png = encode(bitmap(10, 10), Bitmap.CompressFormat.PNG)
        assertEquals(PrepareResult.Unsupported, preprocessor.prepare("uid-a", "image/gif") { png.inputStream() })
        assertEquals(PrepareResult.Unsupported, preprocessor.prepare("uid-a", "image/heic") { png.inputStream() })
        assertEquals(PrepareResult.Unsupported, preprocessor.prepare("uid-a", null) { png.inputStream() })
    }

    @Test
    fun `bytes that are not an image, or cannot be opened, are unreadable`() {
        assertEquals(
            PrepareResult.Unreadable,
            preprocessor.prepare("uid-a", "image/png") { ByteArray(500) { 7 }.inputStream() }
        )
        assertEquals(PrepareResult.Unreadable, preprocessor.prepare("uid-a", "image/png") { null })
        assertEquals(0, files.attachmentsDir("uid-a").listFiles()!!.size)
    }

    @Test
    fun `the copy lands in the account's own directory and can be taken back out`() {
        val png = encode(bitmap(10, 10), Bitmap.CompressFormat.PNG)
        val image = ready(preprocessor.prepare("uid-a", "image/png") { png.inputStream() })
        assertTrue(output(image.file).isFile)

        val elsewhere = File(files.attachmentsDir("uid-b"), "keep.png").apply { writeBytes(png) }
        preprocessor.discard("uid-a", "../../uid-b/attachments/keep.png")
        assertTrue("discard never reaches outside the account's directory", elsewhere.exists())

        preprocessor.discard("uid-a", image.file)
        assertFalse(output(image.file).exists())
    }

    @Test
    fun `a sweep removes old orphans only, and only in the account's own directory`() {
        val dir = files.attachmentsDir("uid-a")
        val old = File(dir, "old.png").apply { writeBytes(byteArrayOf(1)); setLastModified(1_000L) }
        val fresh = File(dir, "fresh.png").apply { writeBytes(byteArrayOf(1)); setLastModified(9_000L) }
        val elsewhere = File(files.attachmentsDir("uid-b"), "keep.png").apply { writeBytes(byteArrayOf(1)); setLastModified(1_000L) }

        assertEquals(setOf("old.png", "fresh.png"), preprocessor.present("uid-a").toSet())
        preprocessor.sweep("uid-a", listOf("old.png", "fresh.png", "../../uid-b/attachments/keep.png"), beforeMs = 5_000L)

        assertFalse(old.exists())
        assertTrue("a copy newer than the sweep may belong to a draft being made", fresh.exists())
        assertTrue(elsewhere.exists())
    }
}
