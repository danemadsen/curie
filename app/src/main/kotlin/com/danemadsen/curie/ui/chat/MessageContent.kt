package com.danemadsen.curie.ui.chat

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.danemadsen.curie.R
import com.danemadsen.curie.ui.common.Persona
import com.danemadsen.curie.ui.components.CurieIconButton
import com.danemadsen.curie.ui.components.TypingIndicator

private val TOOL_CHIP_SHAPE = RoundedCornerShape(20.dp)

/** How a message's content shows right now. */
internal data class MessageContentState(
    val isEditing: Boolean,

    /** The answer is being generated and none of it is visible yet: the network animation stands in. */
    val isPending: Boolean,

    /** The playback state when it concerns one of this message's clips, else null. */
    val playback: AudioPlayback?,
)

/**
 * Maid's ContentView: the message's media, the assistant's tool actions, the markdown text and
 * the spoken answer, with 8 dp between them. While editing, a text field with confirm and cancel
 * replaces it all.
 */
@Composable
internal fun MessageContent(
    message: MessageUi,
    author: Persona,
    state: MessageContentState,
    onAction: (ChatAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.isEditing) {
        EditMessageField(
            initialText = message.text,
            onConfirm = { text -> onAction(ChatAction.ConfirmEdit(message.id, text)) },
            onCancel = { onAction(ChatAction.CancelEdit) },
            modifier = modifier,
        )
        return
    }

    val hasVisibleContent = message.text.isNotBlank() ||
        message.attachments.isNotEmpty() ||
        message.toolActions.isNotEmpty() ||
        message.spokenReply != null ||
        message.error != null

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (attachment in message.attachments) {
            when (attachment) {
                is AttachmentUi.Audio -> AudioClipChip(
                    clip = attachment.clip,
                    label = stringResource(R.string.message_voice_message),
                    playback = state.playback,
                    onToggle = { onAction(ChatAction.ToggleAudio(attachment.clip.id)) },
                )
                else -> MessageMedia(
                    attachment = attachment,
                    onOpen = { onAction(ChatAction.OpenAttachment(message.id, attachment.id)) },
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        }

        for ((index, toolAction) in message.toolActions.withIndex()) {
            ToolActionChip(
                action = toolAction,
                onOpenLink = { onAction(ChatAction.OpenToolLink(message.id, index)) },
            )
        }

        if (message.text.isNotBlank()) {
            MessageMarkdown(text = message.text)
        }

        val error = message.error
        if (error != null) {
            MessageError(error = error)
        }

        val spokenReply = message.spokenReply
        if (spokenReply != null) {
            AudioClipChip(
                clip = spokenReply,
                label = stringResource(R.string.message_spoken_reply, author.name),
                playback = state.playback,
                onToggle = { onAction(ChatAction.ToggleAudio(spokenReply.id)) },
            )
        }

        if (!hasVisibleContent) {
            if (state.isPending) {
                TypingIndicator()
            } else {
                Text(
                    text = stringResource(R.string.message_no_content),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                    fontStyle = FontStyle.Italic,
                )
            }
        }
    }
}

/** Why the answer failed, in the error colour, and how to try again. */
@Composable
private fun MessageError(error: String) {
    val colors = MaterialTheme.colorScheme
    val errorModifier = Modifier
        .fillMaxWidth()
        .semantics(mergeDescendants = true) {}

    Row(
        modifier = errorModifier,
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.tabler_alert_circle_filled),
            contentDescription = null,
            tint = colors.error,
            modifier = Modifier.size(18.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = error, style = MaterialTheme.typography.bodyMedium, color = colors.error)
            Text(
                text = stringResource(R.string.message_error_retry),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

/**
 * Something the assistant did, as an icon and a short summary on a pill. A link (a web search
 * Curie did not open by itself) ends with an external-link icon and opens when tapped.
 */
@Composable
private fun ToolActionChip(action: ToolActionUi, onOpenLink: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val accent = if (action.succeeded) colors.primary else colors.error
    val openLabel = stringResource(R.string.tool_link_open)
    val shapedModifier = Modifier
        .clip(TOOL_CHIP_SHAPE)
        .background(colors.surfaceVariant)
    val interactiveModifier = if (action.isLink) {
        shapedModifier.clickable(onClickLabel = openLabel, role = Role.Button, onClick = onOpenLink)
    } else {
        shapedModifier
    }
    val chipModifier = interactiveModifier
        .semantics(mergeDescendants = true) {}
        .padding(horizontal = 12.dp, vertical = 6.dp)

    Row(
        modifier = chipModifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            painter = painterResource(toolIconResource(action.icon)),
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = action.summary,
            style = MaterialTheme.typography.bodyMedium,
            color = if (action.succeeded) colors.onSurface else colors.error,
        )
        if (action.isLink) {
            Icon(
                painter = painterResource(R.drawable.tabler_external_link),
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@DrawableRes
private fun toolIconResource(icon: ToolIcon): Int = when (icon) {
    ToolIcon.APP -> R.drawable.tabler_apps
    ToolIcon.ALARM -> R.drawable.tabler_alarm
    ToolIcon.TIMER -> R.drawable.tabler_hourglass
    ToolIcon.CALENDAR -> R.drawable.tabler_calendar_event
    ToolIcon.CALL -> R.drawable.tabler_phone_call
    ToolIcon.MESSAGE -> R.drawable.tabler_message
    ToolIcon.SEARCH -> R.drawable.tabler_search
    ToolIcon.WEB -> R.drawable.tabler_world
    ToolIcon.MAP -> R.drawable.tabler_map_pin
    ToolIcon.MUSIC -> R.drawable.tabler_music
    ToolIcon.FLASHLIGHT -> R.drawable.tabler_bulb
    ToolIcon.CAMERA -> R.drawable.tabler_camera
    ToolIcon.NOTE -> R.drawable.tabler_note
    ToolIcon.SETTINGS -> R.drawable.tabler_adjustments
    ToolIcon.OTHER -> R.drawable.tabler_tool
}

/**
 * Maid's edit mode: the message's text, editable in place (14 sp, no box), with check and close
 * buttons below on the right.
 */
@Composable
private fun EditMessageField(
    initialText: String,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fieldState = rememberTextFieldState(initialText = initialText)
    val focusRequester = remember { FocusRequester() }
    val colors = MaterialTheme.colorScheme

    LaunchedEffect(focusRequester) {
        focusRequester.requestFocus()
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicTextField(
            state = fieldState,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
            lineLimits = TextFieldLineLimits.MultiLine(),
            cursorBrush = SolidColor(colors.primary),
        )

        val controlsModifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)

        Row(
            modifier = controlsModifier,
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CurieIconButton(
                icon = R.drawable.tabler_check,
                contentDescription = stringResource(R.string.message_edit_confirm),
                onClick = { onConfirm(fieldState.text.toString()) },
            )
            CurieIconButton(
                icon = R.drawable.tabler_x,
                contentDescription = stringResource(R.string.message_edit_cancel),
                onClick = onCancel,
            )
        }
    }
}
