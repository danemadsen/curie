package com.danemadsen.curie.ui.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.danemadsen.curie.R
import com.danemadsen.curie.ui.common.ModelStatus
import com.danemadsen.curie.ui.components.CurieIconButton
import com.danemadsen.curie.ui.components.Popover
import com.danemadsen.curie.ui.components.PopoverAlign
import com.danemadsen.curie.ui.components.PopoverItem
import com.danemadsen.curie.ui.components.PopoverPosition
import com.danemadsen.curie.ui.format.formatClipDuration
import kotlin.math.roundToInt

private val INPUT_SHAPE = RoundedCornerShape(30.dp)
private val ATTACH_MENU_WIDTH = 160.dp
private const val MAX_PROMPT_LINES = 6

/** The attach popover's entries, in order. */
private val MEDIA_SOURCE_LABELS = listOf(
    MediaSource.PICK_PHOTO to R.string.composer_pick_photo,
    MediaSource.PICK_VIDEO to R.string.composer_pick_video,
    MediaSource.TAKE_PHOTO to R.string.composer_take_photo,
    MediaSource.RECORD_VIDEO to R.string.composer_record_video,
)

/** Maid's prompt input reserves the height of one 16 sp line plus Android's text field padding. */
private val FIELD_MIN_HEIGHT = 41.dp

/**
 * Maid's PromptInputGroup: "Clear Prompt" above while there is text, the attachments to send, and
 * the input pill: attach, the message field and one button that records a voice message, sends,
 * or stops the answer being generated. While recording, the pill shows the recording instead.
 *
 * Until the model is ready, a line above the pill says what it is doing (the chat header has no model
 * button) and opens the model's status when tapped.
 *
 * [prompt] holds the message being typed; the state holder reads and clears it on [ChatAction.Send].
 */
@Composable
internal fun Composer(
    prompt: TextFieldState,
    state: ComposerUiState,
    modelStatus: ModelStatus,
    isGenerating: Boolean,
    onAction: (ChatAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isModelReady = modelStatus is ModelStatus.Ready

    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (!isModelReady) {
            ModelStatusLine(status = modelStatus, onClick = { onAction(ChatAction.OpenModelStatus) })
        }

        if (prompt.text.isNotEmpty() && state.recording == null) {
            val clearModifier = Modifier
                .clickable(role = Role.Button) { prompt.clearText() }
                .padding(vertical = 8.dp)

            Text(
                text = stringResource(R.string.composer_clear_prompt),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = clearModifier,
            )
        }

        if (state.attachments.isNotEmpty()) {
            AttachmentRow(
                attachments = state.attachments,
                onRemove = { attachmentId -> onAction(ChatAction.RemoveAttachment(attachmentId)) },
            )
        }

        val pillModifier = Modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, INPUT_SHAPE)
            .padding(horizontal = 12.dp, vertical = 4.dp)

        Row(modifier = pillModifier, verticalAlignment = Alignment.CenterVertically) {
            val recording = state.recording
            if (recording != null) {
                RecordingControls(recording = recording, isModelReady = isModelReady, onAction = onAction)
            } else {
                AttachButton(onAddMedia = { source -> onAction(ChatAction.AddMedia(source)) })
                PromptField(prompt = prompt, modifier = Modifier.weight(1f))

                val hasContent = prompt.text.isNotBlank() || state.attachments.isNotEmpty()
                ComposerActionButton(
                    isGenerating = isGenerating,
                    hasContent = hasContent,
                    isModelReady = isModelReady,
                    onAction = onAction,
                )
            }
        }
    }
}

/** What the model is doing while it cannot answer, in the style of Maid's "Clear Prompt" link. */
@Composable
private fun ModelStatusLine(status: ModelStatus, onClick: () -> Unit) {
    val text = modelStatusText(status)
    val clickLabel = stringResource(R.string.chat_model_status)
    val lineModifier = Modifier
        .clickable(role = Role.Button, onClickLabel = clickLabel, onClick = onClick)
        .padding(vertical = 8.dp)

    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = lineModifier,
    )
}

@Composable
private fun modelStatusText(status: ModelStatus): String = when (status) {
    ModelStatus.NotDownloaded -> stringResource(R.string.composer_status_download)
    is ModelStatus.Downloading -> {
        val progress = status.progress
        if (progress == null) {
            stringResource(R.string.composer_status_downloading)
        } else {
            val percent = (progress.coerceIn(0f, 1f) * 100f).roundToInt()
            stringResource(R.string.composer_status_downloading_percent, percent)
        }
    }
    ModelStatus.Loading -> stringResource(R.string.composer_status_loading)
    ModelStatus.Ready -> ""
    is ModelStatus.Failed -> stringResource(R.string.composer_status_error)
}

/** Maid's PromptInputField: 16 sp, no box of its own, the placeholder in the text colour. */
@Composable
private fun PromptField(prompt: TextFieldState, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface)
    val placeholder = stringResource(R.string.composer_placeholder)
    val keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
    val decorator = TextFieldDecorator { innerTextField ->
        Box(contentAlignment = Alignment.CenterStart) {
            if (prompt.text.isEmpty()) {
                Text(text = placeholder, style = textStyle)
            }
            innerTextField()
        }
    }

    val fieldModifier = modifier
        .padding(horizontal = 8.dp)
        .heightIn(min = FIELD_MIN_HEIGHT)
        .padding(horizontal = 4.dp, vertical = 10.dp)

    BasicTextField(
        state = prompt,
        modifier = fieldModifier,
        textStyle = textStyle,
        keyboardOptions = keyboardOptions,
        lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = MAX_PROMPT_LINES),
        cursorBrush = SolidColor(colors.primary),
        decorator = decorator,
    )
}

