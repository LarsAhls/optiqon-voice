package se.optiqon.voice.ui.profiles

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.optiqon.voice.domain.model.OutputStyle
import se.optiqon.voice.domain.model.PostProcessingPrompt
import se.optiqon.voice.domain.model.Profile
import se.optiqon.voice.domain.model.RewriteMode
import se.optiqon.voice.domain.model.SummarizeMode
import se.optiqon.voice.domain.model.TextReplacementRule
import se.optiqon.voice.domain.model.TranscriptionLanguageOption
import se.optiqon.voice.domain.model.TranscriptionLanguages
import se.optiqon.voice.ui.common.AppScaffold
import se.optiqon.voice.ui.common.AppTopBar
import se.optiqon.voice.ui.common.LogoTile
import se.optiqon.voice.ui.common.PillShape
import se.optiqon.voice.ui.common.StatusPill
import se.optiqon.voice.ui.theme.AppIcons
import se.optiqon.voice.ui.theme.CardBorder
import se.optiqon.voice.ui.theme.Hairline
import se.optiqon.voice.ui.theme.SelectedBorder
import se.optiqon.voice.ui.theme.SelectedHalo

private sealed interface ProfilesMode {
    data object List : ProfilesMode
    data class Edit(val profile: Profile) : ProfilesMode
    data class Rules(val profile: Profile) : ProfilesMode
    data class Prompts(val profile: Profile) : ProfilesMode
    data class Style(val profile: Profile) : ProfilesMode
}

@Composable
fun ProfilesScreen(
    outerPadding: PaddingValues,
    viewModel: ProfilesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var mode by remember { mutableStateOf<ProfilesMode>(ProfilesMode.List) }

    BackHandler(enabled = mode is ProfilesMode.Edit) {
        mode = ProfilesMode.List
    }

    when (val currentMode = mode) {
        ProfilesMode.List -> ProfilesListScreen(
            profiles = uiState.profiles,
            onActivate = viewModel::activate,
            onEdit = { mode = ProfilesMode.Edit(it) },
            onDuplicate = viewModel::duplicate,
            onDelete = viewModel::delete,
            onCreate = { mode = ProfilesMode.Edit(Profile(name = "New profile")) },
            outerPadding = outerPadding
        )
        is ProfilesMode.Edit -> ProfileEditScreen(
            profile = currentMode.profile,
            rules = uiState.rules,
            prompts = uiState.prompts,
            onBack = { mode = ProfilesMode.List },
            onSave = {
                viewModel.save(it)
                mode = ProfilesMode.List
            },
            onRules = { mode = ProfilesMode.Rules(it) },
            onPrompts = { mode = ProfilesMode.Prompts(it) },
            onStyle = { mode = ProfilesMode.Style(it) },
            outerPadding = outerPadding
        )
        is ProfilesMode.Rules -> ProfileRulePickerScreen(
            profile = currentMode.profile,
            rules = uiState.rules,
            onBack = { updated -> mode = ProfilesMode.Edit(updated) },
            outerPadding = outerPadding
        )
        is ProfilesMode.Prompts -> ProfilePromptPickerScreen(
            profile = currentMode.profile,
            prompts = uiState.prompts,
            onBack = { updated -> mode = ProfilesMode.Edit(updated) },
            outerPadding = outerPadding
        )
        is ProfilesMode.Style -> ProfileStyleScreen(
            profile = currentMode.profile,
            onBack = { updated -> mode = ProfilesMode.Edit(updated) },
            outerPadding = outerPadding
        )
    }
}

