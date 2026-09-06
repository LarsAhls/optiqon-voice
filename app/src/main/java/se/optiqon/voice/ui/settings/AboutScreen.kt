package se.optiqon.voice.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import se.optiqon.voice.BuildConfig
import se.optiqon.voice.ui.common.GhostButton
import se.optiqon.voice.ui.common.GroupCard
import se.optiqon.voice.ui.common.HairlineDivider
import se.optiqon.voice.ui.common.ListRow
import se.optiqon.voice.ui.common.OptiqonMark
import se.optiqon.voice.ui.common.SectionEyebrow
import se.optiqon.voice.ui.theme.AppIcons

/** A licence text the app ships a copy of, so the notice is readable offline. */
internal data class BundledLicense(val title: String, val assetPath: String)

internal val bundledLicenses = listOf(
    BundledLicense("Cormorant Garamond — SIL Open Font License 1.1", "licenses/OFL-CormorantGaramond.txt"),
    BundledLicense("Public Sans — SIL Open Font License 1.1", "licenses/OFL-PublicSans.txt")
)

private const val SOURCE_URL = "https://github.com/LarsAhls/optiqon-voice"
private const val GPL_URL = "https://www.gnu.org/licenses/gpl-3.0.html"

/**
 * The legal corner. A GPL app has to say so and has to say where the source is, and the
 * fonts it embeds carry their own notice — all of it bundled, because someone reading a
 * licence is often exactly the person with no working network setup.
 */
@Composable
internal fun AboutScreen(onBack: () -> Unit, outerPadding: PaddingValues) {
    val context = LocalContext.current
    var reading by remember { mutableStateOf<BundledLicense?>(null) }

    val open = reading
    if (open != null) {
        BackHandler { reading = null }
        LicenseTextScreen(license = open, onBack = { reading = null }, outerPadding = outerPadding)
        return
    }

    DrillInScaffold(title = "About", onBack = onBack, onAdd = null, outerPadding = outerPadding) { padding ->
        LazyColumn(contentPadding = padding, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item("identity") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OptiqonMark(size = 72.dp)
                    Text("Optiqon Voice", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        text = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item("licence") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionEyebrow("Licence")
                    Text(
                        text = "Optiqon Voice is free software: you can redistribute it and modify " +
                            "it under the terms of the GNU General Public License version 3 or " +
                            "later, as published by the Free Software Foundation. It comes with " +
                            "no warranty of any kind.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    GhostButton(
                        text = "Read GPLv3",
                        icon = AppIcons.OpenInNew,
                        onClick = { context.openInBrowser(GPL_URL) }
                    )
                    GhostButton(
                        text = "Source code",
                        icon = AppIcons.OpenInNew,
                        onClick = { context.openInBrowser(SOURCE_URL) }
                    )
                    Text(
                        text = "Built on sasayaki by pluja, also under GPLv3.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item("fonts") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionEyebrow("Bundled fonts")
                    GroupCard {
                        bundledLicenses.forEachIndexed { index, license ->
                            if (index > 0) HairlineDivider()
                            ListRow(title = license.title, onClick = { reading = license })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LicenseTextScreen(
    license: BundledLicense,
    onBack: () -> Unit,
    outerPadding: PaddingValues
) {
    val context = LocalContext.current
    val text by produceState(initialValue = "", license) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open(license.assetPath).bufferedReader().use { it.readText() }
            }.getOrElse { "The licence text could not be read from this build." }
        }
    }

    DrillInScaffold(title = license.title, onBack = onBack, onAdd = null, outerPadding = outerPadding) { padding ->
        // One paragraph per blank line: a licence pasted as a single Text is a wall, and
        // lazy items keep the long OFL preamble off the main thread's layout pass.
        val paragraphs = remember(text) {
            text.split(Regex("\r?\n\\s*\r?\n")).map { it.trim() }.filter { it.isNotEmpty() }
        }
        LazyColumn(contentPadding = padding, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(paragraphs) { paragraph ->
                Text(
                    text = paragraph,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun android.content.Context.openInBrowser(url: String) {
    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
