package com.danemadsen.curie.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.danemadsen.curie.R
import com.danemadsen.curie.ui.common.Persona
import com.danemadsen.curie.ui.components.AssistantAvatar
import com.danemadsen.curie.ui.components.CurieIconButton
import com.danemadsen.curie.ui.components.LARGE_ICON_SIZE
import com.danemadsen.curie.ui.components.UserAvatar

/** What a message's controls may do right now. */
internal data class MessageControlsState(
    /**
     * False while an answer is generated or a message is edited. Every control is disabled then,
     * stopping the reading aloud included, as in Maid.
     */
    val enabled: Boolean,

    val isSpeaking: Boolean,
)

/**
 * Maid's MessageView, without bubbles: the author's avatar and name with the message's controls
 * on the right, then the content. A continuation (an answer's round after its tools ran) is only
 * content, carrying on the message above it.
 */
@Composable
internal fun MessageView(
    message: MessageUi,
    author: Persona,
    controls: MessageControlsState,
    content: MessageContentState,
    onAction: (ChatAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val topPadding = if (message.isContinuation) 0.dp else 12.dp
    val viewModifier = modifier
        .fillMaxWidth()
        .padding(start = 4.dp, top = topPadding, end = 4.dp, bottom = 12.dp)

    Column(modifier = viewModifier) {
        if (!message.isContinuation) {
            val headerModifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)

            Row(
                modifier = headerModifier,
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MessageAuthor(
                    role = message.role,
                    author = author,
                    modifier = Modifier.weight(1f, fill = false),
                )
                MessageControls(message = message, state = controls, onAction = onAction)
            }
        }

        MessageContent(message = message, author = author, state = content, onAction = onAction)
    }
}

/** Maid's RoleView: a 28 dp avatar and the bold name in the secondary colour. */
@Composable
private fun MessageAuthor(role: MessageRole, author: Persona, modifier: Modifier = Modifier) {
    val rowModifier = modifier.semantics(mergeDescendants = true) { heading() }

    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (role) {
            MessageRole.USER -> UserAvatar(user = author, size = LARGE_ICON_SIZE)
            MessageRole.ASSISTANT -> AssistantAvatar(assistant = author, size = LARGE_ICON_SIZE)
        }
        Text(
            text = author.name,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/**
 * Maid's ControlsView in the secondary colour: speak and regenerate for answers, edit for the
 * user's messages, then the alternatives at this place ("1 / 2") and delete.
 */
@Composable
private fun MessageControls(
    message: MessageUi,
    state: MessageControlsState,
    onAction: (ChatAction) -> Unit,
) {
    val tint = MaterialTheme.colorScheme.secondary
    val messageId = message.id

    Row(verticalAlignment = Alignment.CenterVertically) {
        if (message.role == MessageRole.ASSISTANT) {
            if (state.isSpeaking) {
                CurieIconButton(
                    icon = R.drawable.tabler_player_stop,
                    contentDescription = stringResource(R.string.message_stop_speaking),
                    onClick = { onAction(ChatAction.StopSpeaking) },
                    enabled = state.enabled,
                    tint = tint,
                )
            } else {
                CurieIconButton(
                    icon = R.drawable.tabler_volume,
                    contentDescription = stringResource(R.string.message_speak),
                    onClick = { onAction(ChatAction.Speak(messageId)) },
                    enabled = state.enabled,
                    tint = tint,
                )
            }
            CurieIconButton(
                icon = R.drawable.tabler_refresh,
                contentDescription = stringResource(R.string.message_regenerate),
                onClick = { onAction(ChatAction.Regenerate(messageId)) },
                enabled = state.enabled,
                tint = tint,
            )
        } else {
            CurieIconButton(
                icon = R.drawable.tabler_pencil,
                contentDescription = stringResource(R.string.message_edit),
                onClick = { onAction(ChatAction.StartEditing(messageId)) },
                enabled = state.enabled,
                tint = tint,
            )
        }

        val position = message.siblingIndex + 1
        val count = message.siblingCount
        val counterDescription = stringResource(R.string.message_version, position, count)

        CurieIconButton(
            icon = R.drawable.tabler_caret_left_filled,
            contentDescription = stringResource(R.string.message_previous_version),
            onClick = { onAction(ChatAction.ShowPreviousSibling(messageId)) },
            enabled = state.enabled && message.siblingIndex > 0,
            tint = tint,
        )
        Text(
            text = stringResource(R.string.message_version_counter, position, count),
            style = MaterialTheme.typography.bodyMedium,
            color = tint,
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .semantics { contentDescription = counterDescription },
        )
        CurieIconButton(
            icon = R.drawable.tabler_caret_right_filled,
            contentDescription = stringResource(R.string.message_next_version),
            onClick = { onAction(ChatAction.ShowNextSibling(messageId)) },
            enabled = state.enabled && message.siblingIndex < count - 1,
            tint = tint,
        )
        CurieIconButton(
            icon = R.drawable.tabler_trash,
            contentDescription = stringResource(R.string.message_delete),
            onClick = { onAction(ChatAction.DeleteMessage(messageId)) },
            enabled = state.enabled,
            tint = tint,
        )
    }
}
