@file:OptIn(ExperimentalTextApi::class)

package se.optiqon.voice.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import se.optiqon.voice.R

/**
 * Both faces ship as single variable files and are instanced per weight here, so the app
 * carries one file per family instead of one per weight. Variation axes are honoured from
 * API 26, which is the app's minimum.
 */
private fun weight(value: Int) = FontVariation.Settings(FontVariation.weight(value))

/** Cormorant Garamond, semibold only. Carries display, headline and the large numerals. */
private val editorialFamily = FontFamily(
    Font(R.font.cormorant_garamond_variable, FontWeight.SemiBold, variationSettings = weight(600))
)

/** Public Sans. Everything that has to be read rather than looked at. */
private val utilityFamily = FontFamily(
    Font(R.font.public_sans_variable, FontWeight.Normal, variationSettings = weight(400)),
    Font(R.font.public_sans_variable, FontWeight.Medium, variationSettings = weight(500)),
    Font(R.font.public_sans_variable, FontWeight.SemiBold, variationSettings = weight(600)),
    Font(R.font.public_sans_variable, FontWeight.Bold, variationSettings = weight(700))
)

private fun editorial(size: Int, lineHeight: Int, tracking: Float = 0f) = TextStyle(
    fontFamily = editorialFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = tracking.em
)

private fun utility(size: Int, lineHeight: Int, fontWeight: FontWeight, tracking: Float = 0f) = TextStyle(
    fontFamily = utilityFamily,
    fontWeight = fontWeight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = tracking.em
)

val Typography = Typography(
    displayLarge = editorial(88, 88, -0.02f),
    displayMedium = editorial(52, 56, -0.01f),
    displaySmall = editorial(48, 52, -0.01f),
    headlineLarge = editorial(40, 44, -0.01f),
    headlineMedium = editorial(36, 40),
    headlineSmall = editorial(32, 36),
    titleLarge = editorial(24, 28),
    titleMedium = utility(16, 22, FontWeight.Medium),
    titleSmall = utility(14, 20, FontWeight.SemiBold),
    bodyLarge = utility(16, 24, FontWeight.Normal),
    bodyMedium = utility(15, 22, FontWeight.Normal),
    bodySmall = utility(13, 18, FontWeight.Normal),
    labelLarge = utility(14, 20, FontWeight.SemiBold),
    labelMedium = utility(12, 16, FontWeight.Medium),
    labelSmall = utility(11, 14, FontWeight.SemiBold)
)

/** The small uppercase label that opens a group of settings rows. */
val SectionEyebrowStyle = utility(12, 16, FontWeight.SemiBold, tracking = 0.08f)

/** A row of statistics: the number is editorial, the word under it is not. */
val StatNumberStyle = editorial(32, 36)
