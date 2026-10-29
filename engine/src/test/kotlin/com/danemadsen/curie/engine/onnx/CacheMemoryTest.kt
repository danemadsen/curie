package com.danemadsen.curie.engine.onnx

import com.danemadsen.curie.engine.CacheMemory
import com.danemadsen.curie.engine.EngineOptions
import com.danemadsen.curie.engine.Lfm3Omni
import com.danemadsen.curie.engine.MemoryBlock
import com.danemadsen.curie.engine.chat.ChatMessage
import com.danemadsen.curie.engine.chat.ChatTemplate
import com.danemadsen.curie.engine.generation.GenerationMode
import com.danemadsen.curie.engine.generation.GenerationRequest
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Conversations keep their KV caches in the engine's [CacheMemory] (the app passes memory outside
 * the Java heap), and free it when they close. Skipped without CURIE_MODEL_DIR.
 */
class CacheMemoryTest {
    private class RecordingBlock(override val buffer: ByteBuffer) : MemoryBlock {
        var isClosed = false
            private set

        override fun close() {
            isClosed = true
        }
    }

    private class RecordingMemory : CacheMemory {
        val blocks = ArrayList<RecordingBlock>()

        override fun allocate(bytes: Int): MemoryBlock {
            val buffer = ByteBuffer.allocateDirect(bytes)
            val block = RecordingBlock(buffer)
            blocks.add(block)
            return block
        }
    }

    private fun hasNonZeroByte(buffer: ByteBuffer): Boolean {
        val reader = buffer.duplicate()
        while (reader.hasRemaining()) {
            if (reader.get() != 0.toByte()) {
                return true
            }
        }
        return false
    }

    @Test
    fun theCacheIsWrittenInPlaceAndFreedWithItsConversation() {
        val directory = ModelFixture.requireFolder()
        val memory = RecordingMemory()
        val options = EngineOptions(useLora = false, cacheMemory = memory)

        OnnxOmniEngine.load(directory, options).use { engine ->
            val tokenizer = (engine as OnnxEngine).resources.tokenizer
            val messages = listOf(ChatMessage.user("Say hello."))
            val prompt = ChatTemplate.render(messages, emptyList(), addGenerationPrompt = true, tokenizer)
            val request = GenerationRequest(GenerationMode.TEXT, maxNewSlots = 4)
            val capacity = 64

            val conversation = engine.newConversation(capacity)
            val block = memory.blocks.single()
            val layerBytes = Lfm3Omni.KV_HEADS * capacity * Lfm3Omni.HEAD_DIM * Float.SIZE_BYTES
            val expectedBytes = 2 * Lfm3Omni.ATTENTION_LAYERS.size * layerBytes
            assertEquals(expectedBytes, block.buffer.capacity())
            assertFalse(hasNonZeroByte(block.buffer))

            conversation.generate(prompt, request, RecordingListener())

            // ONNX Runtime appended the prompt's keys and values to the block itself, not to a copy
            assertTrue(hasNonZeroByte(block.buffer))
            assertFalse(block.isClosed)

            conversation.close()
            assertTrue(block.isClosed)
        }
    }
}
