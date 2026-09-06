package se.optiqon.voice.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.optiqon.voice.data.db.entity.DictationStats
import se.optiqon.voice.data.db.entity.DictationSummary
import se.optiqon.voice.domain.model.DictationStatus
import se.optiqon.voice.domain.model.Profile
import se.optiqon.voice.service.BubbleService
import se.optiqon.voice.ui.common.GhostButton
import se.optiqon.voice.ui.common.HairlineDivider
import se.optiqon.voice.ui.common.OptiqonMark
import se.optiqon.voice.ui.common.PermissionChecklist
import se.optiqon.voice.ui.common.PermissionStatus
import se.optiqon.voice.ui.common.PillShape
import se.optiqon.voice.ui.common.PrimaryButton
import se.optiqon.voice.ui.common.SectionCard
import se.optiqon.voice.ui.common.SectionEyebrow
import se.optiqon.voice.ui.common.StatusPill
import se.optiqon.voice.ui.common.Wordmark
import se.optiqon.voice.ui.common.rememberAccessibilityPermissionState
import se.optiqon.voice.ui.common.rememberMicrophonePermissionState
import se.optiqon.voice.ui.common.rememberNotificationPermissionState
import se.optiqon.voice.ui.common.rememberOverlayPermissionState
import se.optiqon.voice.ui.history.DayGroup
import se.optiqon.voice.ui.history.HistoryViewModel
import se.optiqon.voice.ui.theme.AppIcons
import se.optiqon.voice.ui.theme.Hairline
import se.optiqon.voice.ui.theme.StatNumberStyle
import se.optiqon.voice.ui.theme.TextTertiary
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun HomeScreen(
    outerPadding: PaddingValues,
    viewModel: HomeViewModel = hiltViewModel(),
    historyViewModel: HistoryViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val todayStats by viewModel.todayStats.collectAsStateWithLifecycle()
    val activeProfile by viewModel.activeProfile.collectAsStateWithLifecycle()
    val providerName by viewModel.providerName.collectAsStateWithLifecycle()
    val dayGroups by historyViewModel.dayGroups.collectAsStateWithLifecycle()
    val retryingIds by historyViewModel.retryingIds.collectAsStateWithLifecycle()
    val serviceRunning by BubbleService.runningState.collectAsStateWithLifecycle()

    val overlayPermission = rememberOverlayPermissionState()
    val accessibilityPermission = rememberAccessibilityPermissionState()
    val microphonePermission = rememberMicrophonePermissionState()
    val notificationPermission = rememberNotificationPermissionState()
    val setupStatuses = listOf(overlayPermission, accessibilityPermission, microphonePermission, notificationPermission)
    val missingPermissions = setupStatuses.filterNot(PermissionStatus::granted)
    val serviceReady = overlayPermission.granted && accessibilityPermission.granted && microphonePermission.granted

    LazyColumn(
        contentPadding = homeContentPadding(outerPadding),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item("header") {
            HomeHeader(profile = activeProfile, providerName = providerName)
        }

        item("hero") {
            BubbleHero(
                serviceRunning = serviceRunning,
                onToggleService = {
                    when {
                        serviceRunning -> BubbleService.stop(context)
                        !microphonePermission.granted -> microphonePermission.onRequest()
                        !overlayPermission.granted -> overlayPermission.onRequest()
                        !accessibilityPermission.granted -> accessibilityPermission.onRequest()
                        else -> BubbleService.start(context)
                    }
                }
            )
        }

        item("stats") { StatsStrip(todayStats) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            (!overlayPermission.granted || !accessibilityPermission.granted)
        ) {
            item("restricted") {
                RestrictedSettingsCard(
                    onOpenAppInfo = {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:${context.packageName}")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    }
                )
            }
        }

        if (missingPermissions.isNotEmpty()) {
            item("setup_header") {
                SectionEyebrow(
                    if (missingPermissions.size == 1) {
                        "1 permission missing"
                    } else {
                        "${missingPermissions.size} permissions missing"
                    }
                )
            }
            // The same checklist the first run shows, so a permission revoked later is
            // picked up where it was left rather than presented as a new kind of problem.
            item("setup_list") { PermissionChecklist(missingPermissions) }
        }

        item("recent_header") { SectionEyebrow("Recent") }

        if (dayGroups.isEmpty()) {
            item("empty") { NothingDictatedYet(serviceReady = serviceReady || serviceRunning) }
        } else {
            dayGroups.forEach { group ->
                item("header_${group.key}") { DayHeader(group) }
                items(group.dictations, key = { it.id }) { dictation ->
                    TranscriptRow(
                        dictation = dictation,
                        isRetrying = dictation.id in retryingIds,
                        onCopy = { copyToClipboard(context, dictation.text) },
                        onRetry = { historyViewModel.retry(dictation.id) },
                        onDelete = { historyViewModel.removeFromHistory(dictation.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeHeader(profile: Profile?, providerName: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Wordmark()
        if (profile != null) {
            ProfileChip(profile = profile, providerName = providerName)
        }
    }
}

@Composable
private fun ProfileChip(profile: Profile, providerName: String) {
    val languageCode = profile.language?.uppercase() ?: "AUTO"
    Row(
        modifier = Modifier
            .clip(PillShape)
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = languageCode.take(2),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        Text(
            text = listOf(profile.name, providerName).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * The whole point of the screen: whether the bubble is listening. The mark breathes so the
 * running state is readable from across the room, without a spinner's suggestion of work.
 */
@Composable
private fun BubbleHero(serviceRunning: Boolean, onToggleService: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (serviceRunning) {
                BreathingRing(delayMillis = 0)
                BreathingRing(delayMillis = 1500)
            }
            OptiqonMark(size = 150.dp)
        }
        Text(
            text = if (serviceRunning) "The bubble is on" else "The bubble is off",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )
        Text(
            text = if (serviceRunning) {
                "Open any app, tap the bubble in a text field, and talk."
            } else {
                "Turn it on and a small bubble floats over your other apps."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (serviceRunning) {
            GhostButton(
                text = "Turn off for now",
                icon = AppIcons.StopCircle,
                onClick = onToggleService
            )
        } else {
            PrimaryButton(text = "Turn on", onClick = onToggleService)
        }
    }
}

@Composable
private fun BreathingRing(delayMillis: Int) {
    val transition = rememberInfiniteTransition(label = "ring")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000, delayMillis = delayMillis),
            repeatMode = RepeatMode.Restart
        ),
        label = "ringProgress"
    )
    Box(
        modifier = Modifier
            .size(150.dp)
            .scale(1f + progress * 0.7f)
            .alpha((1f - progress) * 0.55f)
            .border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape)
    )
}

@Composable
private fun StatsStrip(stats: DictationStats) {
    val empty = stats.count == 0
    Column(modifier = Modifier.fillMaxWidth()) {
        HairlineDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatColumn(stats.count.toString(), "dictations today", empty, Modifier.weight(1f))
            StatDivider()
            StatColumn(formatCompactNumber(stats.wordCount), "words", empty, Modifier.weight(1f))
            StatDivider()
            StatColumn(formatDuration(stats.durationMs), "spoken", empty, Modifier.weight(1f))
        }
        HairlineDivider()
    }
}

@Composable
private fun StatColumn(value: String, label: String, muted: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = value,
            style = StatNumberStyle,
            color = if (muted) TextTertiary else MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StatDivider() {
    Box(modifier = Modifier.width(1.dp).height(36.dp).background(Hairline))
}

@Composable
private fun NothingDictatedYet(serviceReady: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Hairline, MaterialTheme.shapes.large)
            .padding(horizontal = 20.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                AppIcons.Mic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
        Text("Nothing dictated yet", style = MaterialTheme.typography.titleMedium)
        Text(
            text = if (serviceReady) {
                "Try it in a chat. Say \"Hej, testar Optiqon Voice\" and watch it land."
            } else {
                "Finish the permissions above, then try it in a chat."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun DayHeader(group: DayGroup) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = group.date,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "${group.totalWords} words",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * A quiet two-line row, because a list of transcripts is something you scan. The actions
 * are one tap away rather than always on screen — they were what made the old card heavy.
 */
@Composable
private fun TranscriptRow(
    dictation: DictationSummary,
    isRetrying: Boolean,
    onCopy: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val failed = dictation.status == DictationStatus.FAILURE.name

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = if (failed) dictation.errorMessage ?: "Transcription failed" else dictation.text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = transcriptMeta(dictation),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (isRetrying) StatusPill("Retrying")
            if (failed) {
                StatusPill(
                    label = "Failed",
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
        if (expanded) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                GhostButton(
                    text = "Copy",
                    icon = AppIcons.ContentCopy,
                    onClick = onCopy,
                    modifier = Modifier.alpha(if (!failed && dictation.text.isNotBlank()) 1f else 0.4f)
                )
                if (!dictation.audioPath.isNullOrBlank()) {
                    GhostButton(
                        text = if (isRetrying) "Retrying" else "Retry",
                        icon = if (isRetrying) null else Icons.Default.Refresh,
                        onClick = { if (!isRetrying) onRetry() }
                    )
                }
                if (isRetrying) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete dictation",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
    HairlineDivider()

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Delete this dictation?") },
            text = { Text("This removes the transcript and any saved retry audio.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete()
                    confirmDelete = false
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun RestrictedSettingsCard(onOpenAppInfo: () -> Unit) {
    SectionCard(
        title = "Restricted settings on Android 13+",
        subtitle = "Sideloaded apps need one extra step before overlay and accessibility permissions can be enabled."
    ) {
        GhostButton(
            text = "Open App Info",
            icon = Icons.Default.Info,
            accent = true,
            onClick = onOpenAppInfo,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun transcriptMeta(dictation: DictationSummary): String {
    val parts = mutableListOf(formatTime(dictation.timestamp))
    displaySourceApp(dictation.sourceApp, dictation.sourceAppPackage)?.let(parts::add)
    val words = dictation.text.split(Regex("\\s+")).count { it.isNotBlank() }
    if (words > 0) parts += "$words words"
    return parts.joinToString(" · ")
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("dictation", text))
    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
}

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
private val defaultZoneId = ZoneId.systemDefault()

private fun formatTime(timestamp: Long): String = timeFormat.format(Instant.ofEpochMilli(timestamp).atZone(defaultZoneId))

private fun formatCompactNumber(number: Int): String {
    return when {
        number >= 1_000_000 -> "${"%.1f".format(number / 1_000_000.0)}M"
        number >= 10_000 -> "${"%.1f".format(number / 1_000.0)}K"
        else -> number.toString()
    }
}

/** Short enough for a stat column: "0s", "48s", "3m 08s", "1h 12m". */
internal fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "${hours}h ${"%02d".format(minutes)}m"
        minutes > 0 -> "${minutes}m ${"%02d".format(seconds)}s"
        else -> "${seconds}s"
    }
}

private fun displaySourceApp(sourceApp: String?, sourceAppPackage: String?): String? {
    val label = sourceApp?.trim()
        ?.takeIf { it.isNotBlank() && !it.equals("App", ignoreCase = true) }
    if (label != null && !label.contains('.')) return label.take(24)

    val packageName = sourceAppPackage?.trim()?.takeIf { it.isNotBlank() }
        ?: label?.takeIf { it.contains('.') }
        ?: return label?.take(24)

    return when {
        packageName.contains("whatsapp", ignoreCase = true) -> "WhatsApp"
        packageName.contains("signal", ignoreCase = true) -> "Signal"
        packageName.contains("molly", ignoreCase = true) -> "Molly"
        packageName.contains("element", ignoreCase = true) -> "Element"
        packageName.contains("telegram", ignoreCase = true) -> "Telegram"
        packageName.contains("gmail", ignoreCase = true) -> "Gmail"
        packageName.contains("outlook", ignoreCase = true) -> "Outlook"
        packageName.contains("discord", ignoreCase = true) -> "Discord"
        packageName.contains("slack", ignoreCase = true) -> "Slack"
        else -> appLabelFromPackage(packageName).take(24)
    }
}

private fun appLabelFromPackage(packageName: String): String {
    val ignoredSegments = setOf("android", "app", "apps", "client", "com", "debug", "im", "io", "mobile", "net", "org", "release", "x")
    val segment = packageName.split('.')
        .firstOrNull { part ->
            val normalized = part.lowercase()
            normalized.length > 1 && normalized !in ignoredSegments
        }
        ?: packageName.substringAfterLast('.')
    return segment
        .replace('_', ' ')
        .replace('-', ' ')
        .trim()
        .replaceFirstChar { it.uppercase() }
}

private fun homeContentPadding(padding: PaddingValues): PaddingValues {
    return PaddingValues(
        start = 24.dp,
        end = 24.dp,
        top = padding.calculateTopPadding() + 20.dp,
        bottom = padding.calculateBottomPadding() + 96.dp
    )
}
