package com.danemadsen.curie.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.danemadsen.curie.ui.common.ModelStatus
import com.danemadsen.curie.ui.components.MODAL_SCRIM_COLOR
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** React Navigation's drawer is 360 dp wide... */
private val DRAWER_MAX_WIDTH = 360.dp

/** ...or as wide as the screen minus an app bar's 56 dp, when that is narrower. */
private val DRAWER_SCREEN_MARGIN = 56.dp

private val DRAWER_SHAPE = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp)

/** Maid's drawer is the surface at 0xF0 opacity. */
private const val DRAWER_ALPHA = 0xF0 / 255f

/**
 * The chat: Maid's chat page in its drawer. The header holds the drawer button, the model pill
 * and the call button; below are the current branch's messages (newest at the bottom, following
 * an answer as it streams in) and the composer.
 *
 * [prompt] is the message being typed. Keep it in the state holder (a TextFieldState survives
 * there like any other state) so [ChatAction.Send] can read and clear it.
 */
@Composable
fun ChatScreen(
    state: ChatUiState,
    prompt: TextFieldState,
    onAction: (ChatAction) -> Unit,
    modifier: Modifier = Modifier,
    drawerState: DrawerState = rememberDrawerState(DrawerValue.Closed),
) {
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    // Opening or closing the drawer ends typing: it hides the keyboard and finishes a chat rename
    LaunchedEffect(drawerState) {
        snapshotFlow { drawerState.isOpen }
            .drop(1)
            .collect { focusManager.clearFocus() }
    }

    val onDrawerAction: (ChatAction) -> Unit = { action ->
        onAction(action)
        val leavesDrawer = action is ChatAction.SelectChat ||
            action is ChatAction.NewChat ||
            action is ChatAction.OpenSettings
        if (leavesDrawer) {
            scope.launch { drawerState.close() }
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val drawerWidth = min(maxWidth - DRAWER_SCREEN_MARGIN, DRAWER_MAX_WIDTH)
        val drawerColor = MaterialTheme.colorScheme.surface.copy(alpha = DRAWER_ALPHA)

        ModalNavigationDrawer(
            drawerState = drawerState,
            // Swiping closes the drawer; it opens from the header button, leaving horizontal
            // swipes to the content and the system back gesture
            gesturesEnabled = drawerState.isOpen,
            scrimColor = MODAL_SCRIM_COLOR,
            drawerContent = {
                ModalDrawerSheet(
                    modifier = Modifier.width(drawerWidth),
                    drawerShape = DRAWER_SHAPE,
                    drawerContainerColor = drawerColor,
                    drawerTonalElevation = 0.dp,
                    windowInsets = WindowInsets(0, 0, 0, 0),
                ) {
                    ChatDrawerContent(
                        chats = state.chats,
                        activeChatId = state.activeChatId,
                        onAction = onDrawerAction,
                    )
                }
            },
        ) {
            ChatPage(
                state = state,
                prompt = prompt,
                onOpenDrawer = { scope.launch { drawerState.open() } },
                onAction = onAction,
            )
        }
    }
}

/** Maid's chat page: header, messages and composer on the surface, 8 dp from the edges. */
@Composable
private fun ChatPage(
    state: ChatUiState,
    prompt: TextFieldState,
    onOpenDrawer: () -> Unit,
    onAction: (ChatAction) -> Unit,
) {
    val horizontalInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
    val pageModifier = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.surface)
        .windowInsetsPadding(horizontalInsets)

    Column(modifier = pageModifier) {
        ChatHeader(
            assistantName = state.assistant.name,
            isCallEnabled = state.modelStatus is ModelStatus.Ready,
            onOpenDrawer = onOpenDrawer,
            onStartCall = { onAction(ChatAction.StartCall) },
        )

        val listModifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(horizontal = 8.dp)

        MessageList(state = state, onAction = onAction, modifier = listModifier)

        // Above the navigation bar, or above the keyboard while it is open
        val bottomInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
        val composerModifier = Modifier
            .windowInsetsPadding(bottomInsets)
            .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)

        Composer(
            prompt = prompt,
            state = state.composer,
            modelStatus = state.modelStatus,
            isGenerating = state.isGenerating,
            onAction = onAction,
            modifier = composerModifier,
        )
    }
}

/**
 * The messages, laid out from the bottom so the newest stays in view while it grows (short chats
 * still start at the top, like Maid's). Every chat opens at its newest message, new messages stay
 * in view while the newest one is showing, and starting a generation scrolls back down to the
 * answer.
 */
@Composable
private fun MessageList(
    state: ChatUiState,
    onAction: (ChatAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberSaveable(state.activeChatId, saver = LazyListState.Saver) { LazyListState() }
    val newestMessageId = state.messages.lastOrNull()?.id

    FollowNewestMessage(listState = listState, newestMessageId = newestMessageId)

    LaunchedEffect(listState, state.isGenerating) {
        if (state.isGenerating) {
            listState.animateScrollToItem(0)
        }
    }

    val newestFirst = remember(state.messages) { state.messages.asReversed() }
    val controlsEnabled = !state.isGenerating && state.editingMessageId == null

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(top = 8.dp),
        reverseLayout = true,
        verticalArrangement = Arrangement.Top,
    ) {
        items(
            items = newestFirst,
            key = { message -> message.id },
            contentType = { message -> message.role },
        ) { message ->
            val author = when (message.role) {
                MessageRole.USER -> state.user
                MessageRole.ASSISTANT -> state.assistant
            }
            val controls = MessageControlsState(
                enabled = controlsEnabled,
                isSpeaking = message.id == state.speakingMessageId,
            )
            val isPending = state.isGenerating &&
                message.role == MessageRole.ASSISTANT &&
                message.id == newestMessageId
            val playback = state.audioPlayback?.takeIf { playback -> message.hasClip(playback.clipId) }
            val content = MessageContentState(
                isEditing = message.id == state.editingMessageId,
                isPending = isPending,
                playback = playback,
            )

            MessageView(
                message = message,
                author = author,
                controls = controls,
                content = content,
                onAction = onAction,
            )
        }
    }
}

/**
 * Keeps a message added at the bottom in view when the newest message was showing. Left alone,
 * the list keeps its first visible item in place (by key) and lays a new message out below the
 * bottom edge, which would hide an answer whose message arrives after the generating flag.
 */
@Composable
private fun FollowNewestMessage(listState: LazyListState, newestMessageId: String?) {
    // Side effects run after the composition that adds a message and before the layout that
    // places it, so the list still describes the previous layout here
    SideEffect {
        val bottomItem = listState.layoutInfo.visibleItemsInfo.firstOrNull() ?: return@SideEffect
        val wasShowingNewest = bottomItem.index == 0
        val hasNewestChanged = bottomItem.key != newestMessageId

        if (wasShowingNewest && hasNewestChanged) {
            listState.requestScrollToItem(0)
        }
    }
}

private fun MessageUi.hasClip(clipId: String): Boolean {
    if (spokenReply?.id == clipId) {
        return true
    }

    return attachments.any { attachment -> attachment is AttachmentUi.Audio && attachment.id == clipId }
}
