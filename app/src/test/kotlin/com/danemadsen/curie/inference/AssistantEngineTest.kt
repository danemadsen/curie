package com.danemadsen.curie.inference

import android.content.ComponentCallbacks2
import com.danemadsen.curie.engine.Conversation
import com.danemadsen.curie.engine.Lfm3Omni
import com.danemadsen.curie.engine.OmniEngine
import com.danemadsen.curie.engine.chat.ChatMessage
import com.danemadsen.curie.engine.generation.FinishReason
import com.danemadsen.curie.engine.generation.GenerationListener
import com.danemadsen.curie.engine.generation.GenerationMode
import com.danemadsen.curie.engine.generation.GenerationRequest
import com.danemadsen.curie.engine.prompt.Prompt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AssistantEngineTest {
    private val model = FakeModelSource()
    private val renderer = FakePromptRenderer()
    private val fakeEngine = FakeOmniEngine()
    private var loads = 0

    private val factory = EngineFactory {
        loads++
        fakeEngine
    }

    private fun TestScope.newEngine(
        engineFactory: EngineFactory = factory,
        engineDispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
    ): Pair<AssistantEngine, CoroutineScope> {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val engine = AssistantEngine(
            model = model,
            engineFactory = engineFactory,
            rendererFactory = { renderer },
            scope = scope,
            idleUnloadDelayMs = IDLE_MS,
            engineDispatcher = engineDispatcher,
            speechDispatcher = StandardTestDispatcher(testScheduler),
        )
        return engine to scope
    }

    private val question = listOf(ChatMessage.system("You are Curie."), ChatMessage.user("Hello?"))
    private val textRequest = GenerationRequest(mode = GenerationMode.TEXT, maxNewSlots = 512)
    private val silent = object : GenerationListener {}

    @Test
    fun generationsShareOneLoad() = runTest {
        val (engine, scope) = newEngine()
        fakeEngine.answer("One")
        fakeEngine.answer("Two")

        val first = async { engine.generate(ConversationSlot.MAIN, question, emptyList(), textRequest, silent) }
        val second = async { engine.generate(ConversationSlot.MAIN, question, emptyList(), textRequest, silent) }
        val texts = listOf(first, second).awaitAll().map { result -> result.text }

        assertEquals(setOf("One", "Two"), texts.toSet())
        assertEquals(1, loads)
        assertEquals(EngineState.Ready, engine.state.value)
        scope.cancel()
    }

    @Test
    fun aModelThatIsNotDownloadedDoesNotLoad() = runTest {
        val (engine, scope) = newEngine()
        model.isReady = false

        assertFailsWith<EngineUnavailableException> {
            engine.generate(ConversationSlot.MAIN, question, emptyList(), textRequest, silent)
        }
        assertEquals(EngineState.Unloaded, engine.state.value)
        assertEquals(0, loads)
        scope.cancel()
    }

    @Test
    fun aFailedLoadIsReportedAndRetriedOnTheNextRequest() = runTest {
        var attempts = 0
        val failingOnce = EngineFactory {
            attempts++
            if (attempts == 1) throw IllegalStateException("no memory") else fakeEngine
        }
        val (engine, scope) = newEngine(engineFactory = failingOnce)

        assertFailsWith<EngineUnavailableException> { engine.load() }
        assertEquals(EngineState.Failed("no memory"), engine.state.value)

        engine.load()
        assertEquals(EngineState.Ready, engine.state.value)
        assertEquals(2, attempts)
        scope.cancel()
    }

    @Test
    fun noMemoryForACacheIsAFailureNotACrash() = runTest {
        val noMemory = object : OmniEngine by fakeEngine {
            override fun newConversation(capacity: Int) = throw OutOfMemoryError("no room for the cache")
        }
        val (engine, scope) = newEngine(engineFactory = { noMemory })

        assertFailsWith<EngineUnavailableException> {
            engine.generate(ConversationSlot.MAIN, question, emptyList(), textRequest, silent)
        }
        assertEquals(EngineState.Failed("not enough memory for the model"), engine.state.value)
        assertTrue(fakeEngine.isClosed)
        scope.cancel()
    }

    @Test
    fun theSystemPromptIsWarmedUpAfterLoading() = runTest {
        val (engine, scope) = newEngine()
        val system = listOf(ChatMessage.system("You are Curie."))
        engine.chatWarmUpPrompt = { AssistantEngine.WarmUpPrompt(system, emptyList()) }

        engine.load()
        advanceUntilIdle()

        val conversation = fakeEngine.conversations.single()
        assertEquals(1, conversation.prefills.size)
        assertEquals(ConversationSlot.MAIN.capacity, conversation.capacity)
        scope.cancel()
    }

    @Test
    fun aWarmUpThatTheCacheAlreadyStartsWithDoesNothing() = runTest {
        val (engine, scope) = newEngine()
        fakeEngine.answer("Hi")
        engine.generate(ConversationSlot.MAIN, question, emptyList(), textRequest, silent)

        val sameSystem = AssistantEngine.WarmUpPrompt(question.take(1), emptyList())
        engine.warmUp(ConversationSlot.MAIN, sameSystem)
        val conversation = fakeEngine.conversations.single()
        assertEquals(0, conversation.prefills.size)

        // A call's system prompt differs: it is prefilled
        val otherSystem = AssistantEngine.WarmUpPrompt(listOf(ChatMessage.system("You are on a call.")), emptyList())
        engine.warmUp(ConversationSlot.MAIN, otherSystem)
        assertEquals(1, conversation.prefills.size)
        scope.cancel()
    }

    @Test
    fun speechFramesAreDecodedAsTheyCome() = runTest {
        val (engine, scope) = newEngine()
        fakeEngine.answer(ScriptedAnswer(text = "Hello there", audioFrames = 3))
        var samples = 0
        val sink = SpeechSink { chunk -> samples += chunk.size }
        val request = GenerationRequest(mode = GenerationMode.AUTO, maxNewSlots = 512)

        val result = engine.generate(ConversationSlot.MAIN, question, emptyList(), request, silent, sink)

        assertEquals(3, result.audioFrames.size)
        assertEquals(3 * Lfm3Omni.FRAME_SAMPLES, samples)
        scope.cancel()
    }

    @Test
    fun cancelEndsTheRunningGenerationWithWhatItSaid() = runTest {
        val executor = Executors.newSingleThreadExecutor()
        val engineThread = executor.asCoroutineDispatcher()
        val (engine, scope) = newEngine(engineDispatcher = engineThread)
        fakeEngine.answer(ScriptedAnswer(text = "I was saying something long", waitsForCancel = true))
        val streamed = StringBuilder()
        val listener = object : GenerationListener {
            override fun onText(delta: String) {
                synchronized(streamed) { streamed.append(delta) }
                engine.cancel()
            }
        }

        val result = withContext(Dispatchers.Default) {
            engine.generate(ConversationSlot.MAIN, question, emptyList(), textRequest, listener)
        }

        assertEquals(FinishReason.CANCELLED, result.finishReason)
        assertEquals("I", result.text)
        scope.cancel()
        executor.shutdown()
    }

    @Test
    fun theModelUnloadsAfterBeingIdleInTheBackgroundUnlessHeld() = runTest {
        val (engine, scope) = newEngine()
        engine.load()
        val hold = engine.hold()

        engine.onAppVisibilityChanged(visible = false)
        advanceTimeBy(IDLE_MS * 2)
        assertEquals(EngineState.Ready, engine.state.value)

        hold.close()
        advanceTimeBy(IDLE_MS + 1)
        advanceUntilIdle()
        assertEquals(EngineState.Unloaded, engine.state.value)
        assertTrue(fakeEngine.isClosed)

        // Shown again, it loads back what the idle time freed
        engine.onAppVisibilityChanged(visible = true)
        advanceUntilIdle()
        assertEquals(EngineState.Ready, engine.state.value)
        assertEquals(2, loads)
        scope.cancel()
    }

    @Test
    fun unloadingWaitsForALoadUnderWay() = runTest {
        val (engine, scope) = newEngine()
        engine.requestLoad()

        // The load has not run yet: unloading must not let it finish afterwards
        engine.unload()

        assertEquals(EngineState.Unloaded, engine.state.value)
        assertTrue(fakeEngine.isClosed)
        scope.cancel()
    }

    @Test
    fun criticalMemoryFreesTheModel() = runTest {
        val (engine, scope) = newEngine()
        engine.load()

        @Suppress("DEPRECATION")
        engine.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        advanceUntilIdle()
        assertEquals(EngineState.Ready, engine.state.value)

        // On screen the user is about to need it: the system frees background apps first
        @Suppress("DEPRECATION")
        engine.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        advanceUntilIdle()
        assertEquals(EngineState.Ready, engine.state.value)

        @Suppress("DEPRECATION")
        engine.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
        advanceUntilIdle()
        assertEquals(EngineState.Unloaded, engine.state.value)
        scope.cancel()
    }

    @Test
    fun readingAloudUsesTheSpeechConversationPieceByPiece() = runTest {
        val (engine, scope) = newEngine()
        val sentence = "This sentence is about fifty characters long, roughly. "
        val text = sentence.repeat(20)
        repeat(5) { fakeEngine.answer(ScriptedAnswer(audioFrames = 2)) }
        var samples = 0

        val isRead = engine.synthesize(text) { chunk -> samples += chunk.size }

        assertTrue(isRead)
        val speech = fakeEngine.conversations.single { conversation -> conversation.capacity == ConversationSlot.SPEECH.capacity }
        assertTrue(speech.generations.size > 1)
        for ((prompt, request) in speech.generations) {
            assertEquals(GenerationMode.SPEECH, request.mode)
            assertTrue(prompt.length + request.maxNewSlots <= ConversationSlot.SPEECH.capacity)
        }
        assertEquals(speech.generations.size * 2 * Lfm3Omni.FRAME_SAMPLES, samples)
        assertEquals(SystemPrompts.TEXT_TO_SPEECH, renderer.lastMessages.first().text())
        scope.cancel()
    }

    @Test
    fun aCallKeepsTheChatsWarmUpOutOfItsCache() = runTest {
        val (engine, scope) = newEngine()
        val chatSystem = listOf(ChatMessage.system("You are Curie, in a chat."))
        val callSystem = AssistantEngine.WarmUpPrompt(listOf(ChatMessage.system("You are on a call.")), emptyList())
        engine.chatWarmUpPrompt = { AssistantEngine.WarmUpPrompt(chatSystem, emptyList()) }

        // A call from cold: it holds the model, loads it and warms up its own prompt only
        val hold = engine.hold()
        engine.load()
        engine.warmUp(ConversationSlot.MAIN, callSystem)
        advanceUntilIdle()
        val conversation = fakeEngine.conversations.single()
        assertEquals(1, conversation.prefills.size)

        // The chat screen shown during the call leaves the call's cache alone
        engine.requestChatWarmUp()
        advanceUntilIdle()
        assertEquals(1, conversation.prefills.size)

        // Once the call is over, the chat's prompt is warmed up again
        hold.close()
        engine.requestChatWarmUp()
        advanceUntilIdle()
        assertEquals(2, conversation.prefills.size)
        scope.cancel()
    }

    @Test
    fun aFailedChatWarmUpIsOnlyLogged() = runTest {
        val escaped = ArrayList<Throwable>()
        val handler = CoroutineExceptionHandler { _, error -> escaped.add(error) }
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler) + handler)
        val failingPrefill = object : OmniEngine by fakeEngine {
            override fun newConversation(capacity: Int): Conversation {
                val conversation = fakeEngine.newConversation(capacity)
                return object : Conversation by conversation {
                    override fun prefill(prompt: Prompt, listener: GenerationListener): Boolean =
                        throw IllegalStateException("the decoder run failed")
                }
            }
        }
        val engine = AssistantEngine(
            model = model,
            engineFactory = { failingPrefill },
            rendererFactory = { renderer },
            scope = scope,
            engineDispatcher = StandardTestDispatcher(testScheduler),
            speechDispatcher = StandardTestDispatcher(testScheduler),
        )
        engine.chatWarmUpPrompt = { AssistantEngine.WarmUpPrompt(question.take(1), emptyList()) }

        engine.requestChatWarmUp()
        advanceUntilIdle()

        assertTrue(escaped.isEmpty())
        assertEquals(EngineState.Ready, engine.state.value)
        scope.cancel()
    }

    @Test
    fun aWarmUpThatWasCancelledIsNotTakenForCached() = runTest {
        val (engine, scope) = newEngine()
        // Shorter than the system prompt (the fake renderer only tells prompts apart by their lengths)
        val firstPrompt = AssistantEngine.WarmUpPrompt(listOf(ChatMessage.system("Hi.")), emptyList())
        val system = AssistantEngine.WarmUpPrompt(question.take(1), emptyList())
        engine.warmUp(ConversationSlot.MAIN, firstPrompt)
        val conversation = fakeEngine.conversations.single()

        // Stopped early, then asked again: the second warm-up does the work the first could not finish
        conversation.nextPrefillIsCancelled = true
        engine.warmUp(ConversationSlot.MAIN, system)
        engine.warmUp(ConversationSlot.MAIN, system)
        assertEquals(3, conversation.prefills.size)

        // Now it is cached
        engine.warmUp(ConversationSlot.MAIN, system)
        assertEquals(3, conversation.prefills.size)
        scope.cancel()
    }

    @Test
    fun anOwnerStopsItsOwnGeneration() = runTest {
        val executor = Executors.newSingleThreadExecutor()
        val engineThread = executor.asCoroutineDispatcher()
        val (engine, scope) = newEngine(engineDispatcher = engineThread)
        fakeEngine.answer(ScriptedAnswer(text = "A long answer", waitsForCancel = true))
        val owner = GenerationOwner()
        val listener = object : GenerationListener {
            override fun onText(delta: String) {
                engine.cancel(owner)
            }
        }

        val result = withContext(Dispatchers.Default) {
            engine.generate(ConversationSlot.MAIN, question, emptyList(), textRequest, listener, owner = owner)
        }

        assertEquals(FinishReason.CANCELLED, result.finishReason)
        assertEquals("A", result.text)
        scope.cancel()
        executor.shutdown()
    }

    @Test
    fun anOwnersStopLeavesAnotherOwnersGenerationRunning() = runTest {
        val executor = Executors.newSingleThreadExecutor()
        val engineThread = executor.asCoroutineDispatcher()
        val (engine, scope) = newEngine(engineDispatcher = engineThread)
        val firstWord = CountDownLatch(1)
        fakeEngine.answer(ScriptedAnswer(text = "The call goes on", pauseAfterFirstWord = firstWord))
        val chatOwner = GenerationOwner()
        val listener = object : GenerationListener {
            override fun onText(delta: String) {
                engine.cancel(chatOwner)
                firstWord.countDown()
            }
        }

        val callOwner = GenerationOwner()
        val result = withContext(Dispatchers.Default) {
            engine.generate(ConversationSlot.MAIN, question, emptyList(), textRequest, listener, owner = callOwner)
        }

        assertEquals(FinishReason.END_OF_TURN, result.finishReason)
        assertEquals("The call goes on", result.text)
        scope.cancel()
        executor.shutdown()
    }

    @Test
    fun anAnswerThatRunsOutOfMemoryFailsAloneAndItsCacheIsForgotten() = runTest {
        val (engine, scope) = newEngine()
        fakeEngine.answer(ScriptedAnswer(failure = OutOfMemoryError("a long clip")))
        fakeEngine.answer("Fine again")

        assertFailsWith<NotEnoughMemoryException> {
            engine.generate(ConversationSlot.MAIN, question, emptyList(), textRequest, silent)
        }

        // The model stays loaded and answers the next message
        assertEquals(EngineState.Ready, engine.state.value)
        assertEquals(1, fakeEngine.conversations.single().resets)
        val next = engine.generate(ConversationSlot.MAIN, question, emptyList(), textRequest, silent)
        assertEquals("Fine again", next.text)
        scope.cancel()
    }

    @Test
    fun anIdleUnloadCutShortByTheAppShowingStillFinishesAndLoadsAgain() = runTest {
        val executor = Executors.newSingleThreadExecutor()
        val engineThread = executor.asCoroutineDispatcher()
        val closing = CountDownLatch(1)
        val mayClose = CountDownLatch(1)
        val slowToClose = object : OmniEngine by fakeEngine {
            override fun close() {
                closing.countDown()
                mayClose.await(WAIT_SECONDS, TimeUnit.SECONDS)
                fakeEngine.close()
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = AssistantEngine(
            model = model,
            engineFactory = {
                loads++
                slowToClose
            },
            rendererFactory = { renderer },
            scope = scope,
            idleUnloadDelayMs = 10,
            engineDispatcher = engineThread,
            speechDispatcher = engineThread,
        )

        withContext(Dispatchers.Default) {
            engine.load()
            engine.onAppVisibilityChanged(visible = false)
            assertTrue(closing.await(WAIT_SECONDS, TimeUnit.SECONDS))

            // Shown while the model is being freed: the idle unload is cancelled half-way
            engine.onAppVisibilityChanged(visible = true)
            mayClose.countDown()

            // It still finishes (the model is not left marked Ready but gone), then loads again
            withTimeout(WAIT_SECONDS * 1_000) {
                engine.state.first { state -> state == EngineState.Ready && loads == 2 }
            }
        }
        assertTrue(fakeEngine.isClosed)
        scope.cancel()
        executor.shutdown()
    }

    private companion object {
        const val IDLE_MS = 60_000L
        const val WAIT_SECONDS = 5L
    }
}
