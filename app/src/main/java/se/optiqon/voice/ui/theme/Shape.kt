package se.optiqon.voice.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val Shapes = Shapes(
    // Inline chips inside a card.
    extraSmall = RoundedCornerShape(8.dp),
    // Text fields.
    small = RoundedCornerShape(14.dp),
    // Rows that can be picked, such as the onboarding options.
    medium = RoundedCornerShape(18.dp),
    // Cards.
    large = RoundedCornerShape(22.dp),
    // Bottom sheets.
    extraLarge = RoundedCornerShape(28.dp)
)
