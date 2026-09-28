package se.optiqon.voice.ui.settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import se.optiqon.voice.R
import se.optiqon.voice.domain.feedback.CaseEvent
import se.optiqon.voice.domain.feedback.FeedbackLimits
import se.optiqon.voice.domain.feedback.ShotState
import se.optiqon.voice.domain.feedback.showsContent
import se.optiqon.voice.ui.common.EmptyStateCard
import se.optiqon.voice.ui.common.GhostButton
import se.optiqon.voice.ui.common.GroupCard
import se.optiqon.voice.ui.common.HairlineDivider
import se.optiqon.voice.ui.common.InlineStatus
import se.optiqon.voice.ui.common.ListRow
import se.optiqon.voice.ui.common.PrimaryButton
import se.optiqon.voice.ui.common.SecondaryButton
import se.optiqon.voice.ui.common.SectionEyebrow
import se.optiqon.voice.ui.common.StatusPill

/**
 * Where a person reports a problem, sees their reports and Optiqon's replies, and takes a
 * screenshot back down.
 *
 * The copy is honest about where things are. In a build with sending switched off it says the
 * report stays on the device; a report written before sending existed is shown, and goes only
 * when its owner says so. The screen itself never writes to Firestore or Storage: every write is
 * a row on the outbox, sent later under the same account or not at all.
 */
@Composable
internal fun FeedbackScreen(
    onBack: () -> Unit,
    outerPadding: PaddingValues,
    viewModel: FeedbackViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf("") }
    val back = { if (viewModel.handlesBack()) { viewModel.back(); text = "" } else onBack() }
    BackHandler(enabled = state.page != FeedbackPage.List) { back() }

    // The system photo picker: no storage permission, and only what the person picks is shared.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(FeedbackLimits.MAX_IMAGES_PER_MESSAGE)
    ) { uris -> if (uris.isNotEmpty()) viewModel.addImages(uris) }
    val pick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    val submit = { viewModel.submit(text); text = "" }

    DrillInScaffold(
        title = stringResource(R.string.feedback_title),
        onBack = back,
        onAdd = null,
        outerPadding = outerPadding
    ) { padding ->
        when (val page = state.page) {
            FeedbackPage.List -> FeedbackListContent(
                state = state,
                padding = padding,
                onNew = viewModel::newCase,
                onOpen = viewModel::open,
                onSendLegacy = viewModel::sendLegacy,
                onDeleteLegacy = viewModel::discardLegacy
            )
            FeedbackPage.Compose -> FeedbackComposeContent(
                state = state,
                padding = padding,
                text = text,
                onText = { if (it.length <= FeedbackLimits.MAX_TEXT_CHARS) text = it },
                onPick = pick,
                onRemoveImage = viewModel::removeImage,
                onSubmit = submit
            )
            is FeedbackPage.Detail -> FeedbackDetailContent(
                state = state,
                padding = padding,
                reply = text,
                onReply = { if (it.length <= FeedbackLimits.MAX_TEXT_CHARS) text = it },
                onPick = pick,
                onRemoveImage = viewModel::removeImage,
                onSubmit = submit,
                onDeleteShot = viewModel::deleteShot,
                onDiscardQueued = { viewModel.discardQueued(page.caseId) }
            )
        }
    }
}

