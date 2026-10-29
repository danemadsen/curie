package com.danemadsen.curie.engine.onnx

import com.danemadsen.curie.engine.ContextOverflowException
import com.danemadsen.curie.engine.Lfm3Omni
import com.danemadsen.curie.engine.chat.ChatMessage
import com.danemadsen.curie.engine.chat.ChatRole
import com.danemadsen.curie.engine.chat.ChatTemplate
import com.danemadsen.curie.engine.chat.ContentPart
import com.danemadsen.curie.engine.chat.ToolDefinition
import com.danemadsen.curie.engine.generation.AudioSampling
import com.danemadsen.curie.engine.generation.FinishReason
import com.danemadsen.curie.engine.generation.GenerationListener
import com.danemadsen.curie.engine.generation.GenerationMode
import com.danemadsen.curie.engine.generation.GenerationRequest
import com.danemadsen.curie.engine.generation.GenerationResult
import com.danemadsen.curie.engine.generation.MaskGitOrders
import com.danemadsen.curie.engine.generation.TextSampling
import com.danemadsen.curie.engine.media.RgbImage
import com.danemadsen.curie.engine.prompt.FeatureStream
import com.danemadsen.curie.engine.prompt.Prompt
import com.danemadsen.curie.engine.prompt.VideoInput
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.util.SplittableRandom
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A conversation's cache across turns on the real model (base model, greedy): prefix reuse,
 * rewinds to snapshots, cancellation and capacity. Skipped without CURIE_MODEL_DIR.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OnnxConversationTest {
    private lateinit var engine: OnnxEngine

    private val tokenizer
        get() = engine.resources.tokenizer

    private val text = GenerationRequest(GenerationMode.TEXT, maxNewSlots = 24)

    private val longSpeechMessages = listOf(
        ChatMessage.system("Perform TTS in American English using a female voice."),
        ChatMessage.user("Hello there. This is a fairly long sentence that the model should speak for a while."),
    )

    private val longSpeech = GenerationRequest(
        GenerationMode.SPEECH,
        maxNewSlots = 60,
        audioSampling = AudioSampling(temperature = 0f),
    )

    @BeforeAll
    fun loadEngine() {
        engine = ModelFixture.loadEngine(useLora = false)
    }

    @AfterAll
    fun closeEngine() {
        if (this::engine.isInitialized) {
            engine.close()
        }
    }

    private fun render(messages: List<ChatMessage>, tools: List<ToolDefinition> = emptyList()): Prompt =
        ChatTemplate.render(messages, tools, addGenerationPrompt = true, tokenizer)

    private fun answered(message: GenerationResult) = ChatMessage(ChatRole.ASSISTANT, generated = message.content)

    private fun freshAnswer(
        prompt: Prompt,
        request: GenerationRequest = text,
        listener: RecordingListener = RecordingListener(),
    ): GenerationResult =
        engine.newConversation(prompt.length + 1 + request.maxNewSlots).use { conversation ->
            conversation.generate(prompt, request, listener)
        }

    @Test
    fun aNewTurnFeedsOnlyItsSuffixAndAnswersAsAFreshConversation() {
        val firstUser = ChatMessage.user("My name is Ada. Please remember it.")
        val firstPrompt = render(listOf(firstUser))

        engine.newConversation(512).use { conversation ->
            val first = conversation.generate(firstPrompt, text, RecordingListener())
            assertEquals(FinishReason.END_OF_TURN, first.finishReason)
            // The cache holds the prompt and every slot of the answer but the final <|im_end|>
            assertEquals(firstPrompt.length + first.content.tokens.size, conversation.cachedLength)

            val secondPrompt = render(listOf(firstUser, answered(first), ChatMessage.user("What is my name?")))
            val cachedBefore = conversation.cachedLength
            val listener = RecordingListener()
            val second = conversation.generate(secondPrompt, text, listener)

            assertEquals(secondPrompt.length - cachedBefore, listener.prefillTotal)
            val fresh = freshAnswer(secondPrompt)
            assertEquals(fresh.content.tokens.toList(), second.content.tokens.toList())
            println("turn 2 fed ${listener.prefillTotal} of ${secondPrompt.length} positions: ${second.text}")
        }
    }

    @Test
    fun regeneratingAnAnswerStartsFromTheEndOfItsPrompt() {
        val prompt = render(listOf(ChatMessage.user("Name three colors of the rainbow.")))

        engine.newConversation(256).use { conversation ->
            val first = conversation.generate(prompt, text, RecordingListener())
            val listener = RecordingListener()
            val again = conversation.generate(prompt, text, listener)

            // Nothing is fed: the end-of-prompt snapshot holds the state and the outputs
            assertEquals(0, listener.prefillTotal)
            assertEquals(first.content.tokens.toList(), again.content.tokens.toList())
            assertEquals(first.text, again.text)
        }
    }

    @Test
    fun editingTheLastMessageRewindsToItsStart() {
        val system = ChatMessage.system("You are a helpful assistant.")
        val original = render(listOf(system, ChatMessage.user("What is the capital of Italy?")))
        val edited = render(listOf(system, ChatMessage.user("What is the capital of Spain?")))
        val userStart = edited.checkpoints[1]
        assertEquals(original.checkpoints[1], userStart)

        engine.newConversation(256).use { conversation ->
            conversation.generate(original, text, RecordingListener())
            val listener = RecordingListener()
            val answer = conversation.generate(edited, text, listener)

            assertEquals(edited.length - userStart, listener.prefillTotal)
            assertEquals(freshAnswer(edited).content.tokens.toList(), answer.content.tokens.toList())
            println("edited question: ${answer.text}")
        }
    }

    @Test
    fun aToolResponseAfterAToolCallAppendsOnly() {
        val golden = EngineGolden.generation("tool-call")
        val tools = EngineGolden.tools(golden)
        val user = ChatMessage.user("What is the weather like in Paris right now?")
        val request = GenerationRequest(GenerationMode.TEXT, maxNewSlots = 64)

        engine.newConversation(512).use { conversation ->
            val call = conversation.generate(render(listOf(user), tools), request, RecordingListener())
            assertEquals(listOf("get_weather"), call.toolCalls.map { toolCall -> toolCall.name })

            val response = ChatMessage.tool("""{"temperature_celsius": 18, "condition": "sunny"}""")
            val prompt = render(listOf(user, answered(call), response), tools)
            val cachedBefore = conversation.cachedLength
            val listener = RecordingListener()
            val answer = conversation.generate(prompt, request, listener)

            assertEquals(prompt.length - cachedBefore, listener.prefillTotal)
            assertEquals(FinishReason.END_OF_TURN, answer.finishReason)
            assertTrue(answer.text.isNotBlank())
            println("after the tool response: ${answer.text}")
        }
    }

    @Test
    fun withoutToolCallsTheSameQuestionIsAnsweredInText() {
        val golden = EngineGolden.generation("tool-call")
        val tools = EngineGolden.tools(golden)
        val user = ChatMessage.user("What is the weather like in Paris right now?")
        val prompt = render(listOf(user), tools)
        val withTools = GenerationRequest(GenerationMode.TEXT, maxNewSlots = 64)
        val withoutTools = withTools.copy(allowToolCalls = false)

        // The question that calls get_weather above gets a written answer when calls are off
        assertEquals(listOf("get_weather"), freshAnswer(prompt, withTools).toolCalls.map { call -> call.name })
        val answer = freshAnswer(prompt, withoutTools)

        assertTrue(answer.toolCalls.isEmpty())
        assertFalse(answer.content.tokens.contains(engine.resources.markup.toolCallStart))
        assertTrue(answer.text.isNotBlank())
        println("without tool calls: ${answer.text}")
    }

    @Test
    fun aPrefilledSystemPromptIsKeptByTheFirstGeneration() {
        val system = ChatMessage.system("You are Curie, a friendly assistant. Keep answers short.")
        val warmUp = ChatTemplate.render(listOf(system), emptyList(), addGenerationPrompt = false, tokenizer)
        val prompt = render(listOf(system, ChatMessage.user("Say hello.")))

        engine.newConversation(256).use { conversation ->
            assertTrue(conversation.prefill(warmUp, RecordingListener()))
            assertEquals(warmUp.length, conversation.cachedLength)

            val listener = RecordingListener()
            val answer = conversation.generate(prompt, text, listener)

            assertEquals(prompt.length - warmUp.length, listener.prefillTotal)
            assertEquals(freshAnswer(prompt).content.tokens.toList(), answer.content.tokens.toList())
        }
    }

    @Test
    fun aCutSpeechAnswerReplaysAsTheCacheHoldsIt() {
        val messages = listOf(
            ChatMessage.system("Perform TTS in American English using a female voice."),
            ChatMessage.user("Good morning! It is a lovely day."),
        )
        val prompt = render(messages)
        val sampling = AudioSampling(temperature = 0f)
        val speech = GenerationRequest(GenerationMode.SPEECH, maxNewSlots = 6, audioSampling = sampling)

        engine.newConversation(256).use { conversation ->
            val answer = conversation.generate(prompt, speech, RecordingListener())
            assertEquals(FinishReason.MAX_SLOTS, answer.finishReason)
            assertEquals(6, answer.audioFrames.size)

            // The content: <|audio_start|>, the six frames and the closing end-of-speech frame; the
            // cache: the marker and the five frames that were fed
            assertEquals(1 + 6 + 1, answer.content.tokens.size)
            assertEquals(prompt.length + 1 + 5, conversation.cachedLength)

            val next = render(messages + answered(answer) + ChatMessage.user("Thanks."))
            val cachedBefore = conversation.cachedLength
            val listener = RecordingListener()
            conversation.generate(next, text, listener)
            assertEquals(next.length - cachedBefore, listener.prefillTotal)
        }
    }

    @Test
    fun aWholeSpeechAnswerReplaysAsTheCacheHoldsIt() {
        val messages = listOf(
            ChatMessage.system("Perform TTS in American English using a female voice."),
            ChatMessage.user("Hello! My name is Curie."),
        )
        val prompt = render(messages)
        val sampling = AudioSampling(temperature = 0f)
        val speech = GenerationRequest(GenerationMode.SPEECH, maxNewSlots = 100, audioSampling = sampling)

        engine.newConversation(512).use { conversation ->
            // The golden case "speech-to-the-end": the answer ends its turn after 38 slots
            val onnxConversation = conversation as OnnxConversation
            val random = SplittableRandom(0)
            val answer = onnxConversation.generate(prompt, speech, RecordingListener(), random, MaskGitOrders.IDENTITY)
            assertEquals(FinishReason.END_OF_TURN, answer.finishReason)

            // The content ends with the end-of-speech frame; the cache holds all of it, that frame
            // included (only the final <|im_end|> was not fed)
            val endOfSpeech = List(Lfm3Omni.NUM_CODEBOOKS) { Lfm3Omni.AUDIO_EOS_CODE }
            assertEquals(endOfSpeech, answer.content.audioCodes.takeLast(Lfm3Omni.NUM_CODEBOOKS))
            assertEquals(prompt.length + answer.content.tokens.size, conversation.cachedLength)

            val next = render(messages + answered(answer) + ChatMessage.user("Thanks."))
            val cachedBefore = conversation.cachedLength
            val listener = RecordingListener()
            conversation.generate(next, text, listener)
            assertEquals(next.length - cachedBefore, listener.prefillTotal)
        }
    }

    @Test
    fun aCancelledGenerationLeavesAConsistentCache() {
        val story = render(listOf(ChatMessage.user("Tell me a long story about a lighthouse keeper.")))
        val long = GenerationRequest(GenerationMode.TEXT, maxNewSlots = 200)

        engine.newConversation(512).use { conversation ->
            var cancelled = false
            val listener = RecordingListener { recorded ->
                if (!cancelled && recorded.text.length > 20) {
                    cancelled = true
                    // From another thread, as an app's stop button would
                    val stopper = Thread { conversation.cancel() }
                    stopper.start()
                    stopper.join()
                }
            }
            val result = conversation.generate(story, long, listener)

            assertEquals(FinishReason.CANCELLED, result.finishReason)
            // Every slot but the last sampled one is in the cache
            assertEquals(story.length + result.content.tokens.size - 1, conversation.cachedLength)

            // The next turn appends to it and answers as a fresh conversation would
            val next = render(
                listOf(
                    ChatMessage.user("Tell me a long story about a lighthouse keeper."),
                    answered(result),
                    ChatMessage.user("Thanks, that is enough."),
                ),
            )
            val cachedBefore = conversation.cachedLength
            val nextListener = RecordingListener()
            val answer = conversation.generate(next, text, nextListener)
            assertEquals(next.length - cachedBefore, nextListener.prefillTotal)
            assertEquals(freshAnswer(next).content.tokens.toList(), answer.content.tokens.toList())
        }
    }

    @Test
    fun aCancelDuringThePrefillStopsBetweenRuns() {
        val messages = listOf(
            ChatMessage.system("You are a helpful assistant."),
            ChatMessage.user("Count from one to five."),
        )
        val prompt = render(messages)

        engine.newConversation(256).use { conversation ->
            val listener = RecordingListener()
            val stopping = object : GenerationListener by listener {
                override fun onPrefill(done: Int, total: Int) {
                    listener.onPrefill(done, total)
                    if (done > 0) {
                        conversation.cancel()
                    }
                }
            }
            val result = conversation.generate(prompt, text, stopping)

            assertEquals(FinishReason.CANCELLED, result.finishReason)
            assertEquals(0, result.content.tokens.size)
            // The first run (up to the system message) is in the cache, nothing after it
            assertEquals(prompt.checkpoints[0], conversation.cachedLength)

            // The next generation goes on from there and answers as a fresh conversation
            val answer = conversation.generate(prompt, text, RecordingListener())
            assertEquals(freshAnswer(prompt).content.tokens.toList(), answer.content.tokens.toList())
        }
    }

    @Test
    fun aCancelTerminatesALongRunAndTheCacheStaysUsable() {
        // A long user message: after the one-position first run comes a run of 256 positions, which
        // takes far longer than the 100 ms after which the cancel lands in it
        val longText = "The quick brown fox jumps over the lazy dog. ".repeat(80)
        val prompt = render(listOf(ChatMessage.user(longText + "How many foxes were there?")))
        val runs = PrefillChunks.of(0, prompt.length, prompt.checkpoints)
        val firstRun = runs[0]
        assertEquals(PrefillChunks.MAX_CHUNK, runs[1].size)

        engine.newConversation(2048).use { conversation ->
            var stopper: Thread? = null
            var cancelledAt = 0L
            val listener = object : GenerationListener {
                override fun onPrefill(done: Int, total: Int) {
                    if (done == firstRun.end && stopper == null) {
                        // The second run starts as this returns
                        val thread = Thread {
                            Thread.sleep(100)
                            cancelledAt = System.nanoTime()
                            conversation.cancel()
                        }
                        thread.start()
                        stopper = thread
                    }
                }
            }
            val result = conversation.generate(prompt, text, listener)
            val returnedAt = System.nanoTime()
            stopper?.join()

            assertEquals(FinishReason.CANCELLED, result.finishReason)
            // Terminated: the long run left nothing in the cache (completed, it would hold 257)
            assertEquals(firstRun.end, conversation.cachedLength)
            val latencyMillis = (returnedAt - cancelledAt) / 1_000_000
            println("a run of ${runs[1].size} positions terminated $latencyMillis ms after the cancel")

            val answer = conversation.generate(prompt, text, RecordingListener())
            assertEquals(freshAnswer(prompt).content.tokens.toList(), answer.content.tokens.toList())
        }
    }

    @Test
    fun aCancelAlsoStopsTheCallsWaitingForTheRunningOne() {
        val system = ChatMessage.system("You are Curie, a friendly assistant. Keep answers short.")
        val warmUp = ChatTemplate.render(listOf(system), emptyList(), addGenerationPrompt = false, tokenizer)
        val prompt = render(listOf(system, ChatMessage.user("Say hello.")))
        val firstRun = PrefillChunks.of(0, warmUp.length, warmUp.checkpoints)[0]
        assertTrue(firstRun.end < warmUp.length)

        engine.newConversation(256).use { conversation ->
            var queued: Thread? = null
            var queuedResult: GenerationResult? = null
            val warmUpListener = object : GenerationListener {
                override fun onPrefill(done: Int, total: Int) {
                    if (done == 0 || queued != null) {
                        return
                    }
                    // A message sent during the warm-up waits for it...
                    val thread = Thread {
                        queuedResult = conversation.generate(prompt, text, RecordingListener())
                    }
                    thread.start()
                    TestThreads.awaitWaiting(thread)
                    queued = thread
                    // ...and the stop button stops both
                    conversation.cancel()
                }
            }

            val warmedUp = conversation.prefill(warmUp, warmUpListener)
            queued?.join()

            assertFalse(warmedUp)
            val stopped = checkNotNull(queuedResult) { "the queued generation did not return" }
            assertEquals(FinishReason.CANCELLED, stopped.finishReason)
            assertEquals(0, stopped.content.tokens.size)
            // The stopped generation fed nothing: the cache holds the warm-up's first run
            assertEquals(firstRun.end, conversation.cachedLength)

            // A call made after the cancel runs to the end
            val answer = conversation.generate(prompt, text, RecordingListener())
            assertEquals(FinishReason.END_OF_TURN, answer.finishReason)
            assertEquals(freshAnswer(prompt).content.tokens.toList(), answer.content.tokens.toList())
        }
    }

    @Test
    fun closingFromAnotherThreadWhileSpeakingStopsTheRunBeforeFreeingIt() {
        val prompt = render(longSpeechMessages)
        val conversation = engine.newConversation(256)
        var closer: Thread? = null
        var frames = 0
        val listener = object : GenerationListener {
            override fun onAudioFrame(codes: IntArray) {
                frames++
                if (frames == 2) {
                    // As a screen that goes away would; the closer waits for this run to return
                    val thread = Thread { conversation.close() }
                    thread.start()
                    TestThreads.awaitWaiting(thread)
                    closer = thread
                }
            }
        }

        val result = conversation.generate(prompt, longSpeech, listener)
        closer?.join()

        assertEquals(FinishReason.CANCELLED, result.finishReason)
        assertEquals(2, frames)
        assertThrows<IllegalStateException> { conversation.generate(prompt, longSpeech, RecordingListener()) }
        // Closing again does nothing
        conversation.close()
    }

    @Test
    fun closingAtAnyMomentOfAGenerationIsSafe() {
        val prompt = render(longSpeechMessages)

        // From before the run starts, through the prefill, into the speech
        for (delayMillis in listOf(0L, 20L, 150L, 400L, 900L)) {
            val conversation = engine.newConversation(256)
            val closer = Thread {
                Thread.sleep(delayMillis)
                conversation.close()
            }
            closer.start()
            val outcome = runCatching { conversation.generate(prompt, longSpeech, RecordingListener()) }
            closer.join()

            // Closed before the run started, the run is refused; after, it is cut short
            val error = outcome.exceptionOrNull()
            if (error != null) {
                assertTrue(error is IllegalStateException, "closing after $delayMillis ms: $error")
            } else {
                assertEquals(FinishReason.CANCELLED, outcome.getOrThrow().finishReason)
            }
            println("closed after $delayMillis ms: ${outcome.map { result -> result.finishReason }}")
        }
    }

    @Test
    fun closingFromTheListenerIsRefused() {
        val prompt = render(listOf(ChatMessage.user("Hi!")))

        engine.newConversation(128).use { conversation ->
            val listener = object : GenerationListener {
                override fun onText(delta: String) {
                    conversation.close()
                }
            }

            assertThrows<IllegalStateException> { conversation.generate(prompt, text, listener) }

            // The conversation is still open and usable
            val answer = conversation.generate(prompt, text, RecordingListener())
            assertEquals(FinishReason.END_OF_TURN, answer.finishReason)
        }
    }

    @Test
    fun aPromptTooLongForTheCapacityIsRejectedBeforeTouchingTheCache() {
        val first = render(listOf(ChatMessage.user("Hi!")))
        val second = render(listOf(ChatMessage.user("Hi! " + "Tell me more. ".repeat(20))))

        engine.newConversation(first.length + 30).use { conversation ->
            conversation.generate(first, GenerationRequest(GenerationMode.TEXT, maxNewSlots = 4), RecordingListener())
            val cached = conversation.cachedLength

            val error = assertThrows<ContextOverflowException> {
                conversation.generate(second, text, RecordingListener())
            }
            assertEquals(second.length + text.maxNewSlots, error.required)
            assertEquals(cached, conversation.cachedLength)

            assertThrows<ContextOverflowException> {
                conversation.prefill(second, RecordingListener())
            }
            assertEquals(cached, conversation.cachedLength)
        }
    }

    @Test
    fun invalidSamplingSettingsAreRejectedBeforeTouchingTheCache() {
        val first = render(listOf(ChatMessage.user("Hi!")))
        val second = render(listOf(ChatMessage.user("Hello! How are you today?")))
        val zeroPenalty = text.copy(textSampling = TextSampling(repetitionPenalty = 0f))
        val negativeWindow = text.copy(audioSampling = AudioSampling(repetitionWindow = -1))

        engine.newConversation(256).use { conversation ->
            conversation.generate(first, GenerationRequest(GenerationMode.TEXT, maxNewSlots = 4), RecordingListener())
            val cached = conversation.cachedLength

            for (request in listOf(zeroPenalty, negativeWindow)) {
                val listener = RecordingListener()

                assertThrows<IllegalArgumentException> { conversation.generate(second, request, listener) }

                // Nothing was rewound or fed
                assertEquals(emptyList(), listener.prefillReports)
                assertEquals(cached, conversation.cachedLength)
            }
        }
    }

    @Test
    fun aVideoWithItsSoundtrackIsEncodedPairByPair() {
        val width = 64
        val height = 48
        val loadedGroups = ArrayList<List<Int>>()
        val video = VideoInput(
            key = "clip",
            width = width,
            height = height,
            frameCount = 8,
            fps = 4.0,
            soundtrackSamples = 32_000,
            loadFrames = { indices ->
                loadedGroups.add(indices.toList())
                indices.map { index -> movingSquare(width, height, index) }
            },
            loadSoundtrack = { FloatArray(32_000) { sample -> 0.3f * kotlin.math.sin(sample * 0.05f) } },
        )
        val parts = listOf(ContentPart.Video(video, withSoundtrack = true), ContentPart.Text("Describe the video."))
        val prompt = render(listOf(ChatMessage(ChatRole.USER, parts)))
        val videoRows = MediaFeatures.expectedRows(video, FeatureStream.VIDEO)
        val soundtrackRows = MediaFeatures.expectedRows(video, FeatureStream.AUDIO)
        engine.resources.mediaFeatures.clear()

        val listener = RecordingListener()
        val answer = freshAnswer(prompt, GenerationRequest(GenerationMode.TEXT, maxNewSlots = 8), listener)

        // The four sampled frames came as two temporal pairs, and every row filled a placeholder
        assertEquals(listOf(listOf(0, 2), listOf(5, 7)), loadedGroups)
        assertEquals(videoRows + soundtrackRows + prompt.length, listener.prefillTotal)
        assertTrue(answer.content.tokens.isNotEmpty())
        println("video: ${answer.text}")
    }

    /** A white square moving right on black, frame by frame. */
    private fun movingSquare(width: Int, height: Int, frame: Int): RgbImage {
        val pixels = ByteArray(width * height * 3)
        for (y in 16 until 32) {
            for (x in frame * 4 until frame * 4 + 16) {
                val offset = (y * width + x) * 3
                pixels.fill(-1, offset, offset + 3)
            }
        }
        return RgbImage(width, height, pixels)
    }

    @Test
    fun resetEmptiesTheCache() {
        val prompt = render(listOf(ChatMessage.user("Hi!")))

        engine.newConversation(128).use { conversation ->
            val first = conversation.generate(prompt, text, RecordingListener())
            conversation.reset()
            assertEquals(0, conversation.cachedLength)

            val listener = RecordingListener()
            val again = conversation.generate(prompt, text, listener)
            assertEquals(prompt.length, listener.prefillTotal)
            assertEquals(first.content.tokens.toList(), again.content.tokens.toList())
        }
    }
}
