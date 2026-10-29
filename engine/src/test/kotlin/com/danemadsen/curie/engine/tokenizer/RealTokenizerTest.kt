package com.danemadsen.curie.engine.tokenizer

import com.danemadsen.curie.engine.Lfm3Omni
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Facts of the model's own tokenizer.json (skipped without CURIE_MODEL_DIR), and what loading it
 * costs: the test prints the time and the bytes allocated of each of a few loads, and how much the
 * heap grew for one tokenizer. A class histogram puts the tokenizer itself at about 14 MB; G1
 * reports more because it rounds each large array up to whole regions.
 *
 * Run alone (--tests '*RealTokenizerTest.reportsLoadTimeAndHeapUse'), the first load shows the cold
 * start of a fresh JVM, before the JIT has compiled the reading code.
 */
class RealTokenizerTest {
    @Test
    fun specialTokenIdsMatchTheModelConstants() {
        val tokenizer = TestTokenizers.requireReal()
        val expected = mapOf(
            "<|pad|>" to Lfm3Omni.PAD_TOKEN,
            "<|startoftext|>" to Lfm3Omni.BOS_TOKEN,
            "<|im_start|>" to Lfm3Omni.IM_START_TOKEN,
            "<|im_end|>" to Lfm3Omni.IM_END_TOKEN,
            "<|vision_start|>" to Lfm3Omni.VISION_START_TOKEN,
            "<|vision_end|>" to Lfm3Omni.VISION_END_TOKEN,
            "<|audio_start|>" to Lfm3Omni.AUDIO_START_TOKEN,
            "<|text_end|>" to Lfm3Omni.TEXT_END_TOKEN,
            "<|mixed_start|>" to Lfm3Omni.MIXED_START_TOKEN,
            "<|video|>" to Lfm3Omni.VIDEO_TOKEN,
            "<|image|>" to Lfm3Omni.IMAGE_TOKEN,
            "<|audio|>" to Lfm3Omni.AUDIO_TOKEN,
            "<|audio_out|>" to Lfm3Omni.AUDIO_OUT_TOKEN,
        )
        for ((content, id) in expected) {
            assertEquals(id, tokenizer.tokenId(content), content)
            assertTrue(tokenizer.isSpecial(id), "$content is special")
        }

        // <think> and the tool call tags are added tokens, but not special: decoding keeps them
        for (content in listOf("<think>", "</think>", "<tool_call>", "</tool_call>", "<tool_response>")) {
            val id = tokenizer.tokenId(content)
            assertTrue(id != null && !tokenizer.isSpecial(id), "$content is a plain added token")
        }
        assertEquals(124_901, tokenizer.tokenId("<think>"))
        assertEquals(124_902, tokenizer.tokenId("</think>"))
    }

    @Test
    fun reportsLoadTimeAndHeapUse() {
        val file = TestTokenizers.requireRealFile()
        val runtime = Runtime.getRuntime()
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val thread = Thread.currentThread().id

        val heapBefore = usedHeapAfterGc(runtime)
        val loadMillis = ArrayList<Long>()
        val allocatedMegabytes = ArrayList<Long>()
        var tokenizer: BpeTokenizer? = null
        repeat(LOADS) {
            val allocatedBefore = threads.getThreadAllocatedBytes(thread)
            val start = System.nanoTime()
            tokenizer = BpeTokenizer.load(file)
            val elapsed = System.nanoTime() - start
            val allocated = threads.getThreadAllocatedBytes(thread) - allocatedBefore
            loadMillis.add(elapsed / 1_000_000)
            allocatedMegabytes.add(allocated / 1_000_000)
        }
        val retained = usedHeapAfterGc(runtime) - heapBefore

        val loaded = requireNotNull(tokenizer)
        val sample = loaded.encode("Hello, Curie!")
        println(
            "tokenizer.json (${file.length() / 1_000_000} MB): loads took $loadMillis ms and allocated " +
                "$allocatedMegabytes MB; one tokenizer grew the heap by ${retained / 1_000_000} MB",
        )
        assertTrue(sample.isNotEmpty())
        assertTrue(retained < 64_000_000, "the tokenizer keeps ${retained / 1_000_000} MB of heap")
    }

    private fun usedHeapAfterGc(runtime: Runtime): Long {
        repeat(3) {
            System.gc()
            Thread.sleep(100)
        }
        return runtime.totalMemory() - runtime.freeMemory()
    }

    private companion object {
        const val LOADS = 4
    }
}