@Composable
internal fun FeedbackListContent(
    state: FeedbackUiState,
    padding: PaddingValues,
    onNew: () -> Unit,
    onOpen: (String) -> Unit,
    onSendLegacy: (String) -> Unit,
    onDeleteLegacy: (String) -> Unit
) {
    LazyColumn(contentPadding = padding, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item("intro") { Intro(state) }
        state.notice?.let { notice -> item("notice") { Notice(notice) } }
        item("new") {
            PrimaryButton(
                text = stringResource(R.string.feedback_new),
                onClick = onNew,
                modifier = Modifier.fillMaxWidth(),
                enabled = state.signedIn && state.approved
            )
        }
        if (state.signedIn && !state.approved) {
            item("not_approved") { Problem(stringResource(R.string.feedback_not_approved)) }
        }
        if (state.remoteError) {
            item("remote_error") { Problem(stringResource(R.string.feedback_remote_error)) }
        }

        val local = state.localOnly
        if (local.isEmpty() && state.remoteCases.isEmpty()) {
            item("empty") {
                EmptyStateCard(
                    icon = Icons.Default.Edit,
                    title = stringResource(R.string.feedback_empty_title),
                    description = stringResource(R.string.feedback_empty_body)
                )
            }
        } else {
            item("cases_eyebrow") { SectionEyebrow(stringResource(R.string.feedback_cases_eyebrow)) }
            item("cases") {
                GroupCard {
                    val waiting = stringResource(
                        if (state.remoteEnabled) R.string.feedback_pill_waiting else R.string.feedback_pill_on_device
                    )
                    local.forEachIndexed { i, q ->
                        if (i > 0) HairlineDivider()
                        ListRow(
                            title = q.title,
                            onClick = { onOpen(q.caseId) },
                            trailing = {
                                StatusPill(if (q.blocked) stringResource(R.string.feedback_pill_failed) else waiting)
                            }
                        )
                    }
                    state.remoteCases.forEachIndexed { i, c ->
                        if (i > 0 || local.isNotEmpty()) HairlineDivider()
                        ListRow(
                            title = c.title,
                            onClick = { onOpen(c.id) },
                            trailing = {
                                StatusPill(
                                    stringResource(if (c.closed) R.string.feedback_pill_closed else R.string.feedback_pill_open)
                                )
                            }
                        )
                    }
                }
            }
        }

        if (state.legacy.isNotEmpty()) {
            item("legacy_eyebrow") { SectionEyebrow(stringResource(R.string.feedback_legacy_eyebrow)) }
            item("legacy_body") { BodyText(stringResource(R.string.feedback_legacy_body)) }
            items(state.legacy, key = { "legacy_${it.id}" }) { note ->
                GroupCard {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(note.message, style = MaterialTheme.typography.bodyMedium, maxLines = 4)
                        // Stacked: the send button fills its width, and beside it the delete
                        // button was pushed off the card.
                        SecondaryButton(
                            text = stringResource(R.string.feedback_legacy_send),
                            onClick = { onSendLegacy(note.id) },
                            enabled = state.approved
                        )
                        GhostButton(
                            text = stringResource(R.string.feedback_legacy_delete),
                            onClick = { onDeleteLegacy(note.id) },
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun FeedbackComposeContent(
    state: FeedbackUiState,
    padding: PaddingValues,
    text: String,
    onText: (String) -> Unit,
    onPick: () -> Unit,
    onRemoveImage: (String) -> Unit,
    onSubmit: () -> Unit
) {
    LazyColumn(contentPadding = padding, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item("intro") { Intro(state) }
        item("message") {
            OutlinedTextField(
                value = text,
                onValueChange = onText,
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                label = { Text(stringResource(R.string.feedback_message_label)) },
                supportingText = { Text(stringResource(R.string.feedback_message_hint)) }
            )
        }
        item("images") { DraftImages(state, onPick, onRemoveImage) }
        item("submit") { SubmitBlock(state, enabled = text.isNotBlank(), onSubmit = onSubmit) }
    }
}

@Composable
internal fun FeedbackDetailContent(
    state: FeedbackUiState,
    padding: PaddingValues,
    reply: String,
    onReply: (String) -> Unit,
    onPick: () -> Unit,
    onRemoveImage: (String) -> Unit,
    onSubmit: () -> Unit,
    onDeleteShot: (String) -> Unit,
    onDiscardQueued: () -> Unit
) {
    val detail = state.detail ?: return
    LazyColumn(contentPadding = padding, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item("title") { Text(detail.title, style = MaterialTheme.typography.titleLarge) }
        state.notice?.let { notice -> item("notice") { Notice(notice) } }
        when {
            detail.case == null -> item("on_device") { BodyText(stringResource(R.string.feedback_detail_on_device)) }
            detail.loading -> item("loading") { BodyText(stringResource(R.string.feedback_loading)) }
            detail.error -> item("error") { Problem(stringResource(R.string.feedback_detail_error)) }
        }

        detail.case?.let { case ->
            item("body") { BodyText(case.body) }
        }

        if (detail.shots.isNotEmpty()) {
            item("shots_eyebrow") {
                SectionEyebrow(stringResource(R.string.feedback_screenshots, detail.shots.size, FeedbackLimits.MAX_ACTIVE_PER_CASE))
            }
            item("shots") {
                GroupCard {
                    detail.shots.forEachIndexed { i, shot ->
                        if (i > 0) HairlineDivider()
                        val failed = shot.state == ShotState.UPLOAD_FAILED || shot.state == ShotState.REMOVE_FAILED
                        ListRow(
                            title = stringResource(R.string.feedback_delete_screenshot),
                            subtitle = shotStatus(shot.state)?.let { stringResource(it) },
                            subtitleColor = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            // A screenshot on its way out is never drawn, whatever is in memory.
                            leading = { Thumbnail(shot.thumbnail?.takeIf { shot.state.showsContent }) },
                            onClick = { onDeleteShot(shot.aid) },
                            // A case that never left the phone is the owner's own to tidy up,
                            // approved or not; a removal already on its way needs no second tap.
                            enabled = (state.approved || detail.case == null) && shot.state != ShotState.REMOVING,
                            trailing = { Icon(Icons.Default.Close, contentDescription = null) }
                        )
                    }
                }
            }
        }

        if (detail.events.isNotEmpty()) {
            item("timeline_eyebrow") { SectionEyebrow(stringResource(R.string.feedback_timeline_eyebrow)) }
            items(detail.events, key = { "event_${it.id}" }) { event -> EventCard(event) }
        }

        if (detail.case == null && detail.queued != null) {
            item("discard") {
                SecondaryButton(
                    text = stringResource(R.string.feedback_discard_queued),
                    onClick = onDiscardQueued,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        } else if (detail.closed) {
            item("closed") { BodyText(stringResource(R.string.feedback_detail_closed)) }
        } else if (detail.case != null) {
            item("reply") {
                OutlinedTextField(
                    value = reply,
                    onValueChange = onReply,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 112.dp),
                    label = { Text(stringResource(R.string.feedback_reply_label)) }
                )
            }
            item("reply_images") { DraftImages(state, onPick, onRemoveImage) }
            item("reply_submit") { SubmitBlock(state, enabled = reply.isNotBlank(), onSubmit = onSubmit) }
        }
    }
}

@Composable
private fun Intro(state: FeedbackUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BodyText(stringResource(R.string.feedback_body))
        if (!state.remoteEnabled) BodyText(stringResource(R.string.feedback_local_only))
    }
}

/** Nothing for a screenshot that is simply there; a refusal is never worded as waiting. */
private fun shotStatus(state: ShotState): Int? = when (state) {
    ShotState.AVAILABLE -> null
    ShotState.UPLOADING -> R.string.feedback_shot_pending
    ShotState.UPLOAD_FAILED -> R.string.feedback_shot_upload_failed
    ShotState.REMOVING -> R.string.feedback_shot_removing
    ShotState.REMOVE_FAILED -> R.string.feedback_shot_remove_failed
}

/** A confirmation gets the tick; a refusal is said as a problem. */
@Composable
private fun Notice(notice: FeedbackNotice) {
    if (notice.ok) InlineStatus(stringResource(notice.res)) else Problem(stringResource(notice.res))
}

@Composable
private fun Problem(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun BodyText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun DraftImages(state: FeedbackUiState, onPick: () -> Unit, onRemove: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionEyebrow(stringResource(R.string.feedback_screenshots, state.draft.size, FeedbackLimits.MAX_IMAGES_PER_MESSAGE))
        if (state.draft.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                state.draft.forEach { draft ->
                    Box {
                        Thumbnail(draft.thumbnail, size = 88)
                        IconButton(
                            onClick = { onRemove(draft.image.file) },
                            modifier = Modifier.align(Alignment.TopEnd).size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.feedback_remove_screenshot),
                                modifier = Modifier
                                    .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.small)
                                    .padding(2.dp)
                            )
                        }
                    }
                }
            }
        }
        if (state.draft.size < FeedbackLimits.MAX_IMAGES_PER_MESSAGE) {
            SecondaryButton(text = stringResource(R.string.feedback_add_screenshot), onClick = onPick)
        }
        Text(
            stringResource(R.string.feedback_screenshots_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SubmitBlock(state: FeedbackUiState, enabled: Boolean, onSubmit: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // The outcome sits above the button, not below it: below, it landed under the
        // navigation bar on a 1080x2376 screen.
        state.notice?.let { Notice(it) }
        PrimaryButton(
            text = stringResource(if (state.remoteEnabled) R.string.feedback_send else R.string.feedback_save_local),
            onClick = onSubmit,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled && !state.busy && state.approved
        )
    }
}

@Composable
private fun EventCard(event: CaseEvent) {
    GroupCard {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(if (event.fromOwner) R.string.feedback_from_you else R.string.feedback_from_optiqon),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            event.body?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            event.toStatus?.let { BodyText(stringResource(R.string.feedback_status_changed, it)) }
        }
    }
}

/** Marks a drawn screenshot, so a test can tell a picture from its placeholder. */
internal const val THUMBNAIL_TAG = "feedback_thumbnail"

@Composable
private fun Thumbnail(bitmap: ImageBitmap?, size: Int = 48) {
    val shape = MaterialTheme.shapes.medium
    val modifier = Modifier.size(size.dp).clip(shape)
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = modifier.testTag(THUMBNAIL_TAG), contentScale = ContentScale.Crop)
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}
