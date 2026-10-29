package com.danemadsen.curie.assistant.wakeword

import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

/** How a listening run ended. */
internal sealed interface ListenResult {
    data object WakeWordHeard : ListenResult

    data class Stopped(val reason: StopReason) : ListenResult
}

/** One listening run: until the wake word is heard or listening cannot go on. */
internal fun interface Recognition {
    /** Cancelling it releases the recognizer and the microphone. Main thread only. */
    suspend fun run(): ListenResult
}

/** What the loop tells the outside while it runs. Called on the main thread. */
internal interface RecognitionLoopListener {
    fun onListening()

    fun onPaused(reason: PauseReason)
}

/**
 * Runs SpeechRecognizer sessions back to back until "Hey Curie" is heard. On API 33+ Curie records the
 * audio itself and feeds one long segmented session through a pipe (no restart beeps, and calls take
 * the microphone over cleanly); recognizers that ignore the pipe, and older Android versions, fall back
 * to the classic loop where the recognizer records each utterance itself.
 *
 * All recognizer calls happen on the main thread, as SpeechRecognizer requires; one recognizer exists
 * at a time and is always destroyed. The loop keeps what it learnt (on-device language support, pipe
 * support, the backoff) across runs.
 */
internal class RecognitionLoop(
    private val recognizers: RecognizerFactory,
    private val createFeed: () -> MicrophoneFeed,
    private val policy: RestartPolicy,
    private val elapsedRealtime: () -> Long,
    canUseOwnMicrophone: Boolean,
    private val listener: RecognitionLoopListener,
) : Recognition {
    private var preferOnDevice = true
    private var usesOwnMicrophone = canUseOwnMicrophone

    private sealed interface RecognizerEvent {
        data object Ready : RecognizerEvent

        data class Partial(val hypotheses: List<String>) : RecognizerEvent

        /** onResults (which ends a classic session) or onSegmentResults (which does not). */
        data class Final(val hypotheses: List<String>, val endsSession: Boolean) : RecognizerEvent

        data object SegmentedSessionEnded : RecognizerEvent

        data class Failed(val code: Int) : RecognizerEvent

        data class SilencedChanged(val isSilenced: Boolean) : RecognizerEvent

        /** Time to check the session's timers. */
        data object Tick : RecognizerEvent
    }

    private sealed interface SessionResult {
        data object WakeWordHeard : SessionResult

        data class Finished(val outcome: SessionOutcome, val wasHealthy: Boolean) : SessionResult
    }

    private sealed interface FeedStart {
        data class Started(val audioSource: ParcelFileDescriptor) : FeedStart

        /** A SpeechRecognizer.ERROR_* code that describes why recording did not start. */
        data class Failed(val errorCode: Int) : FeedStart
    }

    private class SessionProgress(val startedAt: Long) {
        var readyAt: Long? = null
        var hadResults = false

        /** Results arrived, or the recognizer has been ready for a while: it works. */
        fun isHealthy(now: Long): Boolean {
            val ready = readyAt ?: return hadResults
            return hadResults || now - ready >= HEALTHY_SESSION_MILLIS
        }
    }

    override suspend fun run(): ListenResult {
        var recognizer: ActiveRecognizer? = null
        try {
            while (true) {
                val active = recognizer ?: recognizers.create(preferOnDevice)
                if (active == null) {
                    return ListenResult.Stopped(StopReason.NO_RECOGNIZER)
                }
                recognizer = active

                val setup = RecognizerSetup(active.isOnDevice, usesOwnMicrophone)
                val sessionResult = runSession(active, setup)
                val finished = when (sessionResult) {
                    SessionResult.WakeWordHeard -> return ListenResult.WakeWordHeard
                    is SessionResult.Finished -> sessionResult
                }

                val decision = policy.decide(finished.outcome, finished.wasHealthy, setup)
                when (decision) {
                    is RestartDecision.Restart -> {
                        if (decision.recreateRecognizer) {
                            active.destroy()
                            recognizer = null
                        }
                        if (decision.silenced) {
                            listener.onPaused(PauseReason.MICROPHONE_SILENCED)
                        }
                        delay(decision.delayMillis)
                    }

                    RestartDecision.UseDefaultRecognizer -> {
                        preferOnDevice = false
                        active.destroy()
                        recognizer = null
                        delay(RestartPolicy.RESTART_DELAY_MILLIS)
                    }

                    RestartDecision.UseRecognizerMicrophone -> {
                        usesOwnMicrophone = false
                        delay(RestartPolicy.RESTART_DELAY_MILLIS)
                    }

                    is RestartDecision.Stop -> return ListenResult.Stopped(decision.reason)
                }
            }
        } finally {
            recognizer?.destroy()
        }
    }

    private suspend fun runSession(active: ActiveRecognizer, setup: RecognizerSetup): SessionResult = coroutineScope {
        val sessionEvents = Channel<RecognizerEvent>(Channel.UNLIMITED)
        val recognitionListener = SessionListener(sessionEvents)
        val speechRecognizer = active.speechRecognizer

        // A listener per session: callbacks that a cancelled session still delivers go to its closed channel.
        speechRecognizer.setRecognitionListener(recognitionListener)

        val ticker = launch {
            while (true) {
                delay(CHECK_INTERVAL_MILLIS)
                sessionEvents.trySend(RecognizerEvent.Tick)
            }
        }

        var feed: MicrophoneFeed? = null
        try {
            var audioSource: ParcelFileDescriptor? = null
            if (setup.usesOwnMicrophone) {
                val newFeed = createFeed()
                val feedStart = startFeed(newFeed, sessionEvents)
                when (feedStart) {
                    is FeedStart.Started -> {
                        audioSource = feedStart.audioSource
                        feed = newFeed
                    }

                    is FeedStart.Failed -> {
                        val outcome = SessionOutcome.Error(feedStart.errorCode)
                        return@coroutineScope SessionResult.Finished(outcome, wasHealthy = false)
                    }
                }
            }

            val recognizerIntent = RecognizerIntents.build(audioSource)
            speechRecognizer.startListening(recognizerIntent)
            awaitSessionResult(sessionEvents, feed)
        } finally {
            ticker.cancel()
            sessionEvents.close()
            speechRecognizer.cancel()
            feed?.stop()
        }
    }

    private fun startFeed(feed: MicrophoneFeed, sessionEvents: SendChannel<RecognizerEvent>): FeedStart {
        return try {
            val audioSource = feed.start { isSilenced ->
                val event = RecognizerEvent.SilencedChanged(isSilenced)
                sessionEvents.trySend(event)
            }
            FeedStart.Started(audioSource)
        } catch (exception: IOException) {
            Log.w(TAG, "The microphone could not be opened", exception)
            FeedStart.Failed(SpeechRecognizer.ERROR_AUDIO)
        } catch (exception: SecurityException) {
            Log.w(TAG, "Recording is not allowed", exception)
            FeedStart.Failed(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
        }
    }

    private suspend fun awaitSessionResult(
        sessionEvents: ReceiveChannel<RecognizerEvent>,
        feed: MicrophoneFeed?,
    ): SessionResult {
        val progress = SessionProgress(elapsedRealtime())
        val stabilizer = PartialResultStabilizer()

        for (event in sessionEvents) {
            val now = elapsedRealtime()
            when (event) {
                RecognizerEvent.Ready -> {
                    progress.readyAt = now
                    listener.onListening()
                }

                is RecognizerEvent.Partial -> {
                    val match = WakeWordMatcher.findMatch(event.hypotheses)
                    if (stabilizer.shouldFire(match, isFinal = false)) {
                        return SessionResult.WakeWordHeard
                    }
                }

                is RecognizerEvent.Final -> {
                    progress.hadResults = true
                    val match = WakeWordMatcher.findMatch(event.hypotheses)
                    if (stabilizer.shouldFire(match, isFinal = true)) {
                        return SessionResult.WakeWordHeard
                    }
                    stabilizer.reset()
                    if (event.endsSession) {
                        return finished(progress, SessionOutcome.Ended, now)
                    }
                }

                RecognizerEvent.SegmentedSessionEnded -> {
                    return finished(progress, SessionOutcome.Ended, now)
                }

                is RecognizerEvent.Failed -> {
                    // Some recognizers report NO_MATCH before they are even ready; the ready timeout
                    // catches real failures to start.
                    val isEarlyNoMatch = progress.readyAt == null && event.code == SpeechRecognizer.ERROR_NO_MATCH
                    if (!isEarlyNoMatch) {
                        val outcome = SessionOutcome.Error(event.code)
                        return finished(progress, outcome, now)
                    }
                }

                is RecognizerEvent.SilencedChanged -> {
                    if (event.isSilenced) {
                        return finished(progress, SessionOutcome.Silenced, now)
                    }
                }

                RecognizerEvent.Tick -> {
                    val timedOut = checkTimers(progress, feed, now)
                    if (timedOut != null) {
                        return finished(progress, timedOut, now)
                    }
                }
            }
        }

        // The channel only closes in runSession's finally, after this function has returned.
        val outcome = SessionOutcome.Error(SpeechRecognizer.ERROR_CLIENT)
        return finished(progress, outcome, elapsedRealtime())
    }

    private fun checkTimers(progress: SessionProgress, feed: MicrophoneFeed?, now: Long): SessionOutcome? {
        val readyAt = progress.readyAt
        if (readyAt == null) {
            val waitedForReady = now - progress.startedAt
            if (waitedForReady >= READY_TIMEOUT_MILLIS) {
                return SessionOutcome.ReadyTimeout
            }
            return null
        }

        if (feed != null) {
            val isPastGrace = now - readyAt >= PIPE_GRACE_MILLIS
            val isStalled = feed.millisSinceLastWrite() >= PIPE_STALL_MILLIS
            if (isPastGrace && isStalled) {
                return SessionOutcome.AudioNotRead
            }
        }

        val sessionAge = now - progress.startedAt
        if (sessionAge >= MAX_SESSION_MILLIS) {
            return SessionOutcome.Rotated
        }
        return null
    }

    private fun finished(progress: SessionProgress, outcome: SessionOutcome, now: Long): SessionResult {
        val wasHealthy = progress.isHealthy(now)
        return SessionResult.Finished(outcome, wasHealthy)
    }

    /** Forwards one session's callbacks into its channel. */
    private class SessionListener(private val events: SendChannel<RecognizerEvent>) : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            events.trySend(RecognizerEvent.Ready)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val hypotheses = hypotheses(partialResults)
            events.trySend(RecognizerEvent.Partial(hypotheses))
        }

        override fun onResults(results: Bundle?) {
            val hypotheses = hypotheses(results)
            events.trySend(RecognizerEvent.Final(hypotheses, endsSession = true))
        }

        override fun onSegmentResults(segmentResults: Bundle) {
            val hypotheses = hypotheses(segmentResults)
            events.trySend(RecognizerEvent.Final(hypotheses, endsSession = false))
        }

        override fun onEndOfSegmentedSession() {
            events.trySend(RecognizerEvent.SegmentedSessionEnded)
        }

        override fun onError(error: Int) {
            events.trySend(RecognizerEvent.Failed(error))
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        private fun hypotheses(results: Bundle?): List<String> {
            val recognized = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            return recognized ?: emptyList()
        }
    }

    companion object {
        private const val TAG = "WakeWordLoop"

        const val CHECK_INTERVAL_MILLIS = 500L
        const val READY_TIMEOUT_MILLIS = 5_000L

        /** After this long ready, a session counts as working even without results. */
        const val HEALTHY_SESSION_MILLIS = 30_000L

        /** Give a recognizer this long after it is ready before judging whether it reads the pipe. */
        const val PIPE_GRACE_MILLIS = 3_000L

        /** The pipe holds about 2 s of audio; writes blocked this long mean nobody reads it. */
        const val PIPE_STALL_MILLIS = 2_500L

        /** Long sessions are restarted, since recognizers may limit their length. */
        const val MAX_SESSION_MILLIS = 10L * 60L * 1_000L
    }
}
