package com.danemadsen.curie.inference

import com.danemadsen.curie.data.Attachment
import com.danemadsen.curie.data.AttachmentKind
import com.danemadsen.curie.data.Message
import com.danemadsen.curie.data.MessageRole
import com.danemadsen.curie.data.ThreadMessage
import com.danemadsen.curie.data.ToolCallRecord
import com.danemadsen.curie.engine.chat.ChatRole
import com.danemadsen.curie.engine.chat.ContentPart
import com.danemadsen.curie.engine.chat.GeneratedContent
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ChatHistoryTest {
    private val history = ChatHistory(FakeMediaInputs())

    private fun item(
        id: String,
        role: MessageRole,
        text: String,
        attachments: List<Attachment> = emptyList(),
        toolCalls: List<ToolCallRecord> = emptyList(),
        error: String? = null,
    ): ThreadMessage {
        val message = Message(
            id = id,
            chatId = "chat",
            parentId = null,
            selectedChildId = null,
            role = role,
            text = text,
            createdAt = 0,
            updatedAt = 0,
            toolCalls = toolCalls,
            speechFile = null,
            error = error,
        )
        return ThreadMessage(message, attachments, siblingIndex = 0, siblingCount = 1)
    }

    private fun attachment(id: String, kind: AttachmentKind, isAvailable: Boolean = true) = Attachment(
        id = id,
        kind = kind,
        path = "/attachments/$id",
        mimeType = "application/octet-stream",
        width = 640,
        height = 480,
        durationMs = 1_000,
        frameCount = 30,
        fps = 30.0,
        sampleCount16k = 16_000,
        isAvailable = isAvailable,
    )

    @Test
    fun userMessagesGiveTheirMediaBeforeTheirText() {
        val photo = attachment("photo", AttachmentKind.IMAGE)
        val clip = attachment("clip", AttachmentKind.VIDEO)
        val voice = attachment("voice", AttachmentKind.AUDIO)
        val thread = listOf(item("q", MessageRole.USER, "What is this?", listOf(photo, clip, voice)))

        val messages = history.toChatMessages("system", thread, emptyMap())

        assertEquals(ChatRole.SYSTEM, messages[0].role)
        val parts = messages[1].parts
        assertEquals("photo", assertIs<ContentPart.Image>(parts[0]).input.key)
        val video = assertIs<ContentPart.Video>(parts[1])
        assertTrue(video.withSoundtrack)
        assertEquals("voice", assertIs<ContentPart.Audio>(parts[2]).input.key)
        assertEquals("What is this?", assertIs<ContentPart.Text>(parts[3]).text)
        assertTrue(ChatHistory.isSpoken(thread[0].attachments))
    }

    @Test
    fun missingMediaIsLeftOutWithANote() {
        val gone = attachment("gone", AttachmentKind.IMAGE, isAvailable = false)
        val thread = listOf(item("q", MessageRole.USER, "", listOf(gone)))

        val parts = history.toChatMessages("system", thread, emptyMap())[1].parts

        val note = assertIs<ContentPart.Text>(parts.single())
        assertTrue(note.text.contains("no longer available"))
        assertFalse(ChatHistory.isSpoken(thread[0].attachments))
    }

    @Test
    fun answersReplayWhatTheEngineGenerated() {
        val generated = GeneratedContent(intArrayOf(1, 2, 3), IntArray(0))
        val thread = listOf(
            item("q", MessageRole.USER, "Hi"),
            item("a", MessageRole.ASSISTANT, "Hello!"),
        )

        val answer = history.toChatMessages("system", thread, mapOf("a" to generated))[2]

        assertSame(generated, answer.generated)
    }

    @Test
    fun importedAnswersGiveTheirTextAndToolCalls() {
        val record = ToolCallRecord("set_timer", "{\"seconds\": 60}", "{\"ok\": true}", "Timer set")
        val damaged = ToolCallRecord("set_alarm", "not json")
        val thread = listOf(
            item("q", MessageRole.USER, "Timer please"),
            item("a", MessageRole.ASSISTANT, "", toolCalls = listOf(record, damaged)),
            item("t", MessageRole.TOOL, "[{\"ok\": true}]"),
            item("b", MessageRole.ASSISTANT, "Done."),
        )

        val messages = history.toChatMessages("system", thread, emptyMap())

        val call = messages[2].toolCalls.single()
        assertEquals("set_timer", call.name)
        assertEquals("60", call.arguments["seconds"].toString())
        assertEquals(ChatRole.TOOL, messages[3].role)
        assertEquals("[{\"ok\": true}]", messages[3].text())
        assertEquals("Done.", messages[4].text())
    }

    @Test
    fun emptyFailedAnswersAreLeftOut() {
        val thread = listOf(
            item("q", MessageRole.USER, "Hi"),
            item("a", MessageRole.ASSISTANT, "", error = "failed"),
            item("q2", MessageRole.USER, "Hello?"),
        )

        val messages = history.toChatMessages("system", thread, emptyMap())

        assertEquals(listOf(ChatRole.SYSTEM, ChatRole.USER, ChatRole.USER), messages.map { message -> message.role })
    }

    @Test
    fun answersStoppedBeforeTheirFirstTokenAreLeftOut() {
        val nothingGenerated = GeneratedContent(IntArray(0), IntArray(0))
        val thread = listOf(
            item("q", MessageRole.USER, "Hi"),
            item("a", MessageRole.ASSISTANT, ""),
            item("q2", MessageRole.USER, "Hello?"),
        )

        val messages = history.toChatMessages("system", thread, mapOf("a" to nothingGenerated))

        assertEquals(listOf(ChatRole.SYSTEM, ChatRole.USER, ChatRole.USER), messages.map { message -> message.role })
    }

    @Test
    fun onlyAudioThatIsStillThereMakesAMessageSpoken() {
        val voice = attachment("voice", AttachmentKind.AUDIO)
        val lostVoice = attachment("lost", AttachmentKind.AUDIO, isAvailable = false)
        val photo = attachment("photo", AttachmentKind.IMAGE)

        assertTrue(ChatHistory.isSpoken(listOf(photo, voice)))
        assertFalse(ChatHistory.isSpoken(listOf(lostVoice)))
        assertFalse(ChatHistory.isSpoken(listOf(photo)))
    }
}
