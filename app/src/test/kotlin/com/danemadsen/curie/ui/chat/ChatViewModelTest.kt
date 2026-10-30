package com.danemadsen.curie.ui.chat

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.danemadsen.curie.audio.FakeClipPlayback
import com.danemadsen.curie.audio.FakeMicrophone
import com.danemadsen.curie.audio.FakeSpeechOutput
import com.danemadsen.curie.R
import com.danemadsen.curie.audio.MicrophoneArbiter
import com.danemadsen.curie.data.AttachmentLimits
import com.danemadsen.curie.data.ChatTransfer
import com.danemadsen.curie.data.DataTestFixture
import com.danemadsen.curie.data.MessageRole as StoredRole
import com.danemadsen.curie.data.Settings
import com.danemadsen.curie.data.SpeakReplies
import com.danemadsen.curie.engine.chat.ChatMessage
import com.danemadsen.curie.engine.chat.ChatRole
import com.danemadsen.curie.engine.chat.ToolCall
import com.danemadsen.curie.engine.generation.GenerationMode
import com.danemadsen.curie.inference.AssistantEngine
import com.danemadsen.curie.inference.ChatHistory
import com.danemadsen.curie.inference.ChatResponder
import com.danemadsen.curie.inference.ConversationSlot
import com.danemadsen.curie.inference.EngineUnavailableException
import com.danemadsen.curie.inference.FakeConversation
import com.danemadsen.curie.inference.FakeMediaInputs
import com.danemadsen.curie.inference.FakeModelSource
import com.danemadsen.curie.inference.FakeOmniEngine
import com.danemadsen.curie.inference.FakePromptRenderer
import com.danemadsen.curie.inference.FakeToolExecutor
import com.danemadsen.curie.inference.ScriptedAnswer
import com.danemadsen.curie.inference.SpeakableText
import com.danemadsen.curie.inference.text
import com.danemadsen.curie.tools.ToolOrigin
import com.danemadsen.curie.ui.common.ModelStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The chat flow end to end: the screen's actions, the database, and a scripted engine. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ChatViewModelTest {
    private val mainDispatcher = StandardTestDispatcher()
    private lateinit var fixture: DataTestFixture
    private lateinit var engineThread: ExecutorCoroutineDispatcher
    private lateinit var speechThread: ExecutorCoroutineDispatcher
    private lateinit var engineScope: CoroutineScope

    private val fakeEngine = FakeOmniEngine()
    private val model = FakeModelSource()
    private val tools = FakeToolExecutor()
    private val clips = FakeClipPlayback()
    private val speech = FakeSpeechOutput(finishesByItself = true)
    private val settings = MutableStateFlow(Settings(userName = "Dane"))
    private val modelStatus = MutableStateFlow<ModelStatus>(ModelStatus.Ready)
    private var renderer = FakePromptRenderer()

    /** Owns the screens' ViewModels, so tearDown clears them before the database closes. */
    private val viewModels = ViewModelStore()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        fixture = DataTestFixture(AttachmentLimits(maxAudioDurationMs = 120_000, maxVideoDurationMs = 60_000))
        engineThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        speechThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        viewModels.clear()
        engineScope.cancel()
        engineThread.close()
        speechThread.close()

        // Not fixture.close(): a cleared screen's last query may still be running on Room's
        // threads, and would fail on a closed database. The in-memory database is just dropped.
        fixture.context.filesDir.deleteRecursively()
        fixture.context.cacheDir.deleteRecursively()
        Dispatchers.resetMain()
    }

    private fun newViewModel(): ChatViewModel {
        val engine = AssistantEngine(
            model = model,
            engineFactory = { fakeEngine },
            rendererFactory = { renderer },
            scope = engineScope,
            engineDispatcher = engineThread,
            speechDispatcher = speechThread,
        )
        val systemPrompt = listOf(ChatMessage.system("system"))
        val warmUp = AssistantEngine.WarmUpPrompt(systemPrompt, tools.definitions)
        engine.chatWarmUpPrompt = { warmUp }
        val responder = ChatResponder(
            chats = fixture.repository,
            engine = engine,
            tools = tools,
            history = ChatHistory(FakeMediaInputs()),
            describeError = { error -> if (error is EngineUnavailableException) MODEL_UNAVAILABLE else "failed" },
            skippedToolSummary = "Not done",
            today = { LocalDate.of(2026, 10, 29) },
        )
        val dependencies = ChatDependencies(
            context = ApplicationProvider.getApplicationContext(),
            chats = fixture.repository,
            transfer = ChatTransfer(fixture.repository, fixture.attachmentStore),
            attachments = fixture.attachmentStore,
            attachmentLimits = AttachmentLimits(),
            settings = settings,
            modelStatus = modelStatus,
            engine = engine,
            responder = responder,
            microphone = FakeMicrophone(),
            arbiter = MicrophoneArbiter(),
            createClipPlayer = { clips },
            createSpeechOutput = { speech },
        )
        val factory = viewModelFactory {
            initializer { ChatViewModel(dependencies, SavedStateHandle()) }
        }
        val provider = ViewModelProvider.create(viewModels, factory)
        return provider[ChatViewModel::class]
    }

    /**
     * Waits for a state of the screen. Not with withTimeout: virtual time would run out at once
     * while the database and the engine work on their own threads (runTest has its own timeout).
     */
    private suspend fun ChatViewModel.awaitState(condition: (ChatUiState) -> Boolean): ChatUiState =
        uiState.first(condition)

    private fun ChatViewModel.send(text: String) {
        prompt.setTextAndPlaceCursorAtEnd(text)
        onAction(ChatAction.Send)
    }

    /** Waits until no answer is being generated and the last message's text is [text]. */
    private suspend fun ChatViewModel.awaitAnswer(text: String): ChatUiState = awaitState { state ->
        !state.isGenerating && state.messages.lastOrNull()?.text == text
    }

    private fun mainConversation(): FakeConversation =
        fakeEngine.conversations.single { conversation -> conversation.capacity == ConversationSlot.MAIN.capacity }

    @Test
    fun sendingStreamsTheAnswerIntoANewChat() = runTest(mainDispatcher) {
        val viewModel = newViewModel()
        val firstWordShown = CountDownLatch(1)
        fakeEngine.answer(ScriptedAnswer(text = "Hi Dane, how can I help?", pauseAfterFirstWord = firstWordShown))

        viewModel.send("Hello there")

        // The first word reaches the screen while the rest is still being generated
        val streaming = viewModel.awaitState { state -> state.messages.getOrNull(1)?.text == "Hi" }
        assertTrue(streaming.isGenerating)
        assertEquals("", viewModel.prompt.text.toString())
        firstWordShown.countDown()

        val done = viewModel.awaitAnswer("Hi Dane, how can I help?")
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), done.messages.map { message -> message.role })
        assertEquals("Hello there", done.messages[0].text)

        val chatId = assertNotNull(done.activeChatId)
        assertEquals("Hello there", fixture.repository.getChat(chatId)?.title)
        val answerId = done.messages[1].id
        val generated = fixture.repository.loadGeneratedContent(listOf(answerId))
        assertTrue(answerId in generated)

        // Typed messages get typed answers, after the system prompt with the user's name and the tools
        val (_, request) = mainConversation().generations.single()
        assertEquals(GenerationMode.TEXT, request.mode)
        val prompt = renderer.lastMessages
        assertTrue(prompt.first().text().contains("The user's name is Dane."))
        assertEquals("Hello there", prompt.last().text())
    }

    @Test
    fun aToolCallRunsTheToolAndTheModelAnswersAfterIt() = runTest(mainDispatcher) {
        val viewModel = newViewModel()
        val arguments = JsonObject(mapOf("seconds" to JsonPrimitive(60)))
        fakeEngine.answer(ScriptedAnswer(text = "Setting it.", toolCalls = listOf(ToolCall("set_timer", arguments))))
        fakeEngine.answer("Your timer is set.")

        viewModel.send("Set a timer for a minute")
        val state = viewModel.awaitAnswer("Your timer is set.")

        // The tool message is hidden; the assistant's first message shows what it did
        assertEquals(3, state.messages.size)
        val toolAnswer = state.messages[1]
        assertEquals("Setting it.", toolAnswer.text)
        assertEquals("Timer set", toolAnswer.toolActions.single().summary)
        assertEquals(ToolIcon.TIMER, toolAnswer.toolActions.single().icon)
        assertTrue(toolAnswer.toolActions.single().succeeded)

        val (call, origin) = tools.calls.single()
        assertEquals("set_timer", call.name)
        assertEquals(ToolOrigin.CHAT, origin)

        // One tool message holds the JSON array of the results, and the second prompt replays both turns
        val chatId = assertNotNull(state.activeChatId)
        val thread = fixture.repository.getThread(chatId)
        val toolMessage = thread.single { item -> item.message.role == StoredRole.TOOL }
        assertEquals("[{\"ok\": true}]", toolMessage.message.text)
        val secondPrompt = renderer.lastMessages
        assertEquals(ChatRole.TOOL, secondPrompt.last().role)
        assertEquals("[{\"ok\": true}]", secondPrompt.last().text())
        assertNotNull(secondPrompt[secondPrompt.size - 2].generated)
    }

    @Test
    fun anAnswerThatUsedToolsReadsAsOneMessage() = runTest(mainDispatcher) {
        val viewModel = newViewModel()
        val arguments = JsonObject(emptyMap())
        fakeEngine.answer(ScriptedAnswer(text = "Setting it.", toolCalls = listOf(ToolCall("set_timer", arguments))))
        fakeEngine.answer("Your timer is set.")
        viewModel.send("Set a timer")
        val answered = viewModel.awaitAnswer("Your timer is set.")

        // One author and one set of controls: the second round carries on the first
        val firstRound = answered.messages[1]
        val finalRound = answered.messages[2]
        assertTrue(!firstRound.isContinuation)
        assertTrue(finalRound.isContinuation)

        // Reading the answer aloud reads its final round, and keeps the speech there
        fakeEngine.answer(ScriptedAnswer(audioFrames = 2))
        viewModel.onAction(ChatAction.Speak(firstRound.id))
        val spoken = viewModel.awaitState { state -> state.messages.getOrNull(2)?.spokenReply != null }
        assertNull(spoken.messages[1].spokenReply)
        assertEquals(1, speech.answers)
    }

    @Test
    fun toolRoundsStopAfterFourAndTheRestShowAsNotDone() = runTest(mainDispatcher) {
        val viewModel = newViewModel()
        val arguments = JsonObject(mapOf("seconds" to JsonPrimitive(60)))
        repeat(ChatResponder.MAX_TOOL_ROUNDS + 1) {
            fakeEngine.answer(ScriptedAnswer(toolCalls = listOf(ToolCall("set_timer", arguments))))
        }

        viewModel.send("Keep setting timers")
        val state = viewModel.awaitState { current ->
            val isLastShown = current.messages.size == 6 && current.messages.last().toolActions.isNotEmpty()
            !current.isGenerating && isLastShown
        }

        assertEquals(ChatResponder.MAX_TOOL_ROUNDS, tools.calls.size)
        val last = state.messages.last().toolActions.single()
        assertEquals("Not done", last.summary)
        assertTrue(!last.succeeded)
    }

    @Test
    fun anAppAToolOpensStartsOnceTheAnswerIsDone() = runTest(mainDispatcher) {
        val viewModel = newViewModel()
        val page = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
        tools.nextOutcome = FakeToolExecutor.success().copy(deferredLaunch = page)
        val arguments = JsonObject(emptyMap())
        val lastWordsHeldBack = CountDownLatch(1)
        fakeEngine.answer(ScriptedAnswer(text = "Opening it.", toolCalls = listOf(ToolCall("set_timer", arguments))))
        fakeEngine.answer(ScriptedAnswer(text = "It opens now.", pauseAfterFirstWord = lastWordsHeldBack))

        viewModel.send("Open example.com")

        // Curie is still answering: the page would cover the chat
        viewModel.awaitState { state -> state.messages.getOrNull(2)?.text == "It" }
        assertTrue(tools.startedLaunches.value.isEmpty())
        lastWordsHeldBack.countDown()

        viewModel.awaitAnswer("It opens now.")
        val started = tools.startedLaunches.first { launches -> launches.isNotEmpty() }
        assertEquals(listOf(page), started)
    }

    @Test
    fun anAppWaitsForTheSpokenReplyToEnd() = runTest(mainDispatcher) {
        val viewModel = newViewModel()
        speech.finishesByItself = false
        val music = Intent("android.media.action.MEDIA_PLAY_FROM_SEARCH")
        tools.nextOutcome = FakeToolExecutor.success().copy(deferredLaunch = music)
        val arguments = JsonObject(emptyMap())
        fakeEngine.answer(ScriptedAnswer(toolCalls = listOf(ToolCall("set_timer", arguments))))
        fakeEngine.answer(ScriptedAnswer(text = "Playing it.", audioFrames = 5))
        viewModel.addMedia(fixture.sourceUri("AUDIO 2000"))
        viewModel.awaitState { state -> state.composer.attachments.isNotEmpty() }

        viewModel.onAction(ChatAction.Send)
        viewModel.awaitState { state -> !state.isGenerating && state.messages.lastOrNull()?.spokenReply != null }
        runCurrent()
        val answered = viewModel.awaitState { state -> state.speakingMessageId != null }

        // Both rounds are spoken answers; only the last one had anything to say, and it is the one stored
        val modes = mainConversation().generations.map { (_, request) -> request.mode }
        assertEquals(listOf(GenerationMode.SPOKEN, GenerationMode.SPOKEN), modes)
        assertNull(answered.messages[1].spokenReply)

        // The music app would cut the reply off, which is still playing
        assertEquals(answered.messages[1].id, answered.speakingMessageId)
        assertTrue(tools.startedLaunches.value.isEmpty())

        clips.stop()
        val started = tools.startedLaunches.first { launches -> launches.isNotEmpty() }
        assertEquals(listOf(music), started)
    }

    @Test
    fun aWebLookupNobodyAskedForStaysALinkAndTheModelAnswers() = runTest(mainDispatcher) {
        val viewModel = newViewModel()
        val search = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=capital"))
        tools.nextOutcome = FakeToolExecutor.success().copy(deferredLaunch = search, isWebLookup = true)
        val arguments = JsonObject(mapOf("query" to JsonPrimitive("capital of France")))
        fakeEngine.answer(ScriptedAnswer(toolCalls = listOf(ToolCall("web_search", arguments))))
        fakeEngine.answer("The capital of France is Paris.")

        viewModel.send("What is the capital of France?")
        val answered = viewModel.awaitAnswer("The capital of France is Paris.")
        runCurrent()

        // Nothing opened, the model read so, and its next round could only answer
        assertTrue(tools.startedLaunches.value.isEmpty())
        val chatId = assertNotNull(answered.activeChatId)
        val toolMessage = fixture.repository.getThread(chatId).single { item -> item.message.role == StoredRole.TOOL }
        assertTrue(toolMessage.message.text.contains("not_opened"))
        val requests = mainConversation().generations.map { (_, request) -> request }
        assertEquals(listOf(true, false), requests.map { request -> request.allowToolCalls })

        // The lookup shows as a link that opens the search
        val link = answered.messages[1].toolActions.single()
        assertTrue(link.isLink)
        tools.webLink = search
        viewModel.onAction(ChatAction.OpenToolLink(answered.messages[1].id, 0))
        val started = tools.startedLaunches.first { launches -> launches.isNotEmpty() }
        assertEquals(listOf(search), started)
    }

    @Test
    fun aTypedRequestForTheWebOpensIt() = runTest(mainDispatcher) {
        val viewModel = newViewModel()
        val search = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=pizza"))
        tools.nextOutcome = FakeToolExecutor.success().copy(deferredLaunch = search, isWebLookup = true)
        val arguments = JsonObject(mapOf("query" to JsonPrimitive("best pizza in Brisbane")))
        fakeEngine.answer(ScriptedAnswer(toolCalls = listOf(ToolCall("web_search", arguments))))
        fakeEngine.answer("Here are the results.")

        viewModel.send("Search the web for the best pizza in Brisbane")
        val answered = viewModel.awaitAnswer("Here are the results.")

        val started = tools.startedLaunches.first { launches -> launches.isNotEmpty() }
        assertEquals(listOf(search), started)
        assertTrue(!answered.messages[1].toolActions.single().isLink)

        // It cannot read the results, so it answers next instead of searching again
        val requests = mainConversation().generations.map { (_, request) -> request }
        assertEquals(listOf(true, false), requests.map { request -> request.allowToolCalls })
    }

    @Test
    fun anAppAndroidKeepsClosedIsReported() = runTest(mainDispatcher) {
        val viewModel = newViewModel()
        val page = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
        tools.nextOutcome = FakeToolExecutor.success().copy(deferredLaunch = page)
        tools.canStartLaunches = false
        val arguments = JsonObject(emptyMap())
        fakeEngine.answer(ScriptedAnswer(text = "Opening it.", toolCalls = listOf(ToolCall("set_timer", arguments))))
        fakeEngine.answer("It opens now.")

        viewModel.send("Open example.com")
        val notice = viewModel.events.first()

        val expected = fixture.context.getString(R.string.apps_blocked_in_background)
        assertEquals(ChatEvent.Notice(expected), notice)
    }

    @Test
    fun regeneratingKeepsTheOldAnswerAsASibling() = runTest(mainDispatcher) {
        val viewModel = newViewModel()
        fakeEngine.answer("First answer")
        fakeEngine.answer("Second answer")
        viewModel.send("Tell me something")
        val first = viewModel.awaitAnswer("First answer")

        viewModel.onAction(ChatAction.Regenerate(first.messages[1].id))
        val second = viewModel.awaitAnswer("Second answer")
        assertEquals(1, second.messages[1].siblingIndex)
        assertEquals(2, second.messages[1].siblingCount)

        // The regenerated answer replies to the same question
        assertEquals("Tell me something", renderer.lastMessages.last().text())

        viewModel.onAction(ChatAction.ShowPreviousSibling(second.messages[1].id))
        viewModel.awaitState { state -> state.messages.getOrNull(1)?.text == "First answer" }
    }

    @Test
    fun editingAQuestionAsksItAgainAsASibling() = runTest(mainDispatcher) {
        val viewModel = newViewModel()
        fakeEngine.answer("Answer one")
        fakeEngine.answer("Answer two")
        viewModel.send("Hello")
        val first = viewModel.awaitAnswer("Answer one")

        viewModel.onAction(ChatAction.StartEditing(first.messages[0].id))
        viewModel.awaitState { state -> state.editingMessageId == first.messages[0].id }
        viewModel.onAction(ChatAction.ConfirmEdit(first.messages[0].id, "Hello again"))

        val edited = viewModel.awaitAnswer("Answer two")
        assertNull(edited.editingMessageId)
        assertEquals("Hello again", edited.messages[0].text)
        assertEquals(2, edited.messages[0].siblingCount)
        assertEquals("Hello again", renderer.lastMessages.last().text())
    }

    @Test
    fun theOldestTurnsAreLeftOutWhenTheChatOutgrowsTheContext() = runTest(mainDispatcher) {
        // Ten slots a character: each 100-character question costs about 1,000 positions
        renderer = FakePromptRenderer(slotsPerChar = 10)
        val viewModel = newViewModel()
        val questions = List(4) { index -> "Question $index ".padEnd(100, '.') }
        for ((index, question) in questions.withIndex()) {
            fakeEngine.answer("Answer $index")
            viewModel.send(question)
            viewModel.awaitAnswer("Answer $index")
        }

        val lastPrompt = renderer.lastMessages
        val promptTexts = lastPrompt.map { message -> message.text() }
        assertEquals(ChatRole.SYSTEM, lastPrompt.first().role)
        assertEquals(questions.last(), promptTexts.last())
        assertTrue(questions.first() !in promptTexts)

        // Every prompt left room for the answer
        for ((prompt, request) in mainConversation().generations) {
            assertTrue(prompt.length + request.maxNewSlots <= ConversationSlot.MAIN.capacity)
        }
    }

    @Test
    fun stoppingKeepsWhatWasSaid() = runTest(mainDispatcher) {
        val viewModel = newViewModel()
        fakeEngine.answer(ScriptedAnswer(text = "Once upon a time", waitsForCancel = true))

        viewModel.send("Tell me a long story")
        viewModel.awaitState { state -> state.messages.getOrNull(1)?.text == "Once" }
        viewModel.onAction(ChatAction.StopGenerating)

        val stopped = viewModel.awaitAnswer("Once")
        val answer = stopped.messages[1]
        assertEquals("Once", answer.text)
        assertNull(answer.error)
        val generated = fixture.repository.loadGeneratedContent(listOf(answer.id))
        assertTrue(answer.id in generated)
    }

    @Test
    fun aFailureIsShownOnTheAnswer() = runTest(mainDispatcher) {
        model.isReady = false
        val viewModel = newViewModel()

        viewModel.send("Hello?")
        val failed = viewModel.awaitState { state -> !state.isGenerating && state.messages.getOrNull(1)?.error != null }

        assertEquals(MODEL_UNAVAILABLE, failed.messages[1].error)
        assertEquals(MessageRole.ASSISTANT, failed.messages[1].role)
    }

    @Test
    fun aVoiceMessageIsAnsweredAloud() = runTest(mainDispatcher) {
        val viewModel = newViewModel()
        fakeEngine.answer(ScriptedAnswer(text = "I heard you.", audioFrames = 5))
        viewModel.addMedia(fixture.sourceUri("AUDIO 2000"))
        viewModel.awaitState { state -> state.composer.attachments.isNotEmpty() }

        viewModel.onAction(ChatAction.Send)
        val answered = viewModel.awaitAnswer("I heard you.")

        // The model speaks the answer itself: no second pass reads it aloud
        val (_, request) = mainConversation().generations.single()
        assertEquals(GenerationMode.SPOKEN, request.mode)
        val speechConversations = fakeEngine.conversations.filter { conversation ->
            conversation.capacity == ConversationSlot.SPEECH.capacity
        }
        assertTrue(speechConversations.isEmpty())
        assertEquals(0, speech.answers)

        // Its speech is stored with the answer and plays as a clip once the answer is complete
        val reply = answered.messages[1]
        assertEquals(5 * 80L, reply.spokenReply?.durationMillis)
        assertEquals(listOf(ChatViewModel.replyClipId(reply.id)), clips.played.map { (clipId, _) -> clipId })
    }

    @Test
    fun aSpokenAnswerThatOnlyWroteIsReadAloudInstead() = runTest(mainDispatcher) {
        val viewModel = newViewModel()
        // The model wrote its answer without speaking (as it does when it ends with a tool call)
        fakeEngine.answer("I heard you.")
        fakeEngine.answer(ScriptedAnswer(audioFrames = 5))
        viewModel.addMedia(fixture.sourceUri("AUDIO 2000"))
        viewModel.awaitState { state -> state.composer.attachments.isNotEmpty() }

        viewModel.onAction(ChatAction.Send)
        val answered = viewModel.awaitState { state -> state.messages.getOrNull(1)?.spokenReply != null }

        // Its text is read aloud with text to speech, and that speech is stored
        val (_, request) = mainConversation().generations.single()
        assertEquals(GenerationMode.SPOKEN, request.mode)
        val speechConversation = fakeEngine.conversations.single { conversation ->
            conversation.capacity == ConversationSlot.SPEECH.capacity
        }
        assertEquals(GenerationMode.SPEECH, speechConversation.generations.single().second.mode)
        assertEquals(1, speech.answers)
        assertEquals(5 * 80 * 24, speech.samplesWritten)
        assertEquals(SpeakableText.estimatedSeconds("I heard you."), speech.expectedSeconds)
        assertEquals(5 * 80L, answered.messages[1].spokenReply?.durationMillis)
        assertTrue(clips.played.isEmpty())
    }

    @Test
    fun theSpeakRepliesSettingDecidesWhetherAnAnswerIsSpoken() = runTest(mainDispatcher) {
        val viewModel = newViewModel()

        // Always: even a typed message is answered in speech
        settings.value = Settings(userName = "Dane", speakReplies = SpeakReplies.ALWAYS)
        fakeEngine.answer(ScriptedAnswer(text = "Hi.", audioFrames = 2))
        viewModel.send("Hello")
        viewModel.awaitAnswer("Hi.")
        assertEquals(GenerationMode.SPOKEN, mainConversation().generations.last().second.mode)

        // Never: not even a voice message is
        settings.value = Settings(userName = "Dane", speakReplies = SpeakReplies.NEVER)
        fakeEngine.answer("Yes?")
        viewModel.addMedia(fixture.sourceUri("AUDIO 2000"))
        viewModel.awaitState { state -> state.composer.attachments.isNotEmpty() }
        viewModel.onAction(ChatAction.Send)
        viewModel.awaitAnswer("Yes?")
        assertEquals(GenerationMode.TEXT, mainConversation().generations.last().second.mode)
    }

    @Test
    fun editingAnEarlyQuestionOfALongChatKeepsTheTurnsBeforeIt() = runTest(mainDispatcher) {
        // Ten slots a character: the chat outgrows the context and its oldest turns are cut
        renderer = FakePromptRenderer(slotsPerChar = 10)
        val viewModel = newViewModel()
        val questions = List(4) { index -> "Question $index ".padEnd(100, '.') }
        for ((index, question) in questions.withIndex()) {
            fakeEngine.answer("Answer $index")
            viewModel.send(question)
            viewModel.awaitAnswer("Answer $index")
        }
        assertTrue(questions.first() !in renderer.lastMessages.map { message -> message.text() })

        // The second question edited: the new branch fits, so nothing before it is left out
        val state = viewModel.awaitState { current -> current.messages.size == 8 }
        fakeEngine.answer("Edited answer")
        viewModel.onAction(ChatAction.ConfirmEdit(state.messages[2].id, "A short question"))
        viewModel.awaitAnswer("Edited answer")

        val prompt = renderer.lastMessages
        val roles = prompt.map { message -> message.role }
        assertEquals(listOf(ChatRole.SYSTEM, ChatRole.USER, ChatRole.ASSISTANT, ChatRole.USER), roles)
        assertEquals(questions[0], prompt[1].text())
        assertEquals("A short question", prompt[3].text())
    }

    @Test
    fun aStopRightAfterSendingStillStopsTheAnswer() = runTest(mainDispatcher) {
        val viewModel = newViewModel()
        fakeEngine.answer("This should never be said")

        // Nothing has run yet when the stop comes
        viewModel.send("Hello")
        viewModel.onAction(ChatAction.StopGenerating)

        val stopped = viewModel.awaitState { state -> !state.isGenerating && state.messages.size == 2 }
        assertEquals("", stopped.messages[1].text)
        val generations = fakeEngine.conversations.sumOf { conversation -> conversation.generations.size }
        assertEquals(0, generations)
    }

    @Test
    fun aReplyThatMayNotPlaySaysWhy() = runTest(mainDispatcher) {
        val viewModel = newViewModel()
        clips.refusesToPlay = true
        fakeEngine.answer(ScriptedAnswer(text = "I heard you.", audioFrames = 5))
        viewModel.addMedia(fixture.sourceUri("AUDIO 2000"))
        viewModel.awaitState { state -> state.composer.attachments.isNotEmpty() }

        viewModel.onAction(ChatAction.Send)
        val notice = viewModel.events.first()

        // Another app has the audio: the speech is kept for later, and it is not read aloud instead
        val expected = fixture.context.getString(R.string.audio_busy)
        assertEquals(ChatEvent.Notice(expected), notice)
        viewModel.awaitAnswer("I heard you.")
        viewModel.awaitState { state -> state.messages.getOrNull(1)?.spokenReply != null }
        assertEquals(GenerationMode.SPOKEN, mainConversation().generations.single().second.mode)
        assertEquals(0, speech.answers)
        assertTrue(fakeEngine.conversations.none { conversation -> conversation.capacity == ConversationSlot.SPEECH.capacity })
    }

    @Test
    fun readingAloudTellsThePlayerHowLongTheTextTakes() = runTest(mainDispatcher) {
        val viewModel = newViewModel()
        fakeEngine.answer("A written answer.")
        viewModel.send("Hello")
        val answered = viewModel.awaitAnswer("A written answer.")
        fakeEngine.answer(ScriptedAnswer(audioFrames = 2))

        viewModel.onAction(ChatAction.Speak(answered.messages[1].id))
        viewModel.awaitState { state -> state.messages[1].spokenReply != null }

        // So that slow speech starts late enough to play without pauses
        val expected = SpeakableText.estimatedSeconds("A written answer.")
        assertEquals(expected, speech.expectedSeconds)
    }

    @Test
    fun readingAloudWhileAnotherAppHasTheAudioSaysWhy() = runTest(mainDispatcher) {
        val viewModel = newViewModel()
        fakeEngine.answer("A written answer.")
        viewModel.send("Hello")
        val answered = viewModel.awaitAnswer("A written answer.")
        speech.refusesToPlay = true

        viewModel.onAction(ChatAction.Speak(answered.messages[1].id))
        val notice = viewModel.events.first()

        val expected = fixture.context.getString(R.string.audio_busy)
        assertEquals(ChatEvent.Notice(expected), notice)
        val speechConversations = fakeEngine.conversations.filter { conversation ->
            conversation.capacity == ConversationSlot.SPEECH.capacity
        }
        assertTrue(speechConversations.isEmpty())
    }

    private companion object {
        const val MODEL_UNAVAILABLE = "The model is not available."
    }
}