/** Stop while an answer is generated, send when there is something to send, else record. */
@Composable
private fun ComposerActionButton(
    isGenerating: Boolean,
    hasContent: Boolean,
    isModelReady: Boolean,
    onAction: (ChatAction) -> Unit,
) {
    val colors = MaterialTheme.colorScheme

    when {
        isGenerating -> CurieIconButton(
            icon = R.drawable.tabler_player_stop_filled,
            contentDescription = stringResource(R.string.composer_stop),
            onClick = { onAction(ChatAction.StopGenerating) },
            tint = colors.error,
        )
        hasContent -> CurieIconButton(
            icon = R.drawable.tabler_send,
            contentDescription = stringResource(R.string.composer_send),
            onClick = { onAction(ChatAction.Send) },
            enabled = isModelReady,
            tint = colors.primary,
        )
        else -> CurieIconButton(
            icon = R.drawable.tabler_microphone,
            contentDescription = stringResource(R.string.composer_record),
            onClick = { onAction(ChatAction.StartRecording) },
            enabled = isModelReady,
            tint = colors.primary,
        )
    }
}

/** The paperclip, which opens a small popover of media sources above it. */
@Composable
private fun AttachButton(onAddMedia: (MediaSource) -> Unit) {
    var isMenuShown by remember { mutableStateOf(false) }

    fun choose(source: MediaSource) {
        isMenuShown = false
        onAddMedia(source)
    }

    Box {
        CurieIconButton(
            icon = R.drawable.tabler_paperclip,
            contentDescription = stringResource(R.string.composer_attach),
            onClick = { isMenuShown = true },
            tint = MaterialTheme.colorScheme.primary,
        )
        Popover(
            expanded = isMenuShown,
            onDismissRequest = { isMenuShown = false },
            width = ATTACH_MENU_WIDTH,
            position = PopoverPosition.TOP,
            align = PopoverAlign.START,
        ) {
            for ((source, label) in MEDIA_SOURCE_LABELS) {
                PopoverItem(text = stringResource(label), onClick = { choose(source) })
            }
        }
    }
}

/** While recording: cancel, a pulsing red dot with the time, and send. */
@Composable
private fun RowScope.RecordingControls(
    recording: RecordingUi,
    isModelReady: Boolean,
    onAction: (ChatAction) -> Unit,
) {
    val colors = MaterialTheme.colorScheme

    CurieIconButton(
        icon = R.drawable.tabler_x,
        contentDescription = stringResource(R.string.composer_cancel_recording),
        onClick = { onAction(ChatAction.CancelRecording) },
    )

    val pulse = rememberInfiniteTransition(label = "recording")
    val dotAlpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 700), RepeatMode.Reverse),
        label = "recordingDot",
    )
    val recordingDescription = stringResource(R.string.composer_recording)
    val statusModifier = Modifier
        .weight(1f)
        .heightIn(min = FIELD_MIN_HEIGHT)
        .padding(horizontal = 12.dp)

    Row(modifier = statusModifier, verticalAlignment = Alignment.CenterVertically) {
        val dotModifier = Modifier
            .size(10.dp)
            .alpha(dotAlpha)
            .background(colors.error, CircleShape)

        Box(modifier = dotModifier)
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = formatClipDuration(recording.elapsedMillis),
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurface,
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = recordingDescription,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
    }

    CurieIconButton(
        icon = R.drawable.tabler_send,
        contentDescription = stringResource(R.string.composer_send_recording),
        onClick = { onAction(ChatAction.SendRecording) },
        enabled = isModelReady,
        tint = colors.primary,
    )
}

/** Maid's PromptImagesView: 100 dp thumbnails, each with a round remove button on its corner. */
@Composable
private fun AttachmentRow(attachments: List<AttachmentUi>, onRemove: (String) -> Unit) {
    val rowPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)

    LazyRow(modifier = Modifier.padding(top = 4.dp), contentPadding = rowPadding) {
        items(items = attachments, key = { attachment -> attachment.id }) { attachment ->
            Box(modifier = Modifier.padding(horizontal = 4.dp)) {
                AttachmentThumbnail(attachment = attachment)
                RemoveAttachmentButton(
                    onClick = { onRemove(attachment.id) },
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }
        }
    }
}

/** A 24 dp close icon on a surface-variant circle, overlapping the thumbnail's corner by 6 dp. */
@Composable
private fun RemoveAttachmentButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val buttonModifier = modifier
        .offset(x = 6.dp, y = (-6).dp)
        .size(24.dp)
        .clip(CircleShape)
        .clickable(role = Role.Button, onClick = onClick)
        .background(colors.surfaceVariant)

    Box(modifier = buttonModifier, contentAlignment = Alignment.Center) {
        Icon(
            painter = painterResource(R.drawable.tabler_x),
            contentDescription = stringResource(R.string.composer_remove_attachment),
            tint = colors.onSurface,
            modifier = Modifier.size(24.dp),
        )
    }
}
