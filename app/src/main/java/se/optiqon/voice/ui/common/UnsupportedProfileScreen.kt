package se.optiqon.voice.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import se.optiqon.voice.domain.device.ProfileVerdict
import se.optiqon.voice.ui.theme.TextPrimary
import se.optiqon.voice.ui.theme.TextSecondary

/**
 * The end of the road for a copy installed where dictation cannot work (F18).
 *
 * Deliberately offers nothing: no retry, no "continue anyway", no sign-in. Every one of those
 * would lead to a screen that looks like it works while the personal profile quietly does the
 * dictating. The only correct action happens outside this profile.
 */
@Composable
fun UnsupportedProfileScreen(
    verdict: ProfileVerdict.Unsupported,
    modifier: Modifier = Modifier.fillMaxSize()
) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
    ) {
        Text(
            text = stringResource(verdict.titleRes),
            style = MaterialTheme.typography.headlineSmall,
            color = TextPrimary
        )
        Text(
            text = stringResource(verdict.bodyRes),
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
    }
}
