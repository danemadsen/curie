package com.danemadsen.curie.ui.chat

import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.danemadsen.curie.ui.theme.CurieTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val CHAT_TITLE = "Weekend in Hobart"

private val CHATS = listOf(
    ChatSummary(id = "chat-1", title = "Plant identification"),
    ChatSummary(id = "chat-2", title = CHAT_TITLE),
)

/**
 * Renaming a chat in place from its long-press menu: the new title is handed over once, whether
 * the keyboard's Done or a tap elsewhere ends the edit, trimmed, and not at all when unchanged.
 */
@RunWith(AndroidJUnit4::class)
class ChatRenameTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val actions = mutableListOf<ChatAction>()
    private lateinit var focusManager: FocusManager

    @Test
    fun doneRenamesTheChatOnce() {
        showDrawer()
        startRenaming()

        renameField().performTextReplacement("Trip to Hobart")
        renameField().performImeAction()

        assertActions(ChatAction.RenameChat(chatId = "chat-2", title = "Trip to Hobart"))
    }

    @Test
    fun tappingElsewhereRenamesTheChatOnce() {
        showDrawer()
        startRenaming()

        renameField().performTextReplacement("Trip to Hobart")
        composeRule.runOnIdle { focusManager.clearFocus() }

        assertActions(ChatAction.RenameChat(chatId = "chat-2", title = "Trip to Hobart"))
    }

    @Test
    fun theNewTitleIsTrimmed() {
        showDrawer()
        startRenaming()

        renameField().performTextReplacement("  Trip to Hobart ")
        renameField().performImeAction()

        assertActions(ChatAction.RenameChat(chatId = "chat-2", title = "Trip to Hobart"))
    }

    @Test
    fun anUnchangedTitleIsNotRenamed() {
        showDrawer()
        startRenaming()

        renameField().performImeAction()

        assertActions()
    }

    private fun showDrawer() {
        composeRule.setContent {
            focusManager = LocalFocusManager.current

            CurieTheme {
                ChatDrawerContent(
                    chats = CHATS,
                    activeChatId = "chat-1",
                    onAction = { action -> actions.add(action) },
                )
            }
        }
    }

    /** Long-presses the chat and picks Rename: its title turns into a focused text field. */
    private fun startRenaming() {
        composeRule.onNodeWithText(CHAT_TITLE).performTouchInput { longClick() }
        composeRule.onNodeWithText("Rename").performClick()

        renameField().assertIsFocused()
    }

    private fun renameField(): SemanticsNodeInteraction = composeRule.onNode(hasSetTextAction())

    private fun assertActions(vararg expected: ChatAction) {
        composeRule.runOnIdle { assertEquals(expected.toList(), actions) }
    }
}
