package com.danemadsen.curie.assistant.wakeword

import android.os.PowerManager
import com.danemadsen.curie.audio.MicrophoneArbiter
import com.danemadsen.curie.audio.MicrophoneArbiter.Owner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WakeWordEngineTest {
    private val goodConditions = DeviceConditions(
        thermalStatus = PowerManager.THERMAL_STATUS_NONE,
        isPowerSaveMode = false,
        batteryPercent = 80,
        isPluggedIn = false,
    )
    private val conditions = MutableStateFlow(goodConditions)
    private val microphone = MicrophoneArbiter()
    private val events = mutableListOf<String>()
    private var canOpenConversation = true

    /** Each run waits until the test decides how it ends; cancellations are counted. */
    private inner class FakeRecognition : Recognition {
        var runs = 0
        var cancellations = 0
        var ownerDuringRun: Owner? = null
        var nextResult = CompletableDeferred<ListenResult>()

        override suspend fun run(): ListenResult {
            runs += 1
            ownerDuringRun = microphone.owner.value
            try {
                val result = nextResult.await()
                nextResult = CompletableDeferred()
                return result
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                cancellations += 1
                throw cancellation
            }
        }
    }

    private val recognition = FakeRecognition()

    private val listener = object : WakeWordEngineListener {
        override fun onListening() {
            events.add("listening")
        }

        override fun onPaused(reason: PauseReason) {
            events.add("paused:$reason")
        }

        override fun onWakeWord(): Boolean {
            events.add("wake word, microphone ${microphone.owner.value}")
            return canOpenConversation
        }

        override fun onStopped(reason: StopReason) {
            events.add("stopped:$reason")
        }
    }

    private fun TestScope.startEngine() {
        val engine = WakeWordEngine(conditions, microphone, recognition, listener)
        backgroundScope.launch { engine.run() }
        runCurrent()
    }

    @Test
    fun listensWithTheMicrophoneWhenAllIsWell() = runTest {
        startEngine()

        assertEquals(1, recognition.runs)
        assertEquals(Owner.WAKE_WORD, recognition.ownerDuringRun)
    }

    @Test
    fun batterySaverPausesAndItsEndResumes() = runTest {
        startEngine()

        conditions.value = goodConditions.copy(isPowerSaveMode = true)
        runCurrent()
        assertEquals(1, recognition.cancellations)
        assertEquals(listOf("paused:BATTERY_SAVER"), events)
        assertNull(microphone.owner.value)

        conditions.value = goodConditions
        runCurrent()
        assertEquals(2, recognition.runs)
    }

    @Test
    fun aCallTakesTheMicrophoneAndListeningResumesShortlyAfter() = runTest {
        startEngine()

        microphone.acquire(Owner.CALL)
        runCurrent()
        assertEquals(1, recognition.cancellations)
        assertEquals(listOf("paused:MICROPHONE_IN_USE"), events)
        assertEquals(Owner.CALL, microphone.owner.value)

        microphone.release(Owner.CALL)
        advanceTimeBy(WakeWordEngine.MICROPHONE_RETURN_DELAY_MILLIS - 1)
        runCurrent()
        assertEquals(1, recognition.runs)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, recognition.runs)
    }

    @Test
    fun theWakeWordReleasesTheMicrophoneBeforeTheHandOff() = runTest {
        startEngine()

        recognition.nextResult.complete(ListenResult.WakeWordHeard)
        runCurrent()

        assertEquals(listOf("wake word, microphone null"), events)
        assertNull(microphone.owner.value)

        advanceTimeBy(WakeWordEngine.AFTER_WAKE_WORD_DELAY_MILLIS)
        runCurrent()
        assertEquals(2, recognition.runs)
    }

    @Test
    fun aWakeWordThatCannotOpenTheConversationStopsListening() = runTest {
        canOpenConversation = false
        startEngine()

        recognition.nextResult.complete(ListenResult.WakeWordHeard)
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()

        val expected = listOf("wake word, microphone null", "stopped:CANNOT_OPEN_CONVERSATION")
        assertEquals(expected, events)
        assertEquals(1, recognition.runs)
        assertNull(microphone.owner.value)
    }

    @Test
    fun aStopIsReportedAndListeningDoesNotRetry() = runTest {
        startEngine()

        recognition.nextResult.complete(ListenResult.Stopped(StopReason.NO_RECOGNIZER))
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(listOf("stopped:NO_RECOGNIZER"), events)
        assertEquals(1, recognition.runs)
        assertNull(microphone.owner.value)
    }

    @Test
    fun heatPausesEvenWhileTheMicrophoneIsBusy() = runTest {
        microphone.acquire(Owner.VOICE_MESSAGE)
        startEngine()
        assertEquals(0, recognition.runs)

        conditions.value = goodConditions.copy(thermalStatus = PowerManager.THERMAL_STATUS_SEVERE)
        runCurrent()

        assertEquals(listOf("paused:MICROPHONE_IN_USE", "paused:TOO_HOT"), events)
        assertTrue(recognition.cancellations == 0)
    }
}
