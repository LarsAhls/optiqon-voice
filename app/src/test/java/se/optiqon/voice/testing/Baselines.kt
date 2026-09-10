package se.optiqon.voice.testing

import androidx.compose.ui.test.SemanticsNodeInteraction
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File

/**
 * Where the committed screenshot baselines live, and the only way to shoot one.
 *
 * They used to be written to `build/outputs/roborazzi/`, which `clean` wipes and git ignores,
 * and the build recorded unconditionally — so the screenshot tests drew a new picture on every
 * run and compared it against nothing. They could not fail on a visual regression, and they did
 * not: two layout defects on the feedback screen passed a green suite and were found on a
 * photograph of a phone.
 *
 * The pictures now sit in the checkout next to the tests, and any run that is not
 * `-Proborazzi.record` compares against them. Roborazzi's defaults are the strict ones — a
 * single changed pixel fails, and a baseline that is missing altogether fails too — so a new
 * screen cannot slip in as a recorded file nobody ever looked at.
 */
fun SemanticsNodeInteraction.captureBaseline(name: String) {
    captureRoboImage(file = File(BASELINE_DIR, "$name.png"))
}

/**
 * The working directory of a test JVM is the module, but a run started from the root of the
 * checkout sees the path one level up. Resolved rather than assumed, because guessing wrong
 * writes baselines to a directory nobody compares against — which is the whole defect this
 * file exists to close.
 */
internal val BASELINE_DIR: File =
    listOf(File("app/src/test/screenshots"), File("src/test/screenshots"))
        .map { it.absoluteFile }
        .firstOrNull { it.parentFile.isDirectory }
        ?: error("No src/test directory below ${File(".").absolutePath}; the baselines moved")

/** The module root, derived from the baselines so the two can never disagree. */
internal val MODULE_DIR: File = BASELINE_DIR.parentFile.parentFile.parentFile
