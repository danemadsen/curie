package com.danemadsen.curie.inference

import com.danemadsen.curie.engine.chat.ChatMessage
import com.danemadsen.curie.engine.chat.ChatRole
import com.danemadsen.curie.engine.chat.ContentPart
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ContextFitterTest {
    private val renderer = FakePromptRenderer()

    /** System prompt, then [turns] exchanges of a 100-character question and answer, then a question. */
    private fun conversation(turns: Int, lastQuestion: String = "last question"): List<ChatMessage> {
        val messages = ArrayList<ChatMessage>()
        messages.add(ChatMessage.system("s".repeat(100)))
        repeat(turns) { turn ->
            messages.add(ChatMessage.user("q$turn".padEnd(100, '.')))
            messages.add(assistant("a$turn".padEnd(100, '.')))
        }
        messages.add(ChatMessage.user(lastQuestion))
        return messages
    }

    private fun assistant(text: String) = ChatMessage(ChatRole.ASSISTANT, listOf(ContentPart.Text(text)))

    @Test
    fun aConversationThatFitsIsKeptWhole() {
        val messages = conversation(turns = 3)

        val fitted = ContextFitter.fit(messages, emptyList(), capacity = 4096, wantedNewSlots = 1024, renderer = renderer)

        assertEquals(0, fitted.droppedMessages)
        assertEquals(1024, fitted.maxNewSlots)
        assertEquals(messages, renderer.lastMessages)
    }

    @Test
    fun theOldestTurnsGoButTheSystemPromptAndTheLastQuestionStay() {
        val messages = conversation(turns = 10)
        val whole = renderer.render(messages, emptyList(), addGenerationPrompt = true)

        // A budget well under the whole conversation
        val capacity = whole.length / 2 + 100
        val fitted = ContextFitter.fit(messages, emptyList(), capacity = capacity, wantedNewSlots = 100, renderer = renderer)

        val kept = renderer.lastMessages
        assertEquals(messages.first(), kept.first())
        assertEquals(messages.last(), kept.last())
        assertTrue(fitted.droppedMessages > 0)
        assertEquals(messages.size - fitted.droppedMessages, kept.size)

        // Whole turns: the first kept message after the system prompt is a question
        assertEquals(ChatRole.USER, kept[1].role)

        // A cut leaves room to grow: the prompt drops to about 70 % of its budget
        val budget = capacity - 100
        assertTrue(fitted.prompt.length <= budget * ContextFitter.TRIM_TARGET)
        assertEquals(100, fitted.maxNewSlots)
    }

    @Test
    fun theWindowKeepsTheCutWhileTheConversationGrows() {
        val window = ContextWindow()
        val messages = conversation(turns = 10)
        val whole = renderer.render(messages, emptyList(), addGenerationPrompt = true)
        val capacity = whole.length / 2 + 100

        val first = ContextFitter.fit(messages, emptyList(), capacity, wantedNewSlots = 100, renderer = renderer, window = window)
        val firstKept = renderer.lastMessages

        // One more exchange: the cut stays where it was, so the prompt only grows at its end
        val longer = messages + assistant("answer") + ChatMessage.user("another question")
        val second = ContextFitter.fit(longer, emptyList(), capacity, wantedNewSlots = 100, renderer = renderer, window = window)
        val secondKept = renderer.lastMessages

        assertEquals(first.droppedMessages, second.droppedMessages)
        assertEquals(firstKept, secondKept.subList(0, firstKept.size))
    }

    @Test
    fun aConversationThatFitsIsNotCutWhateverTheWindowSays() {
        val messages = conversation(turns = 4)
        val window = ContextWindow()
        window.droppedMessages = 4

        val fitted = ContextFitter.fit(messages, emptyList(), capacity = 4096, wantedNewSlots = 1024, renderer = renderer, window = window)

        assertEquals(0, fitted.droppedMessages)
        assertEquals(messages, renderer.lastMessages)
        assertEquals(0, window.droppedMessages)
    }

    @Test
    fun aShorterBranchOfALongChatKeepsTheTurnsItHasRoomFor() {
        // One window per chat: a long branch is cut first
        val window = ContextWindow()
        val longBranch = conversation(turns = 40)
        val longFit = ContextFitter.fit(
            longBranch,
            emptyList(),
            capacity = 4096,
            wantedNewSlots = 1024,
            renderer = renderer,
            window = window,
        )
        assertTrue(longFit.droppedMessages > 0)

        // An early question edited: the new branch fits whole
        val shortBranch = conversation(turns = 5)
        val shortFit = ContextFitter.fit(
            shortBranch,
            emptyList(),
            capacity = 4096,
            wantedNewSlots = 1024,
            renderer = renderer,
            window = window,
        )

        assertEquals(0, shortFit.droppedMessages)
        assertEquals(shortBranch, renderer.lastMessages)
    }

    @Test
    fun anotherLongBranchIsNotCutMoreThanItNeeds() {
        val window = ContextWindow()
        val answerSlots = 100
        val capacity = 3_000 + answerSlots
        val longBranch = conversation(turns = 40)
        val longFit = ContextFitter.fit(longBranch, emptyList(), capacity, answerSlots, renderer, window = window)

        // A branch that is over the budget too, but by less: the long branch's cut would leave too little
        val otherBranch = conversation(turns = 16)
        val otherFit = ContextFitter.fit(otherBranch, emptyList(), capacity, answerSlots, renderer, window = window)
        val freshFit = ContextFitter.fit(otherBranch, emptyList(), capacity, answerSlots, renderer)

        assertTrue(otherFit.droppedMessages < longFit.droppedMessages)
        assertEquals(freshFit.droppedMessages, otherFit.droppedMessages)
    }

    @Test
    fun anAnswerGetsTheRoomThereIsDownToTheMinimum() {
        val messages = conversation(turns = 0, lastQuestion = "x".repeat(500))
        val promptLength = renderer.render(messages, emptyList(), addGenerationPrompt = true).length

        val fitted = ContextFitter.fit(messages, emptyList(), capacity = promptLength + 300, wantedNewSlots = 1024, renderer = renderer)

        assertEquals(300, fitted.maxNewSlots)
    }

    @Test
    fun aLastTurnThatCannotFitIsRefused() {
        val messages = conversation(turns = 2, lastQuestion = "x".repeat(5000))

        val error = assertFailsWith<PromptTooLongException> {
            ContextFitter.fit(messages, emptyList(), capacity = 4096, wantedNewSlots = 1024, renderer = renderer)
        }
        assertEquals(4096, error.capacity)
    }

    @Test
    fun dropCountsLandOnTurnBoundaries() {
        val messages = listOf(
            ChatMessage.system("system"),
            ChatMessage.user("one"),
            assistant("answer one"),
            ChatMessage.tool("[]"),
            assistant("answer one again"),
            ChatMessage.user("two"),
            assistant("answer two"),
            ChatMessage.user("three"),
        )

        assertEquals(0, ContextFitter.turnAlignedDropCount(messages, wanted = 3))
        assertEquals(4, ContextFitter.turnAlignedDropCount(messages, wanted = 5))
        assertEquals(6, ContextFitter.turnAlignedDropCount(messages, wanted = 6))

        // Never the last question
        assertEquals(6, ContextFitter.turnAlignedDropCount(messages, wanted = 100))
    }
}
