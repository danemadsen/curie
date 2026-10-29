package com.danemadsen.curie.call

import android.content.Intent
import android.util.Log
import com.danemadsen.curie.audio.AudioLevels
import com.danemadsen.curie.audio.FocusChange
import com.danemadsen.curie.audio.LevelMeter
import com.danemadsen.curie.audio.MicrophoneSource
import com.danemadsen.curie.audio.MicrophoneUnavailableException
import com.danemadsen.curie.audio.RecordingPurpose
import com.danemadsen.curie.audio.SpeechOutput
import com.danemadsen.curie.audio.VadEvent
import com.danemadsen.curie.audio.VadSensitivity
import com.danemadsen.curie.audio.VadSettings
import com.danemadsen.curie.audio.VoiceActivityDetector
import com.danemadsen.curie.engine.chat.ChatMessage
import com.danemadsen.curie.engine.chat.ChatRole
import com.danemadsen.curie.engine.chat.ContentPart
import com.danemadsen.curie.engine.chat.ToolCall
import com.danemadsen.curie.engine.generation.FinishReason
import com.danemadsen.curie.engine.generation.GenerationListener
import com.danemadsen.curie.engine.generation.GenerationResult
import com.danemadsen.curie.engine.prompt.AudioInput
import com.danemadsen.curie.inference.ContextWindow
import com.danemadsen.curie.inference.EngineUnavailableException
import com.danemadsen.curie.inference.SpeechSink
import com.danemadsen.curie.inference.ToolExecutor
import com.danemadsen.curie.inference.SpeakableText
import com.danemadsen.curie.inference.ToolResults
import com.danemadsen.curie.inference.WebLookups
import com.danemadsen.curie.tools.ToolOrigin
import com.danemadsen.curie.tools.ToolOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Where a call stands. */
enum class CallStage {
    /** The model is loading. */
    CONNECTING,

    /** Waiting for the user to speak, or hearing them ([CallState.isUserSpeaking]). */
    LISTENING,

    /** The user finished; the answer is being prepared and none of it plays yet. */
    THINKING,

    SPEAKING,
    ENDED,
}

enum class CallEndReason {
    HUNG_UP,

    /** A tool opened another app after Curie had answered. */
    APP_LAUNCHED,

    MODEL_UNAVAILABLE,
    MICROPHONE_UNAVAILABLE,
}

data class CallState(
    val stage: CallStage = CallStage.CONNECTING,
    val isUserSpeaking: Boolean = false,

    /** The text of the answer being spoken; blank between answers. */
    val assistantCaption: String = "",

    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = true,

    /** When the call connected ([CallController]'s clock); null while connecting. */
    val connectedAtMs: Long? = null,

    val endReason: CallEndReason? = null,
)

/**
 * A spoken conversation with the model, like a phone call. The microphone feeds a
 * [VoiceActivityDetector]; each utterance becomes a user turn holding the audio, and the model
 * answers it ([CallAssistant.respond], speech and text as it chooses) while the speech is decoded
 * and streamed to [output]. An answer without speech is read aloud ([CallAssistant.synthesize]).
 *
 * While Curie thinks or speaks, the detector listens for a barge-in (louder, longer speech): it
 * stops the answer and the user's new utterance, pre-roll included, becomes the next turn.
 * Tools run with [ToolOrigin.CALL]; actions that open another app run after Curie finished
 * speaking, and then the call ends.
 *
 * Curie speaks only while the call holds the audio focus: another app talking over her pauses
 * her, and one that takes the audio for good (or keeps it when an answer starts) leaves the
 * answer to the captions. Each answer asks for the focus again after it was lost.
 *
 * The conversation lives only in memory. [start] runs the call until [hangUp] (or a failure);
 * the microphone stays open for the whole call.
 */
