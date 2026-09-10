package se.optiqon.voice.testing

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.ThresholdValidator
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
    captureRoboImage(file = File(BASELINE_DIR, "$name.png"), roborazziOptions = OPTIONS)
}

/**
 * The one deliberate loosening of Roborazzi's defaults, and the number is measured rather than
 * picked. The baselines are recorded on a developer machine and compared again on the CI
 * runner, and the two rasterise antialiasing slightly differently: the first CI run against
 * Windows-recorded pictures reported between 2 and 36 differing pixels of 376980 on all twelve
 * screens, with nothing visible in the diff images but glyph edges.
 *
 * 0.0002 permits 75 of those 376980 pixels. That is twice the worst platform difference seen
 * and a quarter of the smallest real regression measured against it — a one-dp padding change
 * on the account screen moved 297 pixels at its quietest and 3807 at its loudest, and a
 * two-step colour change on the primary moved 21276. The gap is about an order of magnitude in
 * each direction, which is what makes the number defensible; it is not wide enough to be
 * comfortable, so a regression that moves fewer than 75 pixels passes here and nothing else
 * will catch it.
 *
 * The colour tolerance is left at Roborazzi's default. That is not free either: the comparator
 * allows a per-channel distance of 0.007, so a single step of 1/255 on a theme colour changes
 * no pixel at all as far as this comparison is concerned. Two steps do.
 */
private val OPTIONS = RoborazziOptions(
    compareOptions = RoborazziOptions.CompareOptions(resultValidator = ThresholdValidator(0.0002F))
)

/**
 * Asks for every face the theme can produce, before the screen under test asks for any.
 *
 * Both families ship as one variable file instanced per weight, and the typeface cache under
 * Robolectric is keyed per process on the resource rather than on the resource plus its
 * variation axes. Whichever weight is resolved first therefore wins for every weight after it,
 * so the same screen rasterises differently depending on what else ran in the same JVM first:
 * the two account screens came out identical in a full suite and 2197 and 3722 pixels different
 * when their class ran alone, and adding a class alongside moved the signed-out screens too.
 *
 * Resolving all of them here, in a fixed order, before anything else makes the picture depend
 * on the screen instead of on the running order. Measured at zero size and clipped, so it
 * resolves the faces without contributing a pixel.
 */
@Composable
fun PrimeTypefaces() {
    val type = MaterialTheme.typography
    Box(modifier = Modifier.size(0.dp).clipToBounds()) {
        listOf(
            type.displayLarge, type.displayMedium, type.displaySmall,
            type.headlineLarge, type.headlineMedium, type.headlineSmall,
            type.titleLarge, type.titleMedium, type.titleSmall,
            type.bodyLarge, type.bodyMedium, type.bodySmall,
            type.labelLarge, type.labelMedium, type.labelSmall
        ).forEach { Text(text = "Ag", style = it, softWrap = false) }
    }
}