@Composable
private fun ProfilesListScreen(
    profiles: List<Profile>,
    onActivate: (Long) -> Unit,
    onEdit: (Profile) -> Unit,
    onDuplicate: (Profile) -> Unit,
    onDelete: (Long) -> Unit,
    onCreate: () -> Unit,
    outerPadding: PaddingValues
) {
    AppScaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onCreate,
                modifier = Modifier.padding(bottom = outerPadding.calculateBottomPadding()),
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("New profile") },
                elevation = FloatingActionButtonDefaults.elevation()
            )
        }
    ) { padding ->
        LazyColumn(
            contentPadding = profilesContentPadding(padding, outerPadding),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item("title") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Profiles", style = MaterialTheme.typography.displaySmall)
                    LogoTile(size = 32.dp)
                }
            }
            // What a profile is, before the list of them rather than after it.
            item("intro") {
                Text(
                    text = "The profile in use decides the language, the service and how much " +
                        "the text is cleaned up. Switching takes effect on the next dictation.",
                    modifier = Modifier.padding(bottom = 6.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            items(profiles, key = { it.id }) { profile ->
                ProfileCard(
                    profile = profile,
                    onActivate = { onActivate(profile.id) },
                    onEdit = { onEdit(profile) },
                    onDuplicate = { onDuplicate(profile) },
                    onDelete = { onDelete(profile.id) }
                )
            }
            item("footer") {
                Text(
                    text = "Switch profiles from the bubble too: press and hold it.",
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * The whole card is the selector — a profile you are reading about is a profile you are
 * considering switching to, and the radio on the left says which one is live.
 */
@Composable
private fun ProfileCard(
    profile: Profile,
    onActivate: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        border = if (profile.isActive) BorderStroke(1.dp, SelectedBorder) else BorderStroke(1.dp, CardBorder),
        colors = CardDefaults.cardColors(
            containerColor = if (profile.isActive) SelectedHalo else MaterialTheme.colorScheme.surface
        ),
        onClick = { if (!profile.isActive) onActivate() }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioMark(selected = profile.isActive)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = profile.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (profile.isActive) StatusPill("In use")
                    }
                    Text(
                        text = profileSummary(profile),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButtonSurface(onClick = onEdit, prominent = profile.isActive) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit ${profile.name}")
                }
            }

            if (profile.isActive) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    profileChips(profile).forEach { chip -> ProfileChipLabel(chip) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onDuplicate) { Text("Duplicate") }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onActivate) { Text("Use this") }
                    TextButton(onClick = onDuplicate) { Text("Duplicate") }
                    TextButton(onClick = onDelete) { Text("Delete") }
                }
            }
        }
    }
}

