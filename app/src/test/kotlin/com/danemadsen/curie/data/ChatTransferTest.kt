package com.danemadsen.curie.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.danemadsen.curie.engine.chat.GeneratedContent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ChatTransferTest {
    private val fixture = DataTestFixture()
    private val repository = fixture.repository
    private val transfer = transferWith(ImportLimits())

    @After
    fun tearDown() {
        fixture.close()
    }

    private fun transferWith(limits: ImportLimits) =
        ChatTransfer(repository, fixture.attachmentStore, clock = { 42L }, importLimits = limits)

    private suspend fun export(chatId: String): ByteArray {
        val output = ByteArrayOutputStream()
        transfer.exportChat(chatId, output)
        return output.toByteArray()
    }

    private suspend fun import(json: String, chatTransfer: ChatTransfer = transfer): ChatImportResult {
        val input = ByteArrayInputStream(json.toByteArray())
        return chatTransfer.importChat(input)
    }

    private fun dataUri(mimeType: String, content: String): String {
        val encoded = Base64.getEncoder().encodeToString(content.toByteArray())
        return "data:$mimeType;base64,$encoded"
    }

    private suspend fun assertNothingImported() {
        assertTrue(repository.observeChats().first().isEmpty())
        assertTrue(fixture.attachmentStore.directory.listFiles().orEmpty().isEmpty())
    }

    /**
     * [expected] is among [error]'s causes. With assertions on, as in tests, kotlinx.coroutines
     * rethrows an exception that leaves withContext as a copy whose cause is the original.
     */
    private fun assertCausedBy(expected: Throwable, error: Throwable) {
        val causes = generateSequence(error.cause) { cause -> cause.cause }
        assertTrue(causes.any { cause -> cause === expected }, "$error is not caused by $expected")
    }

    // === Round trip ===

    @Test
    fun theVisibleThreadSurvivesTheRoundTripAsANewChat() = runTest {
        val chat = repository.createChat("Holiday")
        val image = fixture.importAttachment("IMAGE")
        val audio = fixture.importAttachment("AUDIO 1500")
        val video = fixture.importAttachment("VIDEO 2000")
        val question = repository.appendMessage(chat.id, null, MessageRole.USER, "What is in these?", listOf(image, audio, video))
        val toolCall = ToolCallRecord("describe", """{"detail":"high"}""", resultJson = """{"ok":true}""", summary = "Described")
        val generated = GeneratedContent(intArrayOf(1, 2), IntArray(12))
        val answer = repository.appendMessage(
            chat.id,
            question.id,
            MessageRole.ASSISTANT,
            "A beach",
            toolCalls = listOf(toolCall),
            generated = generated,
        )
        val hidden = repository.addSibling(answer.id, "a draft nobody sees")
        repository.selectSibling(hidden.id, -1)

        val output = ByteArrayOutputStream()
        val exported = transfer.exportChat(chat.id, output)
        val bytes = output.toByteArray()
        val imported = transfer.importChat(ByteArrayInputStream(bytes))

        assertEquals(1, exported.omittedAttachments)
        assertEquals(1, imported.skippedAttachments)
        assertEquals(0, imported.skippedToolCalls)
        assertNotEquals(chat.id, imported.chat.id)
        assertEquals("Holiday", imported.chat.title)
        assertEquals(chat.createdAt, imported.chat.createdAt)

        val thread = repository.getThread(imported.chat.id)
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), thread.map { item -> item.message.role })
        assertEquals(listOf("What is in these?", "A beach"), thread.map { item -> item.message.text })
        assertEquals(listOf(question.createdAt, answer.createdAt), thread.map { item -> item.message.createdAt })
        assertEquals(listOf(toolCall), thread.last().message.toolCalls)

        val copies = thread.first().attachments
        assertEquals(listOf(AttachmentKind.IMAGE, AttachmentKind.AUDIO), copies.map { copy -> copy.kind })
        assertEquals("IMAGE", File(copies.first().path).readText())
        assertNotEquals(image.path, copies.first().path)
        assertEquals(audio.sampleCount16k, copies.last().sampleCount16k)

        // The engine's record of the answer stays behind: the new chat replays it from the text
        val importedIds = thread.map { item -> item.message.id }
        assertTrue(repository.loadGeneratedContent(importedIds).isEmpty())
        assertEquals(listOf(question.id, answer.id), repository.getThread(chat.id).map { item -> item.message.id })
    }

    @Test
    fun theExportIsReadableJson() = runTest {
        val chat = repository.createChat("Notes")
        val image = fixture.importAttachment("IMAGE")
        val video = fixture.importAttachment("VIDEO 1000")
        repository.appendMessage(chat.id, null, MessageRole.USER, "Look", listOf(image, video))

        val text = export(chat.id).decodeToString()
        val document = Json.parseToJsonElement(text).jsonObject

        assertEquals("curie-chat", document.getValue("format").jsonPrimitive.content)
        assertEquals(1, document.getValue("version").jsonPrimitive.content.toInt())
        assertEquals(42, document.getValue("exportedAt").jsonPrimitive.content.toInt())
        val message = document.getValue("messages").jsonArray.single().jsonObject
        assertEquals("user", message.getValue("role").jsonPrimitive.content)

        val attachments = message.getValue("attachments") as JsonArray
        val imageEntry = attachments[0] as JsonObject
        val videoEntry = attachments[1] as JsonObject
        assertEquals("image", imageEntry.getValue("kind").jsonPrimitive.content)
        assertEquals(dataUri("image/png", "IMAGE"), imageEntry.getValue("data").jsonPrimitive.content)
        assertEquals("video", videoEntry.getValue("kind").jsonPrimitive.content)
        assertNull(videoEntry["data"])
        assertEquals("Videos are not exported.", videoEntry.getValue("note").jsonPrimitive.content)
    }

    @Test
    fun anAttachmentTooLargeToEmbedIsListedWithANote() = runTest {
        val chat = repository.createChat()
        val padding = "x".repeat(ChatTransfer.MAX_EMBEDDED_BYTES_PER_ATTACHMENT.toInt())
        val large = fixture.importAttachment("IMAGE $padding")
        repository.appendMessage(chat.id, null, MessageRole.USER, "big", listOf(large))

        val output = ByteArrayOutputStream()
        val result = transfer.exportChat(chat.id, output)

        assertEquals(1, result.omittedAttachments)
        assertTrue(output.toString(Charsets.UTF_8.name()).contains("Too large to export."))
    }

    @Test
    fun theLargestExportImportsWithinTheImportLimits() = runTest {
        val chat = repository.createChat()
        val fullSize = ChatTransfer.MAX_EMBEDDED_BYTES_PER_ATTACHMENT.toInt()
        val first = fixture.importAttachment("IMAGE " + "a".repeat(fullSize - 6))
        val second = fixture.importAttachment("IMAGE " + "b".repeat(fullSize - 6))
        // Over the export's total budget, so it is listed without data
        val third = fixture.importAttachment("AUDIO 1000")
        repository.appendMessage(chat.id, null, MessageRole.USER, "photos", listOf(first, second, third))

        val output = ByteArrayOutputStream()
        val exported = transfer.exportChat(chat.id, output)
        val document = output.toByteArray()
        val imported = transfer.importChat(ByteArrayInputStream(document))

        assertEquals(1, exported.omittedAttachments)
        assertTrue(document.size < ImportLimits().maxDocumentBytes)
        assertEquals(1, imported.skippedAttachments)
        val copies = repository.getThread(imported.chat.id).single().attachments
        assertEquals(listOf(fullSize.toLong(), fullSize.toLong()), copies.map { copy -> File(copy.path).length() })
    }

    // === Rejected files ===

    @Test
    fun malformedDocumentsAreRejected() = runTest {
        assertFailsWith<ChatImportException.Malformed> { import("not json") }
        assertFailsWith<ChatImportException.Malformed> { import("{}") }
        assertFailsWith<ChatImportException.Malformed> {
            import("""{"format":"curie-chat","version":1,"messages":[{"role":"robot"}]}""")
        }
        assertNothingImported()
    }

    @Test
    fun otherFormatsAndNewerVersionsAreUnsupported() = runTest {
        val other = assertFailsWith<ChatImportException.Unsupported> { import("""{"format":"other","version":1}""") }
        val newer = assertFailsWith<ChatImportException.Unsupported> { import("""{"format":"curie-chat","version":2}""") }

        assertEquals("other", other.format)
        assertEquals(2, newer.version)
    }

    @Test
    fun aFileOverTheDocumentLimitIsTooLarge() = runTest {
        val limit = ImportLimits().maxDocumentBytes
        val padded = GeneratedInputStream(prefix = "{", length = limit + 1)

        val error = assertFailsWith<ChatImportException.TooLarge> { transfer.importChat(padded) }

        assertEquals(limit, error.limitBytes)
        assertNothingImported()
    }

    @Test
    fun aValueOverTheTokenLimitIsTooLarge() = runTest {
        val limits = ImportLimits(maxDocumentBytes = 1_000_000, maxTokenBytes = 20_000, maxDepth = 8)
        val chatTransfer = transferWith(limits)
        val longTitle = """{"format":"curie-chat","version":1,"title":"${"x".repeat(30_000)}"}"""
        // The parser skips an unknown value by reading it whole
        val longUnknownNumber = """{"format":"curie-chat","version":1,"futureCount":${"9".repeat(30_000)}}"""
        // A number for a known field fails as soon as it overflows
        val longCreatedAt = """{"format":"curie-chat","version":1,"createdAt":${"9".repeat(30_000)}}"""

        val titleError = assertFailsWith<ChatImportException.TooLarge> { import(longTitle, chatTransfer) }
        val numberError = assertFailsWith<ChatImportException.TooLarge> { import(longUnknownNumber, chatTransfer) }
        assertFailsWith<ChatImportException.Malformed> { import(longCreatedAt, chatTransfer) }

        assertEquals(20_000, titleError.limitBytes)
        assertEquals(20_000, numberError.limitBytes)
    }

    @Test
    fun nestingNoExportHasIsMalformed() = runTest {
        val nested = "[".repeat(100) + "]".repeat(100)
        val json = """{"format":"curie-chat","version":1,"futureField":$nested}"""

        assertFailsWith<ChatImportException.Malformed> { import(json) }
    }

    @Test
    fun aSourceThatFailsMidwayIsUnreadable() = runTest {
        // Well past the parser's first 16K-character buffer, where it wraps what its input throws
        val failure = IOException("provider failed")
        val title = "x".repeat(1_000_000)
        val prefix = """{"format":"curie-chat","version":1,"title":"$title"""
        val failing = GeneratedInputStream(prefix, length = 500_000, failure = failure)

        val error = assertFailsWith<ChatImportException.Unreadable> { transfer.importChat(failing) }

        assertCausedBy(failure, error)
    }

    @Test
    fun aSourceThatFailsAtOnceIsUnreadable() = runTest {
        val failure = IOException("permission revoked")
        val failing = GeneratedInputStream(prefix = "", length = 0, failure = failure)

        val error = assertFailsWith<ChatImportException.Unreadable> { transfer.importChat(failing) }

        assertCausedBy(failure, error)
    }

    // === Partly usable files ===

    @Test
    fun unusableAttachmentsAreSkippedAndTheTextKept() = runTest {
        val notBase64 = "data:image/png;base64,***"
        val notMedia = dataUri("image/png", "PDF document")
        val audio = dataUri("audio/mp4", "AUDIO 900")
        val json = """
            {"format":"curie-chat","version":1,"title":"Imported","messages":[
              {"role":"user","text":"files","createdAt":5,"attachments":[
                {"kind":"image","mimeType":"image/png","data":"$notBase64"},
                {"kind":"image","mimeType":"image/png","data":"$notMedia"},
                {"kind":"video","mimeType":"video/mp4","note":"Videos are not exported."},
                {"kind":"audio","mimeType":"audio/mp4","data":"$audio","futureField":true}
              ]},
              {"role":"tool","text":"{\"ok\":true}","createdAt":6}
            ]}
        """.trimIndent()

        val result = import(json)

        assertEquals(3, result.skippedAttachments)
        val thread = repository.getThread(result.chat.id)
        assertEquals(listOf("files", """{"ok":true}"""), thread.map { item -> item.message.text })
        assertEquals(listOf(MessageRole.USER, MessageRole.TOOL), thread.map { item -> item.message.role })
        assertEquals(listOf(AttachmentKind.AUDIO), thread.first().attachments.map { attachment -> attachment.kind })
        assertEquals(1, fixture.attachmentStore.directory.listFiles()?.size)
    }

    @Test
    fun damagedToolCallsAreDroppedAndCounted() = runTest {
        val json = """
            {"format":"curie-chat","version":1,"messages":[
              {"role":"assistant","text":"done","toolCalls":[
                {"name":"set_timer","argumentsJson":"{\"minutes\":5}","resultJson":"{\"ok\":true}"},
                {"name":"set_timer","argumentsJson":"not json"},
                {"name":"set_timer","argumentsJson":"[5]"},
                {"name":" ","argumentsJson":"{}"},
                {"name":"lookup","argumentsJson":"{}","resultJson":"{broken"},
                {"name":"lookup","argumentsJson":"{}"}
              ]}
            ]}
        """.trimIndent()

        val result = import(json)

        assertEquals(4, result.skippedToolCalls)
        val toolCalls = repository.getThread(result.chat.id).single().message.toolCalls
        val expected = listOf(
            ToolCallRecord("set_timer", """{"minutes":5}""", resultJson = """{"ok":true}"""),
            ToolCallRecord("lookup", "{}"),
        )
        assertEquals(expected, toolCalls)
    }

    @Test
    fun aFullDeviceFailsTheImportAndLeavesNothing() = runTest {
        // A file where the attachment directory belongs: storage refuses every copy
        val directory = fixture.attachmentStore.directory
        directory.parentFile?.mkdirs()
        directory.writeText("in the way")
        val image = dataUri("image/png", "IMAGE")
        val json = """
            {"format":"curie-chat","version":1,"messages":[
              {"role":"user","text":"photo","attachments":[{"kind":"image","mimeType":"image/png","data":"$image"}]}
            ]}
        """.trimIndent()

        val error = assertFailsWith<ChatImportException.StorageFailed> { import(json) }

        val causes = generateSequence(error.cause) { cause -> cause.cause }
        assertTrue(causes.any { cause -> cause is AttachmentImportException.StorageFailed })
        assertTrue(repository.observeChats().first().isEmpty())
    }

    /**
     * [prefix], then spaces up to [length] bytes, made while it is read; then [failure] if given,
     * else the end of the stream.
     */
    private class GeneratedInputStream(
        prefix: String,
        private val length: Long,
        private val failure: IOException? = null,
    ) : InputStream() {
        private val prefixBytes = prefix.toByteArray()
        private var position = 0L

        override fun read(): Int {
            val buffer = ByteArray(1)
            val count = read(buffer, 0, 1)
            return if (count < 0) -1 else buffer[0].toInt() and 0xFF
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= this.length) {
                if (failure != null) {
                    throw failure
                }
                return -1
            }

            val count = minOf(length.toLong(), this.length - position).toInt()
            for (index in 0 until count) {
                val at = position + index
                buffer[offset + index] = if (at < prefixBytes.size) prefixBytes[at.toInt()] else SPACE
            }
            position += count
            return count
        }

        private companion object {
            const val SPACE = ' '.code.toByte()
        }
    }
}
