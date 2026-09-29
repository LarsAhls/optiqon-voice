package se.optiqon.voice.data.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.optiqon.voice.BuildConfig
import se.optiqon.voice.testing.MODULE_DIR
import java.io.File

/**
 * What the app may and may not do with Cloud Storage, read from the source as text.
 *
 * - `google-services.json` names a default bucket that does not exist. Every Storage instance
 *   is therefore asked for by name, in one place, with the Feedback bucket.
 * - A download URL is a bearer link that keeps working after a screenshot is taken down, so the
 *   app never asks for one; reads go through the rules each time.
 * - Taking a screenshot down is a Firestore tombstone. The client never deletes or rewrites an
 *   object; the rules refuse it anyway, and this keeps the code from pretending otherwise.
 *
 * `src/main/java` is a declared input of the test task (`mainSourcesAsData`), so an edit to any
 * source re-runs this guard instead of serving it from the cache.
 */
class FeedbackStorageContractTest {

    private val sources: Map<String, String> by lazy {
        val root = File(MODULE_DIR, "src/main/java")
        root.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") }
            .associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() }
            .also { check(it.isNotEmpty()) { "No main sources under $MODULE_DIR; this guard has gone stale" } }
    }

    private fun offenders(pattern: Regex) = sources.filter { pattern.containsMatchIn(it.value) }.keys

    @Test
    fun `the bucket is the Feedback bucket, named in full`() {
        assertEquals("gs://optiqon-voice-47498-eun2", BuildConfig.FEEDBACK_BUCKET_URL)
    }

    @Test
    fun `the remote channel is off unless a build asks for it`() {
        assertEquals(false, BuildConfig.FEEDBACK_REMOTE_ENABLED)
    }

    @Test
    fun `only the storage adapter and its module touch the Storage SDK`() {
        assertEquals(
            setOf(
                "se/optiqon/voice/data/feedback/FirebaseAttachmentStore.kt",
                "se/optiqon/voice/di/FeedbackStorageModule.kt"
            ),
            offenders(Regex("""com\.google\.firebase\.storage"""))
        )
    }

    @Test
    fun `every Storage instance is asked for with an explicit bucket`() {
        val calls = sources.flatMap { (path, text) ->
            STORAGE_GET_INSTANCE.findAll(text).map { path to it.value }.toList()
        }
        assertEquals(listOf("se/optiqon/voice/di/FeedbackStorageModule.kt"), calls.map { it.first })
        calls.forEach { (_, call) ->
            assertTrue("no bucket argument in $call", call.contains(",") && call.contains("bucketUrl"))
        }
        assertTrue(offenders(Regex("""Firebase\s*\.\s*storage\b""")).isEmpty())
    }

    @Test
    fun `no download URL is ever asked for`() {
        assertEquals(emptySet<String>(), offenders(Regex("""(?i)\bgetDownloadUrl\b|\.downloadUrl\b""")))
    }

    @Test
    fun `no object is deleted, rewritten or given metadata beyond its type by the client`() {
        val storageFiles = sources.filterValues { it.contains("com.google.firebase.storage") }
        storageFiles.forEach { (path, text) ->
            listOf(
                Regex("""\.delete\s*\("""),
                Regex("""updateMetadata"""),
                Regex("""setCustomMetadata"""),
                Regex("""putFile|putStream""")
            ).forEach { banned ->
                assertTrue("$path uses ${banned.pattern}", !banned.containsMatchIn(text))
            }
        }
    }

    private companion object {
        /** `FirebaseStorage.getInstance(...)`, arguments included, one level of nesting. */
        val STORAGE_GET_INSTANCE = Regex("""FirebaseStorage\s*\.\s*getInstance\s*\((?:[^()]|\([^()]*\))*\)""")
    }
}
