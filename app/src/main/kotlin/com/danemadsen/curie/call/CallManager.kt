package com.danemadsen.curie.call

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.danemadsen.curie.R
import com.danemadsen.curie.audio.MicrophoneArbiter
import com.danemadsen.curie.audio.MicrophoneSource
import com.danemadsen.curie.audio.SpeechOutput
import com.danemadsen.curie.audio.SpeechPlayer
import com.danemadsen.curie.data.Settings
import com.danemadsen.curie.inference.AssistantEngine
import com.danemadsen.curie.inference.SystemPrompts
import com.danemadsen.curie.inference.ToolExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

/** A call that runs or ran: its controller keeps the final state once it ended. */
class ActiveCall(val controller: CallController, val job: Job) {
    val isRunning: Boolean
        get() = job.isActive
}

/**
 * The one call at a time, for the whole app: the call screen, the call's foreground service and
 * its notification all look at [call]. A call holds the model loaded and the microphone (taking
 * it from the "Hey Curie" listener) until it ends.
 */
class CallManager(
    private val context: Context,
    private val engine: AssistantEngine,
    private val tools: ToolExecutor,
    private val microphone: MicrophoneSource,
    private val arbiter: MicrophoneArbiter,
    private val settings: Flow<Settings>,
    private val scope: CoroutineScope,
    private val today: () -> LocalDate = LocalDate::now,
) {
    private val mutableCall = MutableStateFlow<ActiveCall?>(null)
    private val startLock = Mutex()

    /** Every call runs on one thread, so its controller sees its own steps in order. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val callDispatcher = Dispatchers.Default.limitedParallelism(1)

    val environment = AppCallEnvironment(context)

    /** The current or last call; null before the first. */
    val call: StateFlow<ActiveCall?> = mutableCall.asStateFlow()

    /** Starts a call unless one runs; returns the running call. */
    suspend fun startCall(): ActiveCall = startLock.withLock {
        val running = mutableCall.value
        if (running != null && running.isRunning) {
            return@withLock running
        }

        val currentSettings = settings.first()
        val systemPrompt = SystemPrompts.call(currentSettings.userName, today())
        val texts = CallTexts(
            failure = context.getString(R.string.call_answer_failed),
            locked = context.getString(R.string.call_tool_locked),
        )
        val output: SpeechOutput = SpeechPlayer(context)
        val controller = CallController(
            assistant = EngineCallAssistant(engine),
            tools = tools,
            microphone = microphone,
            output = output,
            environment = environment,
            systemPrompt = systemPrompt,
            texts = texts,
            clock = SystemClock::elapsedRealtime,
        )

        // The wake word listener gives the microphone up when a call takes it
        val hasMicrophone = arbiter.acquire(MicrophoneArbiter.Owner.CALL)
        if (!hasMicrophone) {
            Log.w(TAG, "a voice message holds the microphone; the call records anyway")
        }
        val hold = engine.hold()
        val callScope = scope + callDispatcher
        val job = controller.start(callScope)
        val call = ActiveCall(controller, job)
        mutableCall.value = call

        scope.launch {
            job.join()
            hold.close()
            output.release()
            arbiter.release(MicrophoneArbiter.Owner.CALL)
        }
        call
    }

    fun hangUp() {
        mutableCall.value?.controller?.hangUp()
    }

    private companion object {
        const val TAG = "CallManager"
    }
}
