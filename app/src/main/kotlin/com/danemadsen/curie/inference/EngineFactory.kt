package com.danemadsen.curie.inference

import com.danemadsen.curie.engine.EngineOptions
import com.danemadsen.curie.engine.ModelFiles
import com.danemadsen.curie.engine.OmniEngine
import com.danemadsen.curie.engine.chat.ChatMessage
import com.danemadsen.curie.engine.chat.ChatTemplate
import com.danemadsen.curie.engine.chat.ToolDefinition
import com.danemadsen.curie.engine.onnx.OnnxOmniEngine
import com.danemadsen.curie.engine.prompt.Prompt
import com.danemadsen.curie.engine.tokenizer.BpeTokenizer
import java.io.File

/**
 * Opens the model in a folder of [ModelFiles]. The app reaches the engine implementation only
 * through this seam; tests pass a fake.
 */
fun interface EngineFactory {
    /** Blocks for seconds and takes about 3 GB: call it once, off the main thread. */
    fun load(directory: File): OmniEngine
}

/** LFM3-Omni on ONNX Runtime with the bundled Curie voice, its caches outside the Java heap. */
object OnnxEngineFactory : EngineFactory {
    override fun load(directory: File): OmniEngine {
        val options = EngineOptions(cacheMemory = SharedCacheMemory)
        return OnnxOmniEngine.load(directory, options)
    }
}

/** Turns a conversation into decoder slots (the chat template over the model's tokenizer). */
fun interface PromptRenderer {
    /** The prompt of [messages] offering [tools]; with [addGenerationPrompt] it ends opening the assistant's turn. */
    fun render(messages: List<ChatMessage>, tools: List<ToolDefinition>, addGenerationPrompt: Boolean): Prompt
}

/** Makes the [PromptRenderer] of a model folder. */
fun interface PromptRendererFactory {
    fun load(directory: File): PromptRenderer
}

/** LFM3-Omni-2B's chat template over [tokenizer]. */
class ChatTemplateRenderer(private val tokenizer: BpeTokenizer) : PromptRenderer {
    override fun render(messages: List<ChatMessage>, tools: List<ToolDefinition>, addGenerationPrompt: Boolean): Prompt =
        ChatTemplate.render(messages, tools, addGenerationPrompt, tokenizer)
}

/** Loads the folder's tokenizer.json for the [ChatTemplateRenderer]. */
object TokenizerRendererFactory : PromptRendererFactory {
    override fun load(directory: File): PromptRenderer {
        val tokenizerFile = File(directory, ModelFiles.TOKENIZER)
        val tokenizer = BpeTokenizer.load(tokenizerFile)
        return ChatTemplateRenderer(tokenizer)
    }
}
