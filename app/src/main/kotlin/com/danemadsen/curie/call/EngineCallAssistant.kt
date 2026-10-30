package com.danemadsen.curie.call

import com.danemadsen.curie.engine.chat.ChatMessage
import com.danemadsen.curie.engine.chat.ToolDefinition
import com.danemadsen.curie.engine.generation.GenerationListener
import com.danemadsen.curie.engine.generation.GenerationMode
import com.danemadsen.curie.engine.generation.GenerationRequest
import com.danemadsen.curie.engine.generation.GenerationResult
import com.danemadsen.curie.inference.AssistantEngine
import com.danemadsen.curie.inference.ContextWindow
import com.danemadsen.curie.inference.ConversationSlot
import com.danemadsen.curie.inference.SpeechSink

/** [CallAssistant] on the app's [AssistantEngine]: calls take turns with chats on the main conversation. */
class EngineCallAssistant(private val engine: AssistantEngine) : CallAssistant {
    override suspend fun prepare() {
        engine.load()
    }

    override suspend fun warmUp(messages: List<ChatMessage>, tools: List<ToolDefinition>) {
        val prompt = AssistantEngine.WarmUpPrompt(messages, tools)
        engine.warmUp(ConversationSlot.MAIN, prompt)
    }

    override suspend fun respond(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
        listener: GenerationListener,
        speech: SpeechSink,
        window: ContextWindow,
        allowToolCalls: Boolean,
    ): GenerationResult {
        // Written, then read aloud in the Curie voice, which only the TTS format has
        val request = GenerationRequest(
            mode = GenerationMode.TEXT,
            maxNewSlots = ANSWER_SLOTS,
            allowToolCalls = allowToolCalls,
        )
        return engine.generate(
            slot = ConversationSlot.MAIN,
            messages = messages,
            tools = tools,
            request = request,
            listener = listener,
            speech = speech,
            window = window,
        )
    }

    override suspend fun synthesize(text: String, speech: SpeechSink): Boolean = engine.synthesize(text, speech)

    override fun cancel() {
        engine.cancel()
    }

    private companion object {
        /** About 40 seconds of interleaved speech: the call's style asks for one to three sentences. */
        const val ANSWER_SLOTS = 768
    }
}
