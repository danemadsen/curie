package com.danemadsen.curie.ui.call

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danemadsen.curie.call.ActiveCall
import com.danemadsen.curie.call.CallEndReason
import com.danemadsen.curie.call.CallManager
import com.danemadsen.curie.call.CallStage
import com.danemadsen.curie.call.CallState
import com.danemadsen.curie.data.Settings
import com.danemadsen.curie.ui.common.Persona
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The call screen's state holder: it starts the call (or joins the one running) once the screen
 * has the microphone, and turns that call's state into [CallUiState], ticking the call timer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallViewModel(
    private val callManager: CallManager,
    settings: Flow<Settings>,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : ViewModel() {
    private val initialState = CallUiState(
        assistant = Persona(Settings.DEFAULT_ASSISTANT_NAME),
        phase = CallPhase.CONNECTING,
    )

    /** The call this screen shows: the one it started or joined. */
    private val call = MutableStateFlow<ActiveCall?>(null)

    private val callState: Flow<CallState?> = call.flatMapLatest { current ->
        current?.controller?.state ?: flowOf(null)
    }

    private val ticks: Flow<Long> = flow {
        while (true) {
            emit(clock())
            delay(TICK_MS)
        }
    }

    val uiState: StateFlow<CallUiState> = combine(settings, callState, ticks) { currentSettings, state, now ->
        toUiState(currentSettings, state, now)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), initialState)

    /** True once the screen's call has ended: the screen can close. */
    val isCallOver: StateFlow<Boolean> = callState
        .map { state -> state?.stage == CallStage.ENDED }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Why the screen's call ended; null while it runs. */
    fun endReason(): CallEndReason? = call.value?.controller?.state?.value?.endReason

    /** The level the eclipse follows: Curie's voice while she speaks, else the user's. Cheap per frame. */
    fun audioLevel(): Float {
        val controller = call.value?.controller ?: return 0f
        val stage = controller.state.value.stage
        return if (stage == CallStage.SPEAKING) controller.outputLevel else controller.inputLevel
    }

    /** Starts the call (or joins the running one); [onStarted] runs once it is under way. */
    fun startCall(onStarted: () -> Unit) {
        if (call.value != null) {
            onStarted()
            return
        }
        viewModelScope.launch {
            call.value = callManager.startCall()
            onStarted()
        }
    }

    fun onAction(action: CallAction) {
        val controller = call.value?.controller ?: return
        val state = controller.state.value
        when (action) {
            CallAction.ToggleMute -> controller.setMuted(!state.isMuted)
            CallAction.ToggleSpeaker -> controller.setSpeakerOn(!state.isSpeakerOn)
            CallAction.EndCall -> controller.hangUp()
        }
    }

    private fun toUiState(settings: Settings, state: CallState?, now: Long): CallUiState {
        val assistant = Persona(name = settings.assistantName, imageUri = settings.assistantImage)
        if (state == null) {
            return initialState.copy(assistant = assistant)
        }
        val connectedAt = state.connectedAtMs
        val elapsedMs = if (connectedAt == null) 0L else (now - connectedAt).coerceAtLeast(0L)
        return CallUiState(
            assistant = assistant,
            phase = phaseOf(state.stage),
            elapsedSeconds = elapsedMs / 1000,
            assistantCaption = state.assistantCaption,
            isUserSpeaking = state.isUserSpeaking,
            isMuted = state.isMuted,
            isSpeakerOn = state.isSpeakerOn,
        )
    }

    private fun phaseOf(stage: CallStage): CallPhase = when (stage) {
        CallStage.CONNECTING -> CallPhase.CONNECTING
        CallStage.LISTENING -> CallPhase.LISTENING
        CallStage.THINKING -> CallPhase.THINKING
        CallStage.SPEAKING -> CallPhase.SPEAKING
        CallStage.ENDED -> CallPhase.ENDED
    }

    private companion object {
        const val TICK_MS = 1_000L
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