@Composable
private fun RadioMark(selected: Boolean) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
            .border(
                width = if (selected) 0.dp else 1.5.dp,
                color = if (selected) Color.Transparent else Hairline,
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun ProfileChipLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .clip(PillShape)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** Plain language, because "FIX / whisper-large-v3" tells you nothing about what you will get. */
private fun profileSummary(profile: Profile): String {
    if (!profile.llmEnabled) return "Transcribe only, no cleanup"
    val rewrite = when (profile.rewriteMode) {
        RewriteMode.NONE -> "Kept word for word"
        RewriteMode.FIX -> "Removes filler, fixes slips"
        RewriteMode.POLISH -> "Polished and formal"
    }
    val summarize = when (profile.summarizeMode) {
        SummarizeMode.NONE -> null
        SummarizeMode.LIGHT -> "tightened a little"
        SummarizeMode.HARD -> "condensed hard"
    }
    val style = if (profile.outputStyle == OutputStyle.MINIMAL) "lowercase" else null
    return listOfNotNull(rewrite, summarize, style).joinToString(" · ")
}

private fun profileChips(profile: Profile): List<String> {
    val language = profile.language?.uppercase() ?: "Auto"
    val cleanup = if (profile.llmEnabled) "Cleanup on" else "Cleanup off"
    val emoji = if (profile.emojiAllowed) "Emoji ok" else null
    return listOfNotNull(language, cleanup, emoji)
}

@Composable
private fun IconButtonSurface(
    onClick: () -> Unit,
    prominent: Boolean = false,
    content: @Composable () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = if (prominent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        contentColor = if (prominent) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Box(modifier = Modifier.size(width = 48.dp, height = 44.dp), contentAlignment = Alignment.Center) {
            content()
        }
    }
}

@Composable
private fun ProfileEditScreen(
    profile: Profile,
    rules: List<TextReplacementRule>,
    prompts: List<PostProcessingPrompt>,
    onBack: () -> Unit,
    onSave: (Profile) -> Unit,
    onRules: (Profile) -> Unit,
    onPrompts: (Profile) -> Unit,
    onStyle: (Profile) -> Unit,
    outerPadding: PaddingValues
) {
    var name by rememberSaveable(profile.id) { mutableStateOf(profile.name) }
    var asrModel by rememberSaveable(profile.id) { mutableStateOf(profile.asrModel) }
    var language by rememberSaveable(profile.id) { mutableStateOf(profile.language.orEmpty()) }
    var llmEnabled by rememberSaveable(profile.id) { mutableStateOf(profile.llmEnabled) }
    var llmModel by rememberSaveable(profile.id) { mutableStateOf(profile.llmModel) }
    var profilePrompt by rememberSaveable(profile.id) { mutableStateOf(profile.profilePrompt) }
    var outputStyleName by rememberSaveable(profile.id, profile.outputStyle) { mutableStateOf(profile.outputStyle.name) }
    var rewriteModeName by rememberSaveable(profile.id, profile.rewriteMode) { mutableStateOf(profile.rewriteMode.name) }
    var summarizeModeName by rememberSaveable(profile.id, profile.summarizeMode) { mutableStateOf(profile.summarizeMode.name) }
    var emojiAllowed by rememberSaveable(profile.id, profile.emojiAllowed) { mutableStateOf(profile.emojiAllowed) }
    var selectedRuleIds by remember(profile.id, profile.selectedRuleIds) { mutableStateOf(profile.selectedRuleIds) }
    var selectedPromptIds by remember(profile.id, profile.selectedPromptIds) { mutableStateOf(profile.selectedPromptIds) }

    val outputStyle = enumValueOrDefault(outputStyleName, OutputStyle.STANDARD)
    val rewriteMode = enumValueOrDefault(rewriteModeName, RewriteMode.FIX)
    val summarizeMode = enumValueOrDefault(summarizeModeName, SummarizeMode.NONE)
    val customPrompts = prompts.filterNot(PostProcessingPrompt::builtIn)
    val customPromptIds = customPrompts.map(PostProcessingPrompt::id).toSet()
    val selectedCustomPromptCount = selectedPromptIds.count { it in customPromptIds }

    fun draft(): Profile = profile.copy(
        name = name,
        asrModel = asrModel,
        language = language.ifBlank { null },
        llmEnabled = llmEnabled,
        llmModel = llmModel,
        profilePrompt = profilePrompt,
        outputStyle = outputStyle,
        rewriteMode = rewriteMode,
        summarizeMode = summarizeMode,
        emojiAllowed = emojiAllowed,
        selectedRuleIds = selectedRuleIds,
        selectedPromptIds = selectedPromptIds
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            EditorTopBar(
                title = if (profile.id == 0L) "Create profile" else "Edit profile",
                onBack = onBack,
                onSave = { onSave(draft()) }
            )
        }
    ) { padding ->
        LazyColumn(
            contentPadding = profileEditorPadding(padding, outerPadding),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            item {
                SettingsGroup {
                    FieldRow("Profile name", name, onClick = null)
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                        singleLine = true
                    )
                }
            }
            item {
                SectionTitle("Transcription Settings")
                SettingsGroup {
                    FieldRow("Transcription provider", "Global provider")
                    EditableInlineRow("Transcription model", asrModel, onValueChange = { asrModel = it })
                    LanguageQuickPicker(
                        selected = language,
                        options = TranscriptionLanguages.quickOptions,
                        onSelect = { language = it }
                    )
                    EditableInlineRow("Language", language, placeholder = "Auto", onValueChange = {
                        language = it.lowercase().filter(Char::isLetter).take(3)
                    })
                }
            }
            item {
                SectionTitle("Post-Processing Settings")
                SettingsGroup {
                    FieldRow(
                        title = "Text replacement rules",
                        value = "${selectedRuleIds.size} of ${rules.size} enabled",
                        onClick = { onRules(draft()) }
                    )
                    ToggleRow("Enable post-processing", llmEnabled, onCheckedChange = { llmEnabled = it })
                    EditableInlineRow("Post-processing model", llmModel, enabled = llmEnabled, onValueChange = { llmModel = it })
                    FieldRow(
                        title = "Style",
                        value = styleSummary(outputStyle, rewriteMode, summarizeMode, emojiAllowed),
                        onClick = { onStyle(draft()) }
                    )
                    EditableInlineRow(
                        title = "Profile-specific prompt",
                        value = profilePrompt,
                        placeholder = "Not configured",
                        minLines = 2,
                        onValueChange = { profilePrompt = it }
                    )
                    FieldRow(
                        title = "Custom prompts",
                        value = "$selectedCustomPromptCount of ${customPrompts.size} enabled",
                        onClick = { onPrompts(draft()) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileRulePickerScreen(
    profile: Profile,
    rules: List<TextReplacementRule>,
    onBack: (Profile) -> Unit,
    outerPadding: PaddingValues
) {
    var selectedIds by remember(profile.id, profile.selectedRuleIds) { mutableStateOf(profile.selectedRuleIds) }
    BackHandler {
        onBack(profile.copy(selectedRuleIds = selectedIds))
    }
    PickerScreen(
        title = "Text replacement rules",
        onBack = { onBack(profile.copy(selectedRuleIds = selectedIds)) },
        floatingAction = null,
        outerPadding = outerPadding
    ) {
        items(rules, key = { it.id }) { rule ->
            RulePickerRow(
                rule = rule,
                selected = rule.id in selectedIds,
                onToggle = { selectedIds = selectedIds.toggle(rule.id) }
            )
        }
    }
}

@Composable
private fun ProfilePromptPickerScreen(
    profile: Profile,
    prompts: List<PostProcessingPrompt>,
    onBack: (Profile) -> Unit,
    outerPadding: PaddingValues
) {
    val customPrompts = prompts.filterNot(PostProcessingPrompt::builtIn)
    val customPromptIds = customPrompts.map(PostProcessingPrompt::id).toSet()
    var selectedIds by remember(profile.id, profile.selectedPromptIds, customPromptIds) {
        mutableStateOf(profile.selectedPromptIds.filterTo(mutableSetOf<Long>()) { it in customPromptIds }.toSet())
    }
    BackHandler {
        onBack(profile.copy(selectedPromptIds = selectedIds))
    }
    PickerScreen(
        title = "Custom prompts",
        onBack = { onBack(profile.copy(selectedPromptIds = selectedIds)) },
        floatingAction = null,
        outerPadding = outerPadding
    ) {
        items(customPrompts, key = { it.id }) { prompt ->
            ToggleTextRow(
                title = prompt.prompt.ifBlank { prompt.title },
                selected = prompt.id in selectedIds,
                onToggle = { selectedIds = selectedIds.toggle(prompt.id) }
            )
        }
    }
}

@Composable
private fun ProfileStyleScreen(
    profile: Profile,
    onBack: (Profile) -> Unit,
    outerPadding: PaddingValues
) {
    var outputStyle by rememberSaveable(profile.id) { mutableStateOf(profile.outputStyle.name) }
    var rewriteMode by rememberSaveable(profile.id) { mutableStateOf(profile.rewriteMode.name) }
    var summarizeMode by rememberSaveable(profile.id) { mutableStateOf(profile.summarizeMode.name) }
    var emojiAllowed by rememberSaveable(profile.id) { mutableStateOf(profile.emojiAllowed) }

    fun draft(): Profile = profile.copy(
        outputStyle = enumValueOrDefault(outputStyle, OutputStyle.STANDARD),
        rewriteMode = enumValueOrDefault(rewriteMode, RewriteMode.FIX),
        summarizeMode = enumValueOrDefault(summarizeMode, SummarizeMode.NONE),
        emojiAllowed = emojiAllowed
    )

    BackHandler { onBack(draft()) }
    PickerScreen(
        title = "Style",
        onBack = { onBack(draft()) },
        floatingAction = null,
        outerPadding = outerPadding
    ) {
        item {
            StyleChoiceGroup(
                title = "Punctuation & casing",
                options = listOf(
                    StyleOption(OutputStyle.STANDARD.name, "Standard", "Caps and punctuation."),
                    StyleOption(OutputStyle.RELAXED.name, "Relaxed", "Caps with lighter punctuation."),
                    StyleOption(OutputStyle.MINIMAL.name, "Minimal", "Lowercase, almost no punctuation.")
                ),
                selected = outputStyle,
                onSelect = { outputStyle = it }
            )
        }
        item {
            StyleChoiceGroup(
                title = "Rewrite",
                options = listOf(
                    StyleOption(RewriteMode.FIX.name, "Fix", "Keep wording close; repair rough edges."),
                    StyleOption(RewriteMode.NONE.name, "None", "Only apply prompts and rules."),
                    StyleOption(RewriteMode.POLISH.name, "Polish", "Rewrite into a more formal tone.")
                ),
                selected = rewriteMode,
                onSelect = { rewriteMode = it }
            )
        }
        item {
            StyleChoiceGroup(
                title = "Condense",
                options = listOf(
                    StyleOption(SummarizeMode.NONE.name, "None", "Do not summarize."),
                    StyleOption(SummarizeMode.LIGHT.name, "Light", "Tighten spoken rambling."),
                    StyleOption(SummarizeMode.HARD.name, "Hard", "Short concise summary.")
                ),
                selected = summarizeMode,
                onSelect = { summarizeMode = it }
            )
        }
        item {
            SettingsGroup {
                ToggleRow("Emoji", emojiAllowed, onCheckedChange = { emojiAllowed = it })
            }
        }
    }
}

@Composable
private fun PickerScreen(
    title: String,
    onBack: () -> Unit,
    floatingAction: (@Composable () -> Unit)?,
    outerPadding: PaddingValues,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(title = title, onBack = onBack)
        },
        floatingActionButton = { floatingAction?.invoke() }
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = padding.calculateTopPadding() + 16.dp,
                bottom = padding.calculateBottomPadding() + outerPadding.calculateBottomPadding() + 32.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}

@Composable
private fun RulePickerRow(
    rule: TextReplacementRule,
    selected: Boolean,
    onToggle: () -> Unit
) {
    Surface(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusPill(if (rule.isRegex) ".*" else "Aa")
            Text(rule.pattern, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("→", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(rule.replacement.ifBlank { "(Empty)" }, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Switch(checked = selected, onCheckedChange = { onToggle() })
        }
    }
}

@Composable
private fun ToggleTextRow(title: String, selected: Boolean, onToggle: () -> Unit) {
    Surface(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Switch(checked = selected, onCheckedChange = { onToggle() })
        }
    }
}

@Composable
private fun LanguageQuickPicker(
    selected: String,
    options: List<TranscriptionLanguageOption>,
    onSelect: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { option ->
            val fieldValue = option.code.orEmpty()
            val isSelected = fieldValue == selected
            Surface(
                onClick = { onSelect(fieldValue) },
                shape = MaterialTheme.shapes.small,
                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                border = BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
            ) {
                Text(
                    option.label,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

private data class StyleOption(
    val value: String,
    val title: String,
    val subtitle: String
)

@Composable
private fun StyleChoiceGroup(
    title: String,
    options: List<StyleOption>,
    selected: String,
    onSelect: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle(title)
        SettingsGroup {
            options.forEach { option ->
                StyleChoiceRow(
                    option = option,
                    selected = option.value == selected,
                    onSelect = { onSelect(option.value) }
                )
            }
        }
    }
}

@Composable
private fun StyleChoiceRow(
    option: StyleOption,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Surface(
        onClick = onSelect,
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(option.title, style = MaterialTheme.typography.titleMedium)
                Text(option.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Surface(
                shape = MaterialTheme.shapes.small,
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
            ) {
                Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                    if (selected) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.background.copy(alpha = 0.35f))
}

@Composable
private fun EditorTopBar(title: String, onBack: () -> Unit, onSave: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
        IconButton(onClick = onSave) {
            Icon(Icons.Default.Check, contentDescription = "Save")
        }
    }
}

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(content = content)
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 10.dp)
    )
}

@Composable
private fun FieldRow(title: String, value: String, onClick: (() -> Unit)? = null) {
    Surface(
        onClick = onClick ?: {},
        enabled = onClick != null,
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(value.ifBlank { "Not configured" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onClick != null) Text("›", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.background.copy(alpha = 0.35f))
}

@Composable
private fun EditableInlineRow(
    title: String,
    value: String,
    placeholder: String = "",
    enabled: Boolean = true,
    minLines: Int = 1,
    onValueChange: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(placeholder) },
            enabled = enabled,
            minLines = minLines
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.background.copy(alpha = 0.35f))
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.background.copy(alpha = 0.35f))
}

private fun Set<Long>.toggle(id: Long): Set<Long> = if (id in this) this - id else this + id

private fun styleSummary(
    outputStyle: OutputStyle,
    rewriteMode: RewriteMode,
    summarizeMode: SummarizeMode,
    emojiAllowed: Boolean
): String {
    val emoji = if (emojiAllowed) "Emoji" else "No emoji"
    return "${outputStyle.label()} · ${rewriteMode.label()} · ${summarizeMode.label()} · $emoji"
}

private fun OutputStyle.label(): String = when (this) {
    OutputStyle.STANDARD -> "Standard"
    OutputStyle.RELAXED -> "Relaxed"
    OutputStyle.MINIMAL -> "Minimal"
}

private fun RewriteMode.label(): String = when (this) {
    RewriteMode.NONE -> "No rewrite"
    RewriteMode.FIX -> "Fix"
    RewriteMode.POLISH -> "Polish"
}

private fun SummarizeMode.label(): String = when (this) {
    SummarizeMode.NONE -> "No summary"
    SummarizeMode.LIGHT -> "Light summary"
    SummarizeMode.HARD -> "Hard summary"
}

private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, default: T): T {
    return enumValues<T>().firstOrNull { it.name == value } ?: default
}

private fun profilesContentPadding(padding: PaddingValues, outerPadding: PaddingValues): PaddingValues {
    return PaddingValues(
        start = 20.dp,
        end = 20.dp,
        top = padding.calculateTopPadding() + 16.dp,
        bottom = padding.calculateBottomPadding() + outerPadding.calculateBottomPadding() + 96.dp
    )
}

private fun profileEditorPadding(padding: PaddingValues, outerPadding: PaddingValues): PaddingValues {
    return PaddingValues(
        start = 20.dp,
        end = 20.dp,
        top = padding.calculateTopPadding() + 16.dp,
        bottom = padding.calculateBottomPadding() + outerPadding.calculateBottomPadding() + 32.dp
    )
}
