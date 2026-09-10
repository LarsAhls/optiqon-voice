package se.optiqon.voice.testing

import java.io.File

// Kept apart from Baselines.kt on purpose. The guards in ScreenshotBaselineTest are plain JUnit
// with no Robolectric around them, and they need these two paths; loading them out of the file
// that also builds a RoborazziOptions would run that construction outside an Android runtime and
// fail the guards with a NoClassDefFoundError instead of an answer.

/**
 * The working directory of a test JVM is the module, but a run started from the root of the
 * checkout sees the path one level up. Resolved rather than assumed, because guessing wrong
 * writes baselines to a directory nobody compares against — which is the whole defect the
 * screenshot setup exists to close.
 */
internal val BASELINE_DIR: File =
    listOf(File("app/src/test/screenshots"), File("src/test/screenshots"))
        .map { it.absoluteFile }
        .firstOrNull { it.parentFile.isDirectory }
        ?: error("No src/test directory below ${File(".").absolutePath}; the baselines moved")

/** The module root, derived from the baselines so the two can never disagree. */
internal val MODULE_DIR: File = BASELINE_DIR.parentFile.parentFile.parentFile
