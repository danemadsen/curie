package com.danemadsen.curie.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.danemadsen.curie.engine.chat.GeneratedContent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Robolectric 4.17 needs Java 21 for SDK 36 and later; 35 runs on Java 17. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ChatRepositoryTest {
    private lateinit var fixture: DataTestFixture
    private lateinit var repository: ChatRepository

    @Before
    fun setUp() {
        fixture = DataTestFixture()
        repository = fixture.repository
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    private suspend fun visibleIds(chatId: String): List<String> {
        val thread = repository.getThread(chatId)
        return thread.map { item -> item.message.id }
    }

    private suspend fun selectedChildOf(messageId: String): String? = repository.getMessage(messageId)?.selectedChildId

    // === Building the thread ===

    @Test
    fun appendedMessagesContinueTheThreadAndTheFirstUserMessageNamesTheChat() = runTest {
        val chat = repository.createChat()
        val question = repository.appendMessage(chat.id, null, MessageRole.USER, "\n  Hello there  \nsecond line")
        val answer = repository.appendMessage(chat.id, question.id, MessageRole.ASSISTANT, "Hi!")

        assertEquals(listOf(question.id, answer.id), visibleIds(chat.id))
        assertEquals(answer.id, selectedChildOf(question.id))
        assertEquals("Hello there", repository.getChat(chat.id)?.title)

        // A later first message (an edit) does not rename a chat that has a title
        repository.addSibling(question.id, "Another opening")
        assertEquals("Hello there", repository.getChat(chat.id)?.title)
    }

    @Test
    fun theFirstUserMessageWithTextNamesTheChatWhereverItIs() = runTest {
        val chat = repository.createChat()
        val greeting = repository.appendMessage(chat.id, null, MessageRole.ASSISTANT, "Hello, I am Curie")
        val voiceOnly = repository.appendMessage(chat.id, greeting.id, MessageRole.USER, "")
        assertEquals("", repository.getChat(chat.id)?.title)

        val answer = repository.appendMessage(chat.id, voiceOnly.id, MessageRole.ASSISTANT, "Sorry?")
        repository.appendMessage(chat.id, answer.id, MessageRole.USER, "Set a timer")

        assertEquals("Set a timer", repository.getChat(chat.id)?.title)
    }

    @Test
    fun aChatWithATitleKeepsIt() = runTest {
        val chat = repository.createChat("Trip plans")
        repository.appendMessage(chat.id, null, MessageRole.USER, "Where should we go?")

        assertEquals("Trip plans", repository.getChat(chat.id)?.title)
    }

    @Test
    fun titlesAreTheFirstLineCutToFortyCharactersWithoutSplittingAnEmoji() {
        val emojiAtTheCut = "a".repeat(38) + "😀" + "tail"

        assertEquals("", ChatRepository.titleFrom("  \n \n"))
        assertEquals("Short", ChatRepository.titleFrom("\n Short \nmore"))
        assertEquals("c".repeat(40), ChatRepository.titleFrom("c".repeat(40)))
        assertEquals("b".repeat(39) + "…", ChatRepository.titleFrom("b".repeat(60)))
        assertEquals("a".repeat(38) + "…", ChatRepository.titleFrom(emojiAtTheCut))
    }

    @Test
    fun chatsAreListedByLatestMessageAndRenamingDoesNotReorder() = runTest {
        val older = repository.createChat("older")
        val newer = repository.createChat("newer")
        val listed = repository.observeChats()

        assertEquals(listOf(newer.id, older.id), listed.first().map { chat -> chat.id })

        repository.appendMessage(older.id, null, MessageRole.USER, "bump")
        assertEquals(listOf(older.id, newer.id), listed.first().map { chat -> chat.id })

        repository.renameChat(newer.id, "  renamed  ")
        assertEquals(listOf(older.id, newer.id), listed.first().map { chat -> chat.id })
        assertEquals("renamed", repository.getChat(newer.id)?.title)
    }

    @Test
    fun theThreadFlowEmitsEveryChange() = runTest {
        val chat = repository.createChat()
        val emissions = Channel<List<ThreadMessage>>(Channel.UNLIMITED)
        val collector = launch {
            repository.observeThread(chat.id).collect { thread -> emissions.send(thread) }
        }

        assertTrue(emissions.receive().isEmpty())

        val question = repository.appendMessage(chat.id, null, MessageRole.USER, "Hi")
        assertEquals(listOf(question.id), emissions.receive().map { item -> item.message.id })

        repository.updateMessage(question.id, text = "Hi there")
        assertEquals("Hi there", emissions.receive().single().message.text)

        repository.deleteChat(chat.id)
        assertTrue(emissions.receive().isEmpty())
        collector.cancel()
    }

    @Test
    fun appendingRejectsAParentOfAnotherChatAndAMissingChat() = runTest {
        val first = repository.createChat()
        val second = repository.createChat()
        val message = repository.appendMessage(first.id, null, MessageRole.USER, "hello")

        assertFailsWith<IllegalArgumentException> {
            repository.appendMessage(second.id, message.id, MessageRole.ASSISTANT, "wrong chat")
        }
        assertFailsWith<IllegalArgumentException> {
            repository.appendMessage("missing", null, MessageRole.USER, "no chat")
        }
        assertEquals(listOf(message.id), visibleIds(first.id))
        assertTrue(visibleIds(second.id).isEmpty())
    }

    // === Regenerate, edit, select ===

    @Test
    fun regeneratingAddsASelectedSiblingAndTheControlsMoveBetweenThem() = runTest {
        val chat = repository.createChat()
        val question = repository.appendMessage(chat.id, null, MessageRole.USER, "Q")
        val first = repository.appendMessage(chat.id, question.id, MessageRole.ASSISTANT, "A1")
        val second = repository.addSibling(first.id, "")

        assertEquals(MessageRole.ASSISTANT, second.role)
        assertEquals(question.id, second.parentId)
        val thread = repository.getThread(chat.id)
        assertEquals(listOf(question.id, second.id), thread.map { item -> item.message.id })
        assertEquals(1, thread.last().siblingIndex)
        assertEquals(2, thread.last().siblingCount)

        repository.selectSibling(second.id, -1)
        assertEquals(listOf(question.id, first.id), visibleIds(chat.id))
        assertEquals(0, repository.getThread(chat.id).last().siblingIndex)

        // Past either end nothing happens
        repository.selectSibling(first.id, -1)
        assertEquals(first.id, selectedChildOf(question.id))
        repository.selectSibling(first.id, 1)
        repository.selectSibling(second.id, 1)
        assertEquals(second.id, selectedChildOf(question.id))
    }

    @Test
    fun eachBranchRemembersItsOwnContinuation() = runTest {
        val chat = repository.createChat()
        val question = repository.appendMessage(chat.id, null, MessageRole.USER, "Q")
        val answer = repository.appendMessage(chat.id, question.id, MessageRole.ASSISTANT, "A")
        val followUp = repository.appendMessage(chat.id, answer.id, MessageRole.USER, "Q2")
        val followAnswer = repository.appendMessage(chat.id, followUp.id, MessageRole.ASSISTANT, "A2")

        val regenerated = repository.addSibling(answer.id, "A'")
        assertEquals(listOf(question.id, regenerated.id), visibleIds(chat.id))

        repository.selectSibling(regenerated.id, -1)
        assertEquals(listOf(question.id, answer.id, followUp.id, followAnswer.id), visibleIds(chat.id))
    }

    @Test
    fun editingTheFirstMessageMovesTheChatsSelection() = runTest {
        val chat = repository.createChat()
        val original = repository.appendMessage(chat.id, null, MessageRole.USER, "first")
        val answer = repository.appendMessage(chat.id, original.id, MessageRole.ASSISTANT, "reply")

        val edited = repository.addSibling(original.id, "first, edited")
        assertNull(edited.parentId)
        assertEquals(listOf(edited.id), visibleIds(chat.id))
        assertEquals(2, repository.getThread(chat.id).single().siblingCount)

        repository.selectSibling(edited.id, -1)
        assertEquals(listOf(original.id, answer.id), visibleIds(chat.id))
    }

    // === Delete ===

    @Test
    fun deletingTheVisibleSiblingShowsThePreviousOneElseTheNext() = runTest {
        val chat = repository.createChat()
        val question = repository.appendMessage(chat.id, null, MessageRole.USER, "Q")
        val first = repository.appendMessage(chat.id, question.id, MessageRole.ASSISTANT, "1")
        val second = repository.addSibling(first.id, "2")
        val third = repository.addSibling(first.id, "3")

        repository.deleteMessage(third.id)
        assertEquals(second.id, selectedChildOf(question.id))

        repository.selectSibling(second.id, -1)
        repository.deleteMessage(first.id)
        assertEquals(second.id, selectedChildOf(question.id))
        assertEquals(1, repository.getThread(chat.id).last().siblingCount)

        repository.deleteMessage(second.id)
        assertNull(selectedChildOf(question.id))
        assertEquals(listOf(question.id), visibleIds(chat.id))
    }

    @Test
    fun deletingAHiddenSiblingKeepsTheSelection() = runTest {
        val chat = repository.createChat()
        val question = repository.appendMessage(chat.id, null, MessageRole.USER, "Q")
        val first = repository.appendMessage(chat.id, question.id, MessageRole.ASSISTANT, "1")
        val second = repository.addSibling(first.id, "2")
        val third = repository.addSibling(first.id, "3")
        repository.selectSibling(third.id, -1)

        repository.deleteMessage(first.id)

        assertEquals(second.id, selectedChildOf(question.id))
        assertEquals(listOf(question.id, second.id), visibleIds(chat.id))
    }

    @Test
    fun deletingAMessageRemovesEveryBranchBelowIt() = runTest {
        val chat = repository.createChat()
        val question = repository.appendMessage(chat.id, null, MessageRole.USER, "Q")
        val answer = repository.appendMessage(chat.id, question.id, MessageRole.ASSISTANT, "A")
        val branchOne = repository.appendMessage(chat.id, answer.id, MessageRole.USER, "B1")
        val branchTwo = repository.addSibling(branchOne.id, "B2")
        val leaf = repository.appendMessage(chat.id, branchTwo.id, MessageRole.ASSISTANT, "leaf")

        repository.deleteMessage(answer.id)

        for (id in listOf(answer.id, branchOne.id, branchTwo.id, leaf.id)) {
            assertNull(repository.getMessage(id), "message $id should be gone")
        }
        assertEquals(listOf(question.id), visibleIds(chat.id))
        assertNull(selectedChildOf(question.id))
    }

    @Test
    fun deletingTheFirstMessageRepointsTheChat() = runTest {
        val chat = repository.createChat()
        val first = repository.appendMessage(chat.id, null, MessageRole.USER, "one")
        val second = repository.addSibling(first.id, "two")

        repository.deleteMessage(second.id)
        assertEquals(listOf(first.id), visibleIds(chat.id))

        repository.deleteMessage(first.id)
        assertTrue(visibleIds(chat.id).isEmpty())
        assertNotNull(repository.getChat(chat.id))
    }

    @Test
    fun aThreadDeeperThanSqlitesTriggerDepthDeletesInOneGo() = runTest {
        val messages = List(1_500) { index ->
            val role = if (index % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT
            NewMessage(role, "message $index", createdAt = index.toLong(), toolCalls = emptyList(), attachments = emptyList())
        }
        val chat = repository.insertThread("deep", createdAt = 0, messages = messages)
        val thread = repository.getThread(chat.id)
        assertEquals(1_500, thread.size)

        repository.deleteMessage(thread.first().message.id)

        assertTrue(repository.getThread(chat.id).isEmpty())
        assertNull(repository.getMessage(thread.last().message.id))
    }

    @Test
    fun deletingAChatRemovesItsMessages() = runTest {
        val chat = repository.createChat()
        val message = repository.appendMessage(chat.id, null, MessageRole.USER, "Q")

        repository.deleteChat(chat.id)

        assertNull(repository.getChat(chat.id))
        assertNull(repository.getMessage(message.id))
    }

    // === Updates and generated content ===

    @Test
    fun updatesChangeOnlyTheGivenFields() = runTest {
        val chat = repository.createChat()
        val question = repository.appendMessage(chat.id, null, MessageRole.USER, "Q")
        val answer = repository.appendMessage(chat.id, question.id, MessageRole.ASSISTANT, "")
        val toolCall = ToolCallRecord("set_timer", """{"minutes":5}""", resultJson = """{"ok":true}""", summary = "Timer set")
        val speech = repository.speechFileFor(answer.id)
        speech.parentFile?.mkdirs()
        speech.writeText("RIFF")

        assertTrue(repository.updateMessage(answer.id, text = "Partial"))
        assertTrue(repository.updateMessage(answer.id, error = "Context full"))
        assertTrue(repository.updateMessage(answer.id, toolCalls = listOf(toolCall), speechFile = speech.path))

        val updated = repository.getMessage(answer.id)
        assertNotNull(updated)
        assertEquals("Partial", updated.text)
        assertEquals("Context full", updated.error)
        assertEquals(listOf(toolCall), updated.toolCalls)
        assertEquals(speech.path, updated.speechFile)
        assertTrue(updated.updatedAt > answer.updatedAt)

        assertFalse(repository.updateMessage("missing", text = "nobody"))
    }

    @Test
    fun generatedContentIsLoadedSeparatelyAndExactly() = runTest {
        val chat = repository.createChat()
        val question = repository.appendMessage(chat.id, null, MessageRole.USER, "Q")
        val tokens = intArrayOf(1, -2, 125_032, Int.MAX_VALUE, Int.MIN_VALUE)
        val audioCodes = IntArray(24) { index -> index * 84 }
        val answer = repository.appendMessage(chat.id, question.id, MessageRole.ASSISTANT, "A")
        val streamed = repository.appendMessage(chat.id, answer.id, MessageRole.USER, "follow-up")

        repository.updateMessage(answer.id, generated = GeneratedContent(tokens, audioCodes))
        val fromAppend = GeneratedContent(intArrayOf(7), IntArray(0))
        val replayed = repository.appendMessage(chat.id, streamed.id, MessageRole.ASSISTANT, "B", generated = fromAppend)

        // More ids than one statement takes: the query is chunked
        val unknownIds = List(1_200) { index -> "unknown-$index" }
        val ids = listOf(question.id, answer.id, replayed.id) + unknownIds
        val contents = repository.loadGeneratedContent(ids)

        assertEquals(setOf(answer.id, replayed.id), contents.keys)
        assertContentEquals(tokens, contents.getValue(answer.id).tokens)
        assertContentEquals(audioCodes, contents.getValue(answer.id).audioCodes)
        assertContentEquals(intArrayOf(7), contents.getValue(replayed.id).tokens)
        assertEquals(0, contents.getValue(replayed.id).audioCodes.size)
    }

    // === Files ===

    @Test
    fun attachmentsAreShownInOrderAndMustComeFromTheStore() = runTest {
        val chat = repository.createChat()
        val image = fixture.importAttachment("IMAGE")
        val audio = fixture.importAttachment("AUDIO 2500")
        val message = repository.appendMessage(chat.id, null, MessageRole.USER, "look", listOf(audio, image))

        val shown = repository.getThread(chat.id).single()
        assertEquals(message.id, shown.message.id)
        assertEquals(listOf(audio, image), shown.attachments)

        val foreign = image.copy(path = File(fixture.context.cacheDir, "elsewhere.png").path)
        assertFailsWith<IllegalArgumentException> {
            repository.appendMessage(chat.id, null, MessageRole.USER, "no", listOf(foreign))
        }
    }

    @Test
    fun aFileSharedByAnEditLivesUntilItsLastMessageIsDeleted() = runTest {
        val chat = repository.createChat()
        val image = fixture.importAttachment("IMAGE")
        val original = repository.appendMessage(chat.id, null, MessageRole.USER, "photo", listOf(image))
        val edited = repository.addSibling(original.id, "photo, edited", listOf(image))

        repository.deleteMessage(edited.id)
        assertTrue(File(image.path).exists())
        assertEquals(listOf(image), repository.getThread(chat.id).single().attachments)

        repository.deleteMessage(original.id)
        assertFalse(File(image.path).exists())
    }

    @Test
    fun deletingChatsDeletesTheirFilesOnly() = runTest {
        val kept = repository.createChat()
        val deleted = repository.createChat()
        val keptImage = fixture.importAttachment("IMAGE")
        val deletedAudio = fixture.importAttachment("AUDIO 1000")
        repository.appendMessage(kept.id, null, MessageRole.USER, "keep", listOf(keptImage))
        val question = repository.appendMessage(deleted.id, null, MessageRole.USER, "drop", listOf(deletedAudio))
        val answer = repository.appendMessage(deleted.id, question.id, MessageRole.ASSISTANT, "spoken")

        val speech = repository.speechFileFor(answer.id)
        speech.parentFile?.mkdirs()
        speech.writeText("RIFF")
        repository.updateMessage(answer.id, speechFile = speech.path)

        repository.deleteChat(deleted.id)
        assertFalse(File(deletedAudio.path).exists())
        assertFalse(speech.exists())
        assertTrue(File(keptImage.path).exists())

        repository.deleteAllChats()
        assertFalse(File(keptImage.path).exists())
        assertTrue(repository.observeChats().first().isEmpty())
    }

    @Test
    fun aSpeechFileOutsideTheSpeechDirectoryIsRefused() = runTest {
        val chat = repository.createChat()
        val message = repository.appendMessage(chat.id, null, MessageRole.ASSISTANT, "spoken")
        val outside = File(fixture.context.cacheDir, "kept.wav")
        outside.parentFile?.mkdirs()
        outside.writeText("RIFF")

        assertFailsWith<IllegalArgumentException> { repository.updateMessage(message.id, speechFile = outside.path) }

        repository.deleteMessage(message.id)
        assertTrue(outside.exists())
    }

    @Test
    fun filesAreStoredByNameRelativeToTheirDirectories() = runTest {
        val chat = repository.createChat()
        val image = fixture.importAttachment("IMAGE")
        val message = repository.appendMessage(chat.id, null, MessageRole.ASSISTANT, "spoken", listOf(image))
        val speech = repository.speechFileFor(message.id)
        speech.parentFile?.mkdirs()
        speech.writeText("RIFF")
        repository.updateMessage(message.id, speechFile = speech.path)

        val row = fixture.database.messageDao().getRow(message.id)
        val fileNames = fixture.database.attachmentDao().getAllFileNames()

        assertEquals("${message.id}.wav", row?.speechFileName)
        assertEquals(listOf(File(image.path).name), fileNames)
    }

    @Test
    fun filesThatAreGoneShowAsUnavailable() = runTest {
        // A restore from a cloud backup brings the rows back without the media
        val chat = repository.createChat()
        val image = fixture.importAttachment("IMAGE")
        val audio = fixture.importAttachment("AUDIO 1000")
        val question = repository.appendMessage(chat.id, null, MessageRole.USER, "look", listOf(image, audio))
        val answer = repository.appendMessage(chat.id, question.id, MessageRole.ASSISTANT, "spoken")
        val speech = repository.speechFileFor(answer.id)
        speech.parentFile?.mkdirs()
        speech.writeText("RIFF")
        repository.updateMessage(answer.id, speechFile = speech.path)

        assertTrue(File(image.path).delete())
        assertTrue(speech.delete())

        val thread = repository.getThread(chat.id)
        assertEquals(listOf(false, true), thread.first().attachments.map { attachment -> attachment.isAvailable })
        assertNull(thread.last().message.speechFile)
        assertNull(repository.getMessage(answer.id)?.speechFile)
    }

    @Test
    fun discardingAnAttachmentSparesFilesAMessageUses() = runTest {
        val chat = repository.createChat()
        val sent = fixture.importAttachment("IMAGE")
        val draft = fixture.importAttachment("IMAGE")
        repository.appendMessage(chat.id, null, MessageRole.USER, "sent", listOf(sent))

        repository.discardAttachment(sent)
        repository.discardAttachment(draft)

        assertTrue(File(sent.path).exists())
        assertFalse(File(draft.path).exists())
    }

    @Test
    fun orphanedFilesAreDeletedOnceTheyAreOldEnough() = runTest {
        val chat = repository.createChat()
        val used = fixture.importAttachment("IMAGE")
        val orphan = fixture.importAttachment("IMAGE")
        val recentOrphan = fixture.importAttachment("IMAGE")
        val message = repository.appendMessage(chat.id, null, MessageRole.ASSISTANT, "answer", listOf(used))

        fixture.speechDirectory.mkdirs()
        val usedSpeech = repository.speechFileFor(message.id)
        usedSpeech.writeText("RIFF")
        repository.updateMessage(message.id, speechFile = usedSpeech.path)
        val orphanSpeech = File(fixture.speechDirectory, "lost.wav")
        orphanSpeech.writeText("RIFF")

        val twoDaysAgo = fixture.now - 2 * 24 * 60 * 60 * 1000L
        for (path in listOf(used.path, orphan.path, usedSpeech.path, orphanSpeech.path)) {
            File(path).setLastModified(twoDaysAgo)
        }

        repository.deleteOrphanedFiles()

        assertTrue(File(used.path).exists())
        assertFalse(File(orphan.path).exists())
        assertTrue(File(recentOrphan.path).exists())
        assertTrue(usedSpeech.exists())
        assertFalse(orphanSpeech.exists())
    }
}
