package se.optiqon.voice.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * The app has one appearance. The bubble floats over other apps at any hour and the screens
 * behind it are a single dark sheet, so there is no light variant to keep in step and no
 * system setting that can put the app into a palette nobody has reviewed. Dynamic colour is
 * refused for the same reason: the pine, sand and clay accents carry meaning.
 */
internal val DarkColorScheme = darkColorScheme(
    primary = Pine80,
    onPrimary = Pine110,
    primaryContainer = Pine100,
    onPrimaryContainer = Pine50,
    secondary = Sand90,
    onSecondary = Sand110,
    secondaryContainer = Sand100,
    onSecondaryContainer = Sand50,
    tertiary = Clay80,
    onTertiary = Clay110,
    tertiaryContainer = Clay100,
    onTertiaryContainer = Clay50,
    background = Canvas,
    onBackground = TextPrimary,
    surface = SurfaceCard,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceRaised,
    onSurfaceVariant = TextSecondary,
    surfaceContainer = SurfaceCard,
    surfaceContainerHigh = SurfaceRaised,
    outline = TextTertiary,
    outlineVariant = CardBorder
)

@Composable
fun OptiqonVoiceTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        shapes = Shapes
    ) {
        // Everything is drawn inside one surface so that `LocalContentColor` is the scheme's
        // `onBackground` everywhere. Without it Material3 hands unwrapped content its default
        // black, which on this canvas is text you cannot read — which is exactly what the
        // account screens were, being the one part of the app drawn outside a Scaffold.
        Surface(
            color = DarkColorScheme.background,
            contentColor = DarkColorScheme.onBackground,
            content = content
        )
    }
}
