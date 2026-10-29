package com.danemadsen.curie.call

import android.content.Intent
import com.danemadsen.curie.audio.FocusChange
import com.danemadsen.curie.engine.chat.ChatMessage
import com.danemadsen.curie.engine.chat.ToolDefinition
import com.danemadsen.curie.engine.generation.GenerationListener
import com.danemadsen.curie.engine.generation.GenerationResult
import com.danemadsen.curie.inference.ContextWindow
import com.danemadsen.curie.inference.SpeechSink

/** What a call needs of the model. */
interface CallAssistant {
    /** Loads the model. Throws [com.danemadsen.curie.inference.EngineUnavailableException]. */
    suspend fun prepare()

    /** Fills the cache with the call's system prompt while the user starts talking. */
    suspend fun warmUp(messages: List<ChatMessage>, tools: List<ToolDefinition>)

    /**
     * Answers [messages] (system prompt first), the model choosing between speech and text, and
     * calling [tools] only while [allowToolCalls]. [window] tells how many of the oldest messages
     * had to be left out.
     */
    suspend fun respond(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>,
        listener: GenerationListener,
        speech: SpeechSink,
        window: ContextWindow,
        allowToolCalls: Boolean,
    ): GenerationResult

    /** Reads [text] aloud; false when it was cancelled. */
    suspend fun synthesize(text: String, speech: SpeechSink): Boolean

    /** Stops the running generation at its next step. */
    fun cancel()
}

/** The parts of the phone a call touches beyond the microphone and the speaker. */
interface CallEnvironment {
    /**
     * Takes transient audio focus for the call, or takes it back after a loss; true when it was
     * granted. [onChange] hears when another app takes it.
     */
    fun requestAudioFocus(onChange: (FocusChange) -> Unit): Boolean

    fun abandonAudioFocus()

    /** True when nothing personal is locked away: the phone is unlocked, or the user unlocked it now. */
    suspend fun ensureUnlocked(): Boolean

    /** Starts an app a tool prepared; false when nothing could open it. */
    fun launch(intent: Intent): Boolean
}
