package se.optiqon.voice.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assertIsDisplayed
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-night")
class HarnessSmokeTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `compose renders and can be captured`() {
        composeRule.setContent { Text("harness") }
        composeRule.onNodeWithText("harness").assertIsDisplayed()
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/harness_smoke.png")
    }
}
