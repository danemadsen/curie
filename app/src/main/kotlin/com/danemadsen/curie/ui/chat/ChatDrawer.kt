package com.danemadsen.curie.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.danemadsen.curie.R
import com.danemadsen.curie.ui.components.ConfirmDialog
import com.danemadsen.curie.ui.components.CurieIconButton
import com.danemadsen.curie.ui.components.Popover
import com.danemadsen.curie.ui.components.PopoverItem
import com.danemadsen.curie.ui.components.SMALL_ICON_SIZE
import com.danemadsen.curie.ui.components.SectionDivider

private val DRAWER_HORIZONTAL_PADDING = 18.dp
private val CHAT_MENU_WIDTH = 120.dp

/**
 * Maid's drawer content: the "Chats" title with import, clear-all and new-chat buttons, a divider,
 * the chats (the active one in the primary colour; long press for rename, export and delete) and,
 * at the bottom, the way to the settings, which Maid kept in its header menu.
 */
@Composable
internal fun ChatDrawerContent(
    chats: List<ChatSummary>,
    activeChatId: String?,
    onAction: (ChatAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isClearConfirmShown by rememberSaveable { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxHeight()) {
        DrawerHeader(
            hasChats = chats.isNotEmpty(),
            onImport = { onAction(ChatAction.ImportChats) },
            onClearAll = { isClearConfirmShown = true },
            onNewChat = { onAction(ChatAction.NewChat) },
        )

        SectionDivider()

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            items(items = chats, key = { chat -> chat.id }) { chat ->
                ChatListItem(
                    chat = chat,
                    isActive = chat.id == activeChatId,
                    onAction = onAction,
                )
            }
        }

        SectionDivider()

        SettingsEntry(onClick = { onAction(ChatAction.OpenSettings) })
    }

    if (isClearConfirmShown) {
        ConfirmDialog(
            message = stringResource(R.string.drawer_clear_confirm),
            onConfirm = {
                isClearConfirmShown = false
                onAction(ChatAction.ClearChats)
            },
            onDismiss = { isClearConfirmShown = false },
        )
    }
}

@Composable
private fun DrawerHeader(
    hasChats: Boolean,
    onImport: () -> Unit,
    onClearAll: () -> Unit,
    onNewChat: () -> Unit,
) {
    val statusBarInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top)
    val headerModifier = Modifier
        .fillMaxWidth()
        .windowInsetsPadding(statusBarInsets)
        .padding(start = DRAWER_HORIZONTAL_PADDING, top = 16.dp, end = DRAWER_HORIZONTAL_PADDING)

    Row(modifier = headerModifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.drawer_title),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )

        // Maid's drawer buttons have 8 dp side margins: 4 here and 4 in the button
        val buttonModifier = Modifier.padding(horizontal = 4.dp)
        CurieIconButton(
            icon = R.drawable.tabler_folder_open,
            contentDescription = stringResource(R.string.drawer_import),
            onClick = onImport,
            size = SMALL_ICON_SIZE,
            modifier = buttonModifier,
        )
        CurieIconButton(
            icon = R.drawable.tabler_trash,
            contentDescription = stringResource(R.string.drawer_clear_all),
            onClick = onClearAll,
            enabled = hasChats,
            size = SMALL_ICON_SIZE,
            modifier = buttonModifier,
        )
        CurieIconButton(
            icon = R.drawable.tabler_plus,
            contentDescription = stringResource(R.string.drawer_new_chat),
            onClick = onNewChat,
            size = SMALL_ICON_SIZE,
            modifier = buttonModifier,
        )
    }
}

/**
 * Maid's ChatButton: the chat's title, which a tap opens and a long press offers to rename (in
 * place), export or delete.
 */
