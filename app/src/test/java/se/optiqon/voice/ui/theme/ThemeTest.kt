package se.optiqon.voice.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.font.FontFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The app is dark everywhere by construction rather than by convention. These tests fail if a
 * light palette or a dynamic one ever becomes reachable: the theme takes no parameters, so the
 * only way back to a light surface would be to change the scheme itself.
 */
@RunWith(RobolectricTestRunner::class)
class ThemeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun schemeUnderTheme(): ColorScheme {
        val captured = mutableStateOf<ColorScheme?>(null)
        composeRule.setContent {
            OptiqonVoiceTheme {
                captured.value = MaterialTheme.colorScheme
                Text("themed")
            }
        }
        composeRule.waitForIdle()
        return requireNotNull(captured.value)
    }

    @Test
    fun `theme is dark when the system is set to light`() {
        val scheme = schemeUnderTheme()
        assertEquals(Canvas, scheme.background)
        assertEquals(SurfaceCard, scheme.surface)
        assertTrue("background must be dark", scheme.background.luminance() < 0.1f)
        assertTrue("text on background must be light", scheme.onBackground.luminance() > 0.6f)
    }

    @Test
    @Config(qualifiers = "night")
    fun `theme is the same palette when the system is set to dark`() {
        assertEquals(DarkColorScheme.background, schemeUnderTheme().background)
    }

    @Test
    fun `accents survive into the scheme rather than being replaced by a dynamic palette`() {
        val scheme = schemeUnderTheme()
        assertEquals(Pine80, scheme.primary)
        assertEquals(Sand90, scheme.secondary)
        assertEquals(Clay80, scheme.tertiary)
    }

    @Test
    fun `every surface in the ladder is distinct and dark`() {
        val ladder = listOf(Canvas, SurfaceCard, SurfaceRaised)
        assertEquals("surfaces must not collapse into each other", ladder.size, ladder.distinct().size)
        ladder.forEach { assertTrue(it.luminance() < 0.1f) }
    }

    @Test
    fun `hairlines are translucent so they work on all three surfaces`() {
        listOf(Hairline, CardBorder, SelectedHalo, SelectedBorder).forEach {
            assertTrue("expected a translucent token, got $it", it.alpha < 1f)
        }
    }

    @Test
    fun `the styles the screens ask for are all bound to a bundled family`() {
        val styles = with(Typography) {
            listOf(
                displayLarge, displayMedium, displaySmall,
                headlineLarge, headlineMedium, headlineSmall,
                titleLarge, titleMedium, titleSmall,
                bodyLarge, bodyMedium, bodySmall,
                labelLarge, labelMedium, labelSmall
            )
        }
        styles.forEach { style ->
            assertNotEquals("a style fell back to the platform font", null, style.fontFamily)
            assertNotEquals(FontFamily.Default, style.fontFamily)
            assertTrue("a style has no size", style.fontSize.value > 0f)
        }
    }

    @Test
    fun `the eyebrow is tracked out and the stat numeral is editorial`() {
        assertTrue(SectionEyebrowStyle.letterSpacing.value > 0f)
        assertEquals(Typography.headlineSmall.fontFamily, StatNumberStyle.fontFamily)
    }

    @Test
    fun `shapes get rounder as the surface gets larger`() {
        val radii = listOf(Shapes.extraSmall, Shapes.small, Shapes.medium, Shapes.large, Shapes.extraLarge)
        assertEquals("two shape slots resolved to the same radius", radii.size, radii.distinct().size)
    }
}