class CallController(
    private val assistant: CallAssistant,
    private val tools: ToolExecutor,
    private val microphone: MicrophoneSource,
    private val output: SpeechOutput,
    private val environment: CallEnvironment,
    private val systemPrompt: String,
    private val texts: CallTexts,
    private val clock: () -> Long,
    vadSettings: VadSettings = VadSettings(),
) {
    private val mutableState = MutableStateFlow(CallState())
    private val vad = VoiceActivityDetector(vadSettings)
    private val inputMeter = LevelMeter()
    private val history = ArrayList<ChatMessage>()
    private var job: Job? = null

    @Volatile
    private var isMuted = false

    /** Set when the user barges in; the answer under way checks it between its steps. */
    @Volatile
    private var isInterrupted = false

    /** True while the call holds the audio focus; a lasting loss clears it until the next answer asks again. */
    @Volatile
    private var hasAudioFocus = false

    /**
     * Whether the answer under way is heard: not when the call could not get the audio focus for
     * it, or lost the focus since.
     */
    @Volatile
    private var isAudible = false

    val state: StateFlow<CallState> = mutableState.asStateFlow()

    /** The user's voice level (0 to 1); cheap to read on every frame. */
    val inputLevel: Float
        get() = inputMeter.level

    /** Curie's voice level (0 to 1); cheap to read on every frame. */
    val outputLevel: Float
        get() = output.level

    /** Runs the call in [scope] until it ends; the returned job completes then. */
    fun start(scope: CoroutineScope): Job {
        check(job == null) { "a call controller runs one call" }
        val started = scope.launch { run() }
        job = started
        return started
    }

    fun hangUp() {
        mutableState.update { current -> current.copy(endReason = current.endReason ?: CallEndReason.HUNG_UP) }
        job?.cancel()
    }

    fun setMuted(muted: Boolean) {
        isMuted = muted
        if (muted) {
            synchronized(vad) { vad.reset() }
            inputMeter.reset()
        }
        mutableState.update { current -> current.copy(isMuted = muted, isUserSpeaking = false) }
    }

    fun setSpeakerOn(speakerOn: Boolean) {
        output.setSpeakerOn(speakerOn)
        mutableState.update { current -> current.copy(isSpeakerOn = speakerOn) }
    }

    private suspend fun run() {
        try {
            hasAudioFocus = environment.requestAudioFocus(::onFocusChange)
            if (!hasAudioFocus) {
                Log.i(TAG, "another app keeps the audio: answers show as captions until it lets go")
            }
            output.setSpeakerOn(mutableState.value.isSpeakerOn)
            assistant.prepare()

            val connectedAt = clock()
            mutableState.update { current -> current.copy(stage = CallStage.LISTENING, connectedAtMs = connectedAt) }
            coroutineScope {
                val warming = launch { warmUp() }
                val events = Channel<VadEvent>(Channel.UNLIMITED)
                val listening = launch { listen(events) }
                converse(events)
                warming.cancel()
                listening.cancel()
            }
        } catch (error: EngineUnavailableException) {
            endWith(CallEndReason.MODEL_UNAVAILABLE)
        } catch (error: MicrophoneUnavailableException) {
            Log.w(TAG, "the call lost the microphone", error)
            endWith(CallEndReason.MICROPHONE_UNAVAILABLE)
        } finally {
            output.stop()
            environment.abandonAudioFocus()
            inputMeter.reset()
            mutableState.update { current ->
                val reason = current.endReason ?: CallEndReason.HUNG_UP
                current.copy(stage = CallStage.ENDED, isUserSpeaking = false, endReason = reason)
            }
        }
    }

    private suspend fun warmUp() {
        try {
            val messages = listOf(ChatMessage.system(systemPrompt))
            assistant.warmUp(messages, tools.definitions)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "warming up the call failed", error)
        }
    }

    /** Microphone to detector, for the whole call; the detector's events go to [events]. */
    private suspend fun listen(events: Channel<VadEvent>) {
        val chunks = microphone.record(RecordingPurpose.CALL)
        chunks.collect { chunk ->
            if (isMuted) {
                return@collect
            }
            val rms = AudioLevels.rms(chunk)
            inputMeter.update(rms)
            val detected = synchronized(vad) { vad.process(chunk) }
            for (event in detected) {
                events.send(event)
            }
        }
    }

    /** Turn after turn until an answer opens another app (or the call is hung up). */
    private suspend fun converse(events: ReceiveChannel<VadEvent>) {
        while (true) {
            val utterance = awaitUtterance(events)
            val outcome = respond(utterance, events)
            if (outcome is Outcome.LaunchApps) {
                for (intent in outcome.intents) {
                    environment.launch(intent)
                }
                endWith(CallEndReason.APP_LAUNCHED)
                return
            }
            if (outcome is Outcome.Failed && outcome.isFatal) {
                endWith(CallEndReason.MODEL_UNAVAILABLE)
                return
            }
        }
    }

    private suspend fun awaitUtterance(events: ReceiveChannel<VadEvent>): FloatArray {
        setSensitivity(VadSensitivity.NORMAL)
        mutableState.update { current -> current.copy(stage = CallStage.LISTENING) }
        while (true) {
            when (val event = events.receive()) {
                VadEvent.SpeechStarted -> {
                    mutableState.update { current -> current.copy(isUserSpeaking = true) }
                }
                is VadEvent.SpeechEnded -> {
                    mutableState.update { current -> current.copy(isUserSpeaking = false) }
                    return event.samples
                }
            }
        }
    }

    /** Answers [utterance], unless the user barges in (their speech arrives on [events]). */
    private suspend fun respond(utterance: FloatArray, events: ReceiveChannel<VadEvent>): Outcome = coroutineScope {
        addUserTurn(utterance)
        isInterrupted = false
        setSensitivity(VadSensitivity.BARGE_IN)
        mutableState.update { current ->
            current.copy(stage = CallStage.THINKING, assistantCaption = "", isUserSpeaking = false)
        }

        isAudible = false
        if (ensureAudioFocus()) {
            isAudible = output.begin()
        }
        val speaking = launch {
            output.isPlaying.first { isPlaying -> isPlaying }
            mutableState.update { current -> current.copy(stage = CallStage.SPEAKING) }
        }
        val answer = async { answer() }

        var outcome: Outcome? = null
        while (outcome == null) {
            outcome = select {
                answer.onAwait { result -> result }
                events.onReceive { event -> if (event == VadEvent.SpeechStarted) Outcome.Interrupted else null }
            }
        }
        if (outcome == Outcome.Interrupted && answer.isActive) {
            interrupt(answer)
        }
        speaking.cancel()

        if (outcome == Outcome.Interrupted) {
            mutableState.update { current -> current.copy(stage = CallStage.LISTENING, isUserSpeaking = true) }
        }
        if (outcome is Outcome.Failed) {
            mutableState.update { current -> current.copy(assistantCaption = texts.failure) }
        }
        outcome
    }

    /** Stops the answer for a barge-in: what was said so far stays in the history. */
    private suspend fun interrupt(answer: Deferred<Outcome>) {
        isInterrupted = true
        output.stop()
        assistant.cancel()
        val stopped = withTimeoutOrNull(CANCEL_GRACE_MS) { answer.join() }
        if (stopped == null) {
            answer.cancelAndJoin()
        }
    }

    /**
     * Generates (with tool rounds), reads a speechless answer aloud and waits for it to play.
     * An answer nobody can hear (see [isAudible]) only shows in the captions.
     */
    private suspend fun answer(): Outcome {
        val deferredLaunches = ArrayList<Intent>()
        val hasSpoken = AtomicBoolean(false)
        val sink = SpeechSink { samples ->
            hasSpoken.set(true)
            if (isAudible) {
                output.write(samples)
            }
        }
        var lastText = ""
        var allowToolCalls = true
        var round = 0

        try {
            while (true) {
                val messages = promptMessages()
                val listener = CaptionListener(round > 0)
                val window = ContextWindow()
                val result = assistant.respond(messages, tools.definitions, listener, sink, window, allowToolCalls)
                forgetDropped(window.droppedMessages)
                addAssistantTurn(result)
                if (isInterrupted || result.finishReason == FinishReason.CANCELLED) {
                    return Outcome.Interrupted
                }
                if (result.text.isNotBlank()) {
                    lastText = result.text
                }

                val hasToolCalls = result.toolCalls.isNotEmpty()
                if (!hasToolCalls || round >= MAX_TOOL_ROUNDS) {
                    break
                }
                // A call has no typed words to ask for the web with, so its lookups never open; the
                // model then answers instead of looking the same thing up again (see WebLookups)
                val ranOutcomes = runTools(result.toolCalls)
                val outcomes = ranOutcomes.map { outcome -> WebLookups.applyTo(outcome, isRequested = false) }
                allowToolCalls = outcomes.none { outcome -> outcome.isWebLookup }
                for (outcome in outcomes) {
                    val intent = outcome.deferredLaunch ?: continue
                    // The same app asked for twice in one answer opens once
                    val isRepeat = deferredLaunches.any { earlier -> earlier.filterEquals(intent) }
                    if (!isRepeat) {
                        deferredLaunches.add(intent)
                    }
                }
                val resultsText = ToolResults.toolMessageText(outcomes)
                history.add(ChatMessage.tool(resultsText))
                round++
            }

            if (isAudible) {
                speakToTheEnd(hasSpoken.get(), lastText, sink)
            }
            if (isInterrupted) {
                return Outcome.Interrupted
            }
            return if (deferredLaunches.isEmpty()) Outcome.Completed else Outcome.LaunchApps(deferredLaunches)
        } catch (error: CancellationException) {
            throw error
        } catch (error: EngineUnavailableException) {
            removeUnansweredTurn()
            return Outcome.Failed(isFatal = true)
        } catch (error: Exception) {
            Log.e(TAG, "answering in the call failed", error)
            removeUnansweredTurn()
            output.stop()
            return Outcome.Failed(isFatal = false)
        }
    }

    /**
     * Reads a speechless answer aloud, then waits for the speech to play out. Only a barge-in
     * cancels the reading, and it sets [isInterrupted]. Playback also ends early when another app
     * takes the audio; the answer still counts then.
     */
    private suspend fun speakToTheEnd(hasSpoken: Boolean, text: String, sink: SpeechSink) {
        val needsReading = !hasSpoken && text.isNotBlank()
        if (needsReading) {
            val expectedSeconds = SpeakableText.estimatedSeconds(text)
            output.expectDuration(expectedSeconds)
            assistant.synthesize(text, sink)
            if (isInterrupted) {
                return
            }
        }
        output.endOfAnswer()
        output.awaitFinished()
    }

    private suspend fun runTools(calls: List<ToolCall>): List<ToolOutcome> {
        // Tools reach personal data and other apps: not from behind the lock screen
        val isUnlocked = environment.ensureUnlocked()
        val outcomes = ArrayList<ToolOutcome>(calls.size)
        for (call in calls) {
            val outcome = if (isUnlocked) tools.execute(call, ToolOrigin.CALL) else lockedOutcome()
            outcomes.add(outcome)
        }
        return outcomes
    }

    private fun lockedOutcome(): ToolOutcome {
        val error = JsonPrimitive("The phone is locked. Ask the user to unlock it and try again.")
        val result = JsonObject(mapOf("error" to error))
        return ToolOutcome(result = result, summary = texts.locked, icon = "lock", deferredLaunch = null, success = false)
    }

    private fun promptMessages(): List<ChatMessage> {
        val messages = ArrayList<ChatMessage>(history.size + 1)
        messages.add(ChatMessage.system(systemPrompt))
        messages.addAll(history)
        return messages
    }

    private fun addUserTurn(samples: FloatArray) {
        val key = "call-" + UUID.randomUUID()
        val input = AudioInput(key, samples.size) { samples }
        val parts = listOf(ContentPart.Audio(input))
        history.add(ChatMessage(ChatRole.USER, parts))
    }

    /**
     * Forgets the oldest [count] messages, which no longer fit the context. The history then starts
     * where the prompt did, so the cut stays put (and the cache with it) until the call outgrows it.
     */
    private fun forgetDropped(count: Int) {
        val forgotten = minOf(count, history.size)
        history.subList(0, forgotten).clear()
    }

    private fun addAssistantTurn(result: GenerationResult) {
        if (result.content.tokens.isEmpty()) {
            return
        }
        history.add(ChatMessage(ChatRole.ASSISTANT, generated = result.content))
    }

    /** After a failure the last user turn goes, so it does not trip the next answer too. */
    private fun removeUnansweredTurn() {
        while (history.isNotEmpty()) {
            val last = history.removeAt(history.lastIndex)
            if (last.role == ChatRole.USER) {
                return
            }
        }
    }

    private fun setSensitivity(sensitivity: VadSensitivity) {
        synchronized(vad) { vad.sensitivity = sensitivity }
    }

    /** The call's audio focus, asked for again when another app took it for good. */
    private fun ensureAudioFocus(): Boolean {
        if (!hasAudioFocus) {
            hasAudioFocus = environment.requestAudioFocus(::onFocusChange)
            if (hasAudioFocus) {
                // Granted again: whatever paused the speech before is over
                output.setPaused(false)
            }
        }
        return hasAudioFocus
    }

    private fun onFocusChange(change: FocusChange) {
        when (change) {
            FocusChange.GAINED -> output.setPaused(false)
            FocusChange.PAUSE -> output.setPaused(true)
            FocusChange.LOST -> loseAudio()
        }
    }

    /** Another app took the audio for good: the rest of the answer shows in the captions only. */
    private fun loseAudio() {
        hasAudioFocus = false
        isAudible = false
        output.stop()
        mutableState.update { current ->
            if (current.stage == CallStage.SPEAKING) current.copy(stage = CallStage.THINKING) else current
        }
    }

    private fun endWith(reason: CallEndReason) {
        mutableState.update { current -> current.copy(endReason = current.endReason ?: reason) }
    }

    /** Shows the answer's text as it is generated; later tool rounds continue the caption. */
    private inner class CaptionListener(private val continues: Boolean) : GenerationListener {
        private var isFirst = true

        override fun onText(delta: String) {
            val separator = if (continues && isFirst) " " else ""
            isFirst = false
            mutableState.update { current ->
                val caption = current.assistantCaption + separator + delta
                current.copy(assistantCaption = caption.trimStart())
            }
        }
    }

    private sealed interface Outcome {
        data object Completed : Outcome

        data object Interrupted : Outcome

        class LaunchApps(val intents: List<Intent>) : Outcome

        class Failed(val isFatal: Boolean) : Outcome
    }

    companion object {
        const val MAX_TOOL_ROUNDS = 4

        private const val TAG = "CallController"

        /** How long a barge-in waits for the engine to stop by itself before cancelling harder. */
        private const val CANCEL_GRACE_MS = 1_000L
    }
}

/** The user-facing texts a call shows. */
class CallTexts(
    /** The caption when an answer failed. */
    val failure: String,

    /** The summary of a tool call refused because the phone is locked. */
    val locked: String,
)