@Composable
private fun ChatListItem(
    chat: ChatSummary,
    isActive: Boolean,
    onAction: (ChatAction) -> Unit,
) {
    var isMenuShown by remember { mutableStateOf(false) }
    var isRenaming by rememberSaveable { mutableStateOf(false) }
    var titleHeightPx by remember { mutableIntStateOf(0) }

    val title = chat.title.ifBlank { stringResource(R.string.drawer_untitled_chat) }
    val titleColor = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface

    if (isRenaming) {
        RenameField(
            initialTitle = chat.title,
            onDone = { newTitle ->
                isRenaming = false
                if (newTitle != chat.title) {
                    onAction(ChatAction.RenameChat(chat.id, newTitle))
                }
            },
        )
        return
    }

    val itemModifier = Modifier
        .fillMaxWidth()
        .combinedClickable(
            role = Role.Button,
            onLongClickLabel = stringResource(R.string.drawer_chat_options),
            onLongClick = { isMenuShown = true },
            onClick = { onAction(ChatAction.SelectChat(chat.id)) },
        )
        .padding(horizontal = DRAWER_HORIZONTAL_PADDING, vertical = 12.dp)

    Box(modifier = itemModifier) {
        // The popover anchors to the title (as wide as the row, like React Native's stretched
        // Text) and opens one title height below it, as in Maid
        val titleModifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { size -> titleHeightPx = size.height }

        Box(modifier = titleModifier) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            val titleHeight = with(LocalDensity.current) { titleHeightPx.toDp() }
            Popover(
                expanded = isMenuShown,
                onDismissRequest = { isMenuShown = false },
                width = CHAT_MENU_WIDTH,
                offset = DpOffset(0.dp, titleHeight),
            ) {
                PopoverItem(
                    text = stringResource(R.string.drawer_rename),
                    onClick = {
                        isMenuShown = false
                        isRenaming = true
                    },
                )
                PopoverItem(
                    text = stringResource(R.string.drawer_export),
                    onClick = {
                        isMenuShown = false
                        onAction(ChatAction.ExportChat(chat.id))
                    },
                )
                PopoverItem(
                    text = stringResource(R.string.drawer_delete),
                    onClick = {
                        isMenuShown = false
                        onAction(ChatAction.DeleteChat(chat.id))
                    },
                )
            }
        }
    }
}

/**
 * The title in place of the chat's row, ready to type. Done, tapping elsewhere or closing the
 * drawer keeps the new title; [onDone] is called once.
 */
@Composable
private fun RenameField(initialTitle: String, onDone: (String) -> Unit) {
    val fieldState = rememberTextFieldState(initialText = initialTitle)
    val focusRequester = remember { FocusRequester() }
    val currentOnDone by rememberUpdatedState(onDone)
    var hasBeenFocused by remember { mutableStateOf(false) }
    var isFinished by remember { mutableStateOf(false) }

    fun finish() {
        if (isFinished) {
            return
        }
        isFinished = true

        val newTitle = fieldState.text.toString().trim()
        currentOnDone(newTitle)
    }

    LaunchedEffect(focusRequester) {
        focusRequester.requestFocus()
    }

    DisposableEffect(fieldState) {
        onDispose { finish() }
    }

    val textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.primary)
    val keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
    val onKeyboardAction = KeyboardActionHandler { finish() }

    val fieldModifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = DRAWER_HORIZONTAL_PADDING, vertical = 12.dp)
        .focusRequester(focusRequester)
        .onFocusChanged { focusState ->
            if (focusState.isFocused) {
                hasBeenFocused = true
            } else if (hasBeenFocused) {
                finish()
            }
        }

    BasicTextField(
        state = fieldState,
        modifier = fieldModifier,
        textStyle = textStyle,
        keyboardOptions = keyboardOptions,
        onKeyboardAction = onKeyboardAction,
        lineLimits = TextFieldLineLimits.SingleLine,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
    )
}

/** The way to the settings, pinned below the chats and clear of the navigation bar. */
@Composable
private fun SettingsEntry(onClick: () -> Unit) {
    val navigationBarInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
    val entryModifier = Modifier
        .fillMaxWidth()
        .clickable(role = Role.Button, onClick = onClick)
        .windowInsetsPadding(navigationBarInsets)
        .padding(horizontal = DRAWER_HORIZONTAL_PADDING, vertical = 12.dp)

    Row(modifier = entryModifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(R.drawable.tabler_settings),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(SMALL_ICON_SIZE),
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = stringResource(R.string.drawer_settings),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
