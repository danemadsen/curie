package com.danemadsen.curie.engine.onnx

import com.danemadsen.curie.engine.chat.ChatMessage
import com.danemadsen.curie.engine.chat.ChatTemplate
import com.danemadsen.curie.engine.generation.AudioSampling
import com.danemadsen.curie.engine.generation.FinishReason
import com.danemadsen.curie.engine.generation.GenerationListener
import com.danemadsen.curie.engine.generation.GenerationMode
import com.danemadsen.curie.engine.generation.GenerationRequest
import com.danemadsen.curie.engine.prompt.Prompt
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

/**
 * Closing the engine from another thread while a conversation speaks: the run stops first, then
 * everything is freed, and every later use fails cleanly instead of reaching freed native memory.
 * Skipped without CURIE_MODEL_DIR.
 */
class EngineCloseTest {
    private val request = GenerationRequest(
        GenerationMode.SPEECH,
        maxNewSlots = 60,
        audioSampling = AudioSampling(temperature = 0f),
    )

    private fun longSpeechPrompt(engine: OnnxEngine): Prompt {
        val messages = listOf(
            ChatMessage.system("Perform TTS in American English using a female voice."),
            ChatMessage.user("Hello there. This is a fairly long sentence that the model should speak for a while."),
        )
        return ChatTemplate.render(messages, emptyList(), addGenerationPrompt = true, engine.resources.tokenizer)
    }

    @Test
    fun closingTheEngineStopsItsRunsBeforeFreeingThem() {
        val engine = ModelFixture.loadEngine(useLora = true)
        val prompt = longSpeechPrompt(engine)
        val conversation = engine.newConversation(256)
        val speechDecoder = engine.newSpeechDecoder(chunkFrames = 1, contextFrames = 20)

        var closer: Thread? = null
        var frames = 0
        val listener = object : GenerationListener {
            override fun onAudioFrame(codes: IntArray) {
                frames++
                // The codec runs on the same sessions while the conversation does
                speechDecoder.push(codes)
                if (frames == 2) {
                    val thread = Thread { engine.close() }
                    thread.start()
                    TestThreads.awaitWaiting(thread)
                    closer = thread
                }
            }
        }

        try {
            val result = conversation.generate(prompt, request, listener)
            closer?.join()

            assertEquals(FinishReason.CANCELLED, result.finishReason)
            assertEquals(2, frames)
            val speechFrame = IntArray(12) { 100 }
            assertThrows<IllegalStateException> { speechDecoder.push(speechFrame) }
            assertThrows<IllegalStateException> { conversation.generate(prompt, request, listener) }
            assertThrows<IllegalStateException> { engine.newConversation(64) }
            assertThrows<IllegalStateException> { engine.newSpeechDecoder() }
            // Closing the conversation again does nothing
            conversation.close()
        } finally {
            // Does nothing after the close above, frees the engine if the test failed before it
            engine.close()
        }
    }

    @Test
    fun aListenerThatPushesSpeechWhileTheEngineClosesMakesTheGenerationThrow() {
        val engine = ModelFixture.loadEngine(useLora = false)
        val prompt = longSpeechPrompt(engine)
        val conversation = engine.newConversation(256)
        // Twelve frames to a codec run: the frame pushed below completes none
        val speechDecoder = engine.newSpeechDecoder()

        var closer: Thread? = null
        var frames = 0
        val listener = object : GenerationListener {
            override fun onAudioFrame(codes: IntArray) {
                frames++
                if (frames == 2) {
                    // The close has started and waits for this generation when the frame is pushed
                    val thread = Thread { engine.close() }
                    thread.start()
                    TestThreads.awaitWaiting(thread)
                    closer = thread
                }
                speechDecoder.push(codes)
            }
        }

        try {
            val error = assertThrows<IllegalStateException> { conversation.generate(prompt, request, listener) }
            closer?.join()

            assertEquals("the engine is closed", error.message)
            assertEquals(2, frames)
            assertThrows<IllegalStateException> { speechDecoder.finish() }
        } finally {
            engine.close()
        }
    }
}
