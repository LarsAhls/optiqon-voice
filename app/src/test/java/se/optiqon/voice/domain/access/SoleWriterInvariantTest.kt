package se.optiqon.voice.domain.access

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * One invariant holds up every reading of a stored [AccountStatus] in this app: a snapshot is
 * written only by [AccessRepository.record], and `record` is called only after a successful,
 * current, non-cached *server* read for the identity in force.
 *
 * That is what lets the rest of the code treat a stored `APPROVED` as "the server said so"
 * rather than "the cache said so" — the distinction that the offline-renewal finding was
 * entirely about. It is also the kind of invariant that decays quietly: a second caller added
 * a year from now would compile, pass every other test, and silently reintroduce a way to
 * approve oneself.
 *
 * So it is asserted against the source tree. A test that fails when a second writer appears is
 * cheaper than the incident, and `internal` alone does not stop a caller in the same module.
 */
class SoleWriterInvariantTest {

    @Test
    fun `exactly one file records an access verdict`() {
        val callers = mainSources()
            .filter { file ->
                RECORD_CALL.findAll(file.readText())
                    .any { it.groupValues[1] !in UNRELATED }
            }
            .map { it.name }
            .distinct()
            .sorted()

        assertEquals(
            "record() is the sole writer of a server verdict; a new caller must be reviewed, " +
                "not merely compiled",
            listOf("RegistrationRepository.kt"),
            callers
        )
    }

    @Test
    fun `record is not part of the public surface`() {
        val source = mainSources().single { it.name == "AccessRepository.kt" }.readText()

        assertTrue(
            "record() must stay internal, so that the one caller is the module's own",
            Regex("""internal\s+suspend\s+fun\s+record\(""").containsMatchIn(source)
        )
    }

    /** Both launch layouts: the module directory and the root of the checkout. */
    private fun mainSources(): List<File> {
        val root = listOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull { it.isDirectory }
            ?: error("Sources not found from ${File(".").absolutePath}")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private companion object {
        /** Any call to a method named `record`, together with what it was called on. */
        val RECORD_CALL = Regex("""(\w+)\.record\(""")

        /**
         * Methods that merely share a good name. Listing them, rather than matching only the
         * receiver we expect, is what makes this test notice a writer introduced under a name
         * nobody thought of in advance.
         */
        val UNRELATED = setOf(
            // The one legitimate write, inside AccessRepository.record itself.
            "accessStateStore",
            "lifetimeStatsDao",
            "recorder"
        )
    }
}
