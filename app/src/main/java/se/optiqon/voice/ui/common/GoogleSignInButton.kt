package se.optiqon.voice.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Google's own sign-in button, rather than the app's.
 *
 * Every other action in the app is a pine pill, and that is the wrong shape for this one: a
 * federated sign-in button is a promise about *whose* dialog opens next, and the only way to
 * make that promise credibly is to look like the button Google publishes — the four-colour
 * mark on a white surface, dark label, no accent of ours anywhere on it. Making it match the
 * rest of the screen would be the app claiming an identity step it does not own.
 *
 * Kept as its own composable so that nothing in [Controls.kt] tempts a future caller into
 * restyling it along with the rest.
 */
@Composable
fun GoogleSignInButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
        enabled = enabled,
        shape = RoundedCornerShape(28.dp),
        contentPadding = PaddingNone,
        colors = ButtonDefaults.buttonColors(
            containerColor = GoogleButtonSurface,
            contentColor = GoogleButtonText,
            disabledContainerColor = GoogleButtonSurfaceDisabled,
            disabledContentColor = GoogleButtonTextDisabled
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = GoogleMark,
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(20.dp)
            )
            // The label is centred in what is left after the mark, so the mark sits at the
            // leading edge the way Google's own asset has it rather than floating beside a
            // centred pair.
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(text = text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
            // Balances the mark so the label is centred in the button, not in the remainder.
            Box(modifier = Modifier.size(20.dp))
        }
    }
}

private val PaddingNone = androidx.compose.foundation.layout.PaddingValues(0.dp)

/** Google's published button colours. Not theme tokens — they are part of the mark. */
private val GoogleButtonSurface = Color(0xFFFFFFFF)
private val GoogleButtonText = Color(0xFF1F1F1F)
private val GoogleButtonSurfaceDisabled = Color(0x1FFFFFFF)
private val GoogleButtonTextDisabled = Color(0x61FFFFFF)

/**
 * The four-colour "G", as four filled paths on the standard 24x24 Material viewport.
 *
 * Drawn here rather than shipped as a drawable so it cannot be tinted by a caller that
 * assumes every icon in the app takes the content colour; [GoogleSignInButton] passes
 * `Color.Unspecified` for exactly that reason.
 */
val GoogleMark: ImageVector by lazy {
    ImageVector.Builder(
        name = "GoogleMark",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            "M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 " +
                "3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z",
            Color(0xFF4285F4)
        )
        path(
            "M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 " +
                "0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z",
            Color(0xFF34A853)
        )
        path(
            "M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 " +
                "10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z",
            Color(0xFFFBBC05)
        )
        path(
            "M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 " +
                "3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z",
            Color(0xFFEA4335)
        )
    }.build()
}

private fun ImageVector.Builder.path(pathData: String, fill: Color) {
    addPath(
        pathData = PathParser().parsePathString(pathData).toNodes(),
        fill = SolidColor(fill)
    )
}
