package com.danemadsen.curie.ui.chat

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.danemadsen.curie.ui.common.ModelStatus
import com.danemadsen.curie.ui.common.Persona
import com.danemadsen.curie.ui.theme.CurieTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

private const val OLD_MESSAGE_SUMMARY = "Old action 1"
private const val QUESTION_SUMMARY = "Newest question"
private const val ANSWER_SUMMARY = "Newest answer"

/**
 * A message identified by the summary of its one tool action. The messages carry no text: the
 * markdown renderer ships Java 21 bytecode, which the Java 17 unit test JVM cannot load, and the
 * scrolling does not depend on what the messages show.
 */
private fun message(id: String, role: MessageRole, summary: String): MessageUi {
    val toolAction = ToolActionUi(id = "$id-action", icon = ToolIcon.OTHER, summary = summary)

    return MessageUi(id = id, role = role, text = "", toolActions = listOf(toolAction))
}

/** Twenty-four messages, oldest first: far more than the screen holds. */
private val HISTORY = List(24) { index ->
    val role = if (index % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT

    message(id = "old-$index", role = role, summary = "Old action $index")
}

private val QUESTION = message(id = "new-question", role = MessageRole.USER, summary = QUESTION_SUMMARY)
private val ANSWER = message(id = "new-answer", role = MessageRole.ASSISTANT, summary = ANSWER_SUMMARY)

/** An answer with nothing to show yet: the neural network animation stands in for it. */
private val PENDING_ANSWER = MessageUi(id = "new-answer", role = MessageRole.ASSISTANT, text = "")

/**
 * The chat follows new messages at the bottom while the newest one is in view, whether they come
 * with the generating flag or in later updates, and leaves a reader of older messages in place.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class MessageListScrollTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var state by mutableStateOf(
        ChatUiState(
            modelStatus = ModelStatus.Ready,
            user = Persona(name = "Dane"),
            assistant = Persona(name = "Curie"),
            activeChatId = "chat",
            messages = HISTORY,
        ),
    )

    @Test
    fun followsAnAnswerAddedAfterTheGenerationStarted() {
        showChat()

        update { chat -> chat.copy(messages = chat.messages + QUESTION, isGenerating = true) }
        update { chat -> chat.copy(messages = chat.messages + ANSWER) }

        composeRule.onNodeWithText(ANSWER_SUMMARY).assertIsDisplayed()
    }

    @Test
    fun followsAnEmptyAnswerAddedAfterTheGenerationStarted() {
        showChat()

        update { chat -> chat.copy(messages = chat.messages + QUESTION, isGenerating = true) }
        update { chat -> chat.copy(messages = chat.messages + PENDING_ANSWER) }

        composeRule.onNodeWithContentDescription("Generating a reply").assertIsDisplayed()
    }

    @Test
    fun followsMessagesAddedAfterTheGeneratingFlag() {
        showChat()

        update { chat -> chat.copy(isGenerating = true) }
        update { chat -> chat.copy(messages = chat.messages + QUESTION + ANSWER) }

        composeRule.onNodeWithText(ANSWER_SUMMARY).assertIsDisplayed()
    }

    @Test
    fun followsMessagesAddedTogetherWithTheGeneratingFlag() {
        showChat()

        update { chat -> chat.copy(messages = chat.messages + QUESTION + ANSWER, isGenerating = true) }

        composeRule.onNodeWithText(ANSWER_SUMMARY).assertIsDisplayed()
    }

    @Test
    fun leavesAReaderOfOlderMessagesInPlace() {
        showChat()
        scrollToOldMessage()

        update { chat -> chat.copy(messages = chat.messages + ANSWER) }

        composeRule.onNodeWithText(OLD_MESSAGE_SUMMARY).assertIsDisplayed()
        composeRule.onNodeWithText(ANSWER_SUMMARY).assertIsNotDisplayed()
    }

    @Test
    fun startingAGenerationScrollsDownFromOlderMessages() {
        showChat()
        scrollToOldMessage()

        update { chat -> chat.copy(messages = chat.messages + QUESTION + ANSWER, isGenerating = true) }

        composeRule.onNodeWithText(ANSWER_SUMMARY).assertIsDisplayed()
    }

    private fun showChat() {
        val prompt = TextFieldState()

        composeRule.setContent {
            CurieTheme {
                ChatScreen(state = state, prompt = prompt, onAction = {})
            }
        }
    }

    /** One emission of the state holder, applied and laid out before the next. */
    private fun update(transform: (ChatUiState) -> ChatUiState) {
        composeRule.runOnIdle { state = transform(state) }
        composeRule.waitForIdle()
    }

    private fun scrollToOldMessage() {
        val oldMessage = hasText(OLD_MESSAGE_SUMMARY)

        // The drawer's chat list scrolls too: pick the list that holds the messages
        val anyOldMessage = hasText("Old action", substring = true)
        val messageList = hasScrollToIndexAction() and hasAnyDescendant(anyOldMessage)

        composeRule.onNode(messageList).performScrollToNode(oldMessage)
        composeRule.onNode(oldMessage).assertIsDisplayed()
    }
}
