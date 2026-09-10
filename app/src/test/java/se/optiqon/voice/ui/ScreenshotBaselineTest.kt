package se.optiqon.voice.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.optiqon.voice.testing.BASELINE_DIR
import se.optiqon.voice.testing.MODULE_DIR
import java.io.File

/**
 * Keeps the screenshot tests in the state where they can fail.
 *
 * The defect this closes was not a missing test but a test that could not go red: the build set
 * `roborazzi.test.record` to `true` unconditionally, every capture wrote into `build/`, and no
 * reference picture existed anywhere in the checkout. Six tests that read as visual regression
 * coverage were recording a fresh picture each run and comparing it against nothing. The proof
 * is on the record: the duplicated heading and the clipped confirmation sentence on the
 * feedback screen went through a green suite and were found on a photograph of a phone.
 *
 * Comparison is now the default, so the ordinary regression fails on its own and needs no guard
 * here. What needs guarding is the setup itself — the three ways it could quietly go back to
 * recording — because each of them turns the whole suite green again without anything looking
 * broken.
 */
class ScreenshotBaselineTest {

    @Test
    fun `the build compares by default and records only when it is asked to`() {
        val build = File(MODULE_DIR, "build.gradle.kts").readText()
        assertFalse(
            "build.gradle.kts records screenshots unconditionally. Every capture then " +
                "overwrites its own baseline and no test can fail on a visual regression. " +
                "Recording belongs behind -Proborazzi.record.",
            build.contains("\"roborazzi.test.record\", \"true\"")
        )
        assertTrue(
            "build.gradle.kts must set roborazzi.test.verify, or the comparison never runs " +
                "and a changed screen is silently accepted.",
            build.contains("\"roborazzi.test.verify\"")
        )
    }

    @Test
    fun `no screen is shot into a directory nobody compares`() {
        // build/ is wiped by clean and ignored by git, so a picture written there is compared
        // against nothing no matter what mode the build is in. captureBaseline is the way in.
        val outputDir = "build/outputs/" + "roborazzi"
        for (source in testSources()) {
            val text = source.readText()
            assertFalse(
                "${source.name} writes a screenshot into $outputDir, which git ignores and " +
                    "clean deletes. Shoot it with captureBaseline so it is compared against " +
                    "the committed picture.",
                text.contains(outputDir)
            )
            assertFalse(
                "${source.name} calls captureRoboImage directly, which bypasses the committed " +
                    "baselines. Use captureBaseline.",
                text.contains("captureRoboImage")
            )
        }
    }

    @Test
    fun `every baseline belongs to a screen that is still shot`() {
        // Deliberately a written-down list rather than something derived from the sources: the
        // names reach captureBaseline through helper parameters, so nothing can read them off
        // reliably, and a list that has to be edited is the point. Adding a screen without its
        // picture, or deleting a test and leaving a picture behind that looks like coverage,
        // both fail here and say which it was.
        val onDisk = (BASELINE_DIR.listFiles()?.map { it.name } ?: emptyList()).sorted()
        assertEquals(
            "The committed baselines and the screens that are shot have drifted apart. " +
                "Re-record with -Proborazzi.record if a screen was added, delete the picture " +
                "if its test is gone, and update this list either way.",
            EXPECTED.map { "$it.png" }.sorted(),
            onDisk
        )
    }

    private fun testSources(): List<File> =
        File(MODULE_DIR, "src/test/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.name == "ScreenshotBaselineTest.kt" }
            // The one place that is allowed to call Roborazzi, because it is the place that
            // points every capture at the committed picture.
            .filterNot { it.name == "Baselines.kt" }
            .toList()
            .also { check(it.isNotEmpty()) { "No test sources under $MODULE_DIR; this guard has gone stale" } }

    private companion object {
        val EXPECTED = listOf(
            "account_signed_out_completing_link",
            "account_signed_out_email_only",
            "account_signed_out_google_and_email",
            "account_signed_out_unconfigured",
            "account_waiting_pending",
            "account_waiting_revoked",
            "home",
            "onboarding_1_language",
            "onboarding_2_connect",
            "onboarding_3_permissions",
            "profiles",
            "settings"
        )
    }
}
