package com.danemadsen.curie.ui.chat

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.test.assertAll
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.danemadsen.curie.ui.common.ModelStatus
import com.danemadsen.curie.ui.common.Persona
import com.danemadsen.curie.ui.theme.CurieTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val STOP_SPEAKING = "Stop reading aloud"

private val SPOKEN_ANSWER = MessageUi(id = "answer", role = MessageRole.ASSISTANT, text = "")
private val QUESTION = MessageUi(id = "question", role = MessageRole.USER, text = "")

/**
 * An answer being read aloud, then the user's next question. The messages carry no text: the
 * markdown renderer ships Java 21 bytecode, which the Java 17 unit test JVM cannot load, and the
 * controls do not depend on what the messages show.
 */
private val READING_ALOUD = ChatUiState(
    modelStatus = ModelStatus.Ready,
    user = Persona(name = "Dane"),
    assistant = Persona(name = "Curie"),
    activeChatId = "chat",
    messages = listOf(SPOKEN_ANSWER, QUESTION),
    speakingMessageId = SPOKEN_ANSWER.id,
)

/**
 * Maid's message controls: usable while the chat is idle, and every one of them disabled while an
 * answer is generated or a message is edited, stopping the reading aloud included.
 */
@RunWith(AndroidJUnit4::class)
class MessageControlsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val actions = mutableListOf<ChatAction>()

    @Test
    fun readingAloudCanBeStoppedWhileTheChatIsIdle() {
        showChat(READING_ALOUD)

        val stopButton = composeRule.onNodeWithContentDescription(STOP_SPEAKING)
        stopButton.assertIsEnabled()
        stopButton.performClick()

        composeRule.runOnIdle { assertEquals(listOf(ChatAction.StopSpeaking), actions) }
    }

    @Test
    fun everyControlIsDisabledWhileAnAnswerIsGenerated() {
        val generating = READING_ALOUD.copy(isGenerating = true)

        showChat(generating)

        assertEveryControlDisabled()
    }

    @Test
    fun everyControlIsDisabledWhileAMessageIsEdited() {
        val editing = READING_ALOUD.copy(editingMessageId = QUESTION.id)

        showChat(editing)

        assertEveryControlDisabled()
    }

    private fun showChat(state: ChatUiState) {
        val prompt = TextFieldState()

        composeRule.setContent {
            CurieTheme {
                ChatScreen(state = state, prompt = prompt, onAction = { action -> actions.add(action) })
            }
        }
    }

    private fun assertEveryControlDisabled() {
        composeRule.onNodeWithContentDescription(STOP_SPEAKING).assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Regenerate").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Edit").assertIsNotEnabled()

        // Every message has these: one of each per message
        val sharedControls = listOf("Previous version", "Next version", "Delete message")
        for (description in sharedControls) {
            val controls = composeRule.onAllNodesWithContentDescription(description)
            controls.assertCountEquals(READING_ALOUD.messages.size)
            controls.assertAll(isNotEnabled())
        }
    }
}
