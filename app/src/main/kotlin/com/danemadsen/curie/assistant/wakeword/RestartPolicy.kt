package com.danemadsen.curie.assistant.wakeword

import android.speech.SpeechRecognizer
import kotlin.math.min
import kotlin.random.Random

/** How one recognition session ended. */
internal sealed interface SessionOutcome {
    /** Results arrived or the segmented session closed: the normal end of a session. */
    data object Ended : SessionOutcome

    /** A long session was ended on purpose; recognizers may limit session length. */
    data object Rotated : SessionOutcome

    /** The recognizer never reported it was ready. */
    data object ReadyTimeout : SessionOutcome

    /** The recognizer stopped reading Curie's audio pipe: it ignores EXTRA_AUDIO_SOURCE and opened the microphone itself. */
    data object AudioNotRead : SessionOutcome

    /** Android gave the microphone to another app (a call, a camcorder, a recorder in front). */
    data object Silenced : SessionOutcome

    /** A SpeechRecognizer.ERROR_* code, or a value beyond them, which the documentation asks clients to expect. */
    data class Error(val code: Int) : SessionOutcome
}

/** Why listening cannot go on until something changes outside the listener. */
internal enum class StopReason {
    /** No RECORD_AUDIO, or no microphone access in the background (while-in-use). */
    NO_PERMISSION,

    /** The recognizer does not support English (US) at all. */
    LANGUAGE_NOT_SUPPORTED,

    /** No speech recognition service is installed. */
    NO_RECOGNIZER,

    /** Curie heard its name but nothing could show: it is not the active assistant and notifications are blocked. */
    CANNOT_OPEN_CONVERSATION,
}

internal sealed interface RestartDecision {
    /**
     * Start the next session after [delayMillis]; [recreateRecognizer] destroys the SpeechRecognizer
     * first. [silenced] means the wait is for the microphone to be released by another app.
     */
    data class Restart(
        val delayMillis: Long,
        val recreateRecognizer: Boolean,
        val silenced: Boolean = false,
    ) : RestartDecision

    /** The on-device recognizer cannot do English (US): use the default one. */
    data object UseDefaultRecognizer : RestartDecision

    /** The recognizer does not take Curie's audio: let it open the microphone itself. */
    data object UseRecognizerMicrophone : RestartDecision

    data class Stop(val reason: StopReason) : RestartDecision
}

/** What the session that just ended ran with. */
internal data class RecognizerSetup(
    val isOnDevice: Boolean,

    /** Curie recorded and fed the audio through EXTRA_AUDIO_SOURCE (API 33+). */
    val usesOwnMicrophone: Boolean,
)

/**
 * Decides what follows a recognition session, following the recognizer's error contract: retry the
 * normal ends quickly, back off exponentially (with jitter) on errors, recreate the recognizer when
 * its connection is broken, and stop on errors a retry cannot fix. Restarts are also capped per
 * minute, so a recognizer that ends sessions instantly cannot spin.
 */
internal class RestartPolicy(
    private val elapsedRealtime: () -> Long,
    private val random: Random,
) {
    private var backoffAttempts = 0
    private val recentRestarts = ArrayDeque<Long>()

    /** [wasHealthy]: the session produced results or ran for a while, so earlier errors are forgiven. */
    fun decide(outcome: SessionOutcome, wasHealthy: Boolean, setup: RecognizerSetup): RestartDecision {
        if (wasHealthy) {
            backoffAttempts = 0
        }

        return when (outcome) {
            SessionOutcome.Ended, SessionOutcome.Rotated -> restart(RESTART_DELAY_MILLIS, recreate = false)
            SessionOutcome.ReadyTimeout -> restart(backoff(1_000, 60_000), recreate = true)
            SessionOutcome.Silenced -> silencedRestart()
            SessionOutcome.AudioNotRead -> audioNotRead(setup)
            is SessionOutcome.Error -> decideForError(outcome.code, setup)
        }
    }

    private fun decideForError(code: Int, setup: RecognizerSetup): RestartDecision {
        return when (code) {
            // Nothing (recognisable) was said: normal in a listening loop.
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            -> restart(RESTART_DELAY_MILLIS, recreate = false)

            // Another client is using the recognizer (a second start gets BUSY on API 31-33, CLIENT on 34+).
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_AUDIO,
            -> restart(backoff(1_000, 60_000), recreate = false)

            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
            -> restart(backoff(500, 30_000), recreate = true)

            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER,
            -> restart(backoff(2_000, 300_000), recreate = true)

            // Too many connections from Curie, or the component is not a recognizer: wait long.
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> restart(backoff(30_000, 600_000), recreate = true)

            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> languageNotSupported(setup)
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> languageUnavailable(setup)

            // Retrying would only fail again: no RECORD_AUDIO or no microphone in the background.
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> RestartDecision.Stop(StopReason.NO_PERMISSION)

            else -> restart(backoff(1_000, 60_000), recreate = false)
        }
    }

    /** No retry helps; only the on-device recognizer still leaves the default one to try. */
    private fun languageNotSupported(setup: RecognizerSetup): RestartDecision {
        if (setup.isOnDevice) {
            return RestartDecision.UseDefaultRecognizer
        }
        return RestartDecision.Stop(StopReason.LANGUAGE_NOT_SUPPORTED)
    }

    /**
     * Supported, but not usable right now: the language pack is not downloaded yet, or (as Google's
     * recognizer reports it) the network failed and there is no offline pack to fall back on. The
     * on-device recognizer leaves the default one to try; the default one is retried like a network error.
     */
    private fun languageUnavailable(setup: RecognizerSetup): RestartDecision {
        if (setup.isOnDevice) {
            return RestartDecision.UseDefaultRecognizer
        }
        return restart(backoff(2_000, 300_000), recreate = true)
    }

    private fun audioNotRead(setup: RecognizerSetup): RestartDecision {
        if (setup.usesOwnMicrophone) {
            return RestartDecision.UseRecognizerMicrophone
        }
        return restart(backoff(1_000, 60_000), recreate = true)
    }

    private fun silencedRestart(): RestartDecision {
        val delayMillis = rateLimited(SILENCED_RETRY_MILLIS)
        return RestartDecision.Restart(delayMillis, recreateRecognizer = false, silenced = true)
    }

    private fun restart(delayMillis: Long, recreate: Boolean): RestartDecision {
        val allowedDelay = rateLimited(delayMillis)
        return RestartDecision.Restart(allowedDelay, recreate)
    }

    /** baseMillis doubled per attempt, capped, then scaled by a random factor in [0.5, 1) against lockstep retries. */
    private fun backoff(baseMillis: Long, capMillis: Long): Long {
        val exponent = min(backoffAttempts, MAX_BACKOFF_EXPONENT)
        backoffAttempts += 1

        val rawDelay = baseMillis shl exponent
        val cappedDelay = min(rawDelay, capMillis)
        val jitter = MIN_JITTER + random.nextDouble() * (1.0 - MIN_JITTER)
        return (cappedDelay * jitter).toLong()
    }

    /** Stretches [delayMillis] so that no more than [MAX_RESTARTS_PER_MINUTE] restarts happen in a minute. */
    private fun rateLimited(delayMillis: Long): Long {
        val now = elapsedRealtime()
        while (recentRestarts.isNotEmpty() && recentRestarts.first() <= now - RATE_WINDOW_MILLIS) {
            recentRestarts.removeFirst()
        }

        var allowedDelay = delayMillis
        if (recentRestarts.size >= MAX_RESTARTS_PER_MINUTE) {
            val oldestRestart = recentRestarts.first()
            val windowFreesAt = oldestRestart + RATE_WINDOW_MILLIS
            allowedDelay = maxOf(delayMillis, windowFreesAt - now)
            recentRestarts.removeFirst()
        }

        recentRestarts.addLast(now + allowedDelay)
        return allowedDelay
    }

    companion object {
        const val RESTART_DELAY_MILLIS = 250L
        const val SILENCED_RETRY_MILLIS = 30_000L
        const val MAX_RESTARTS_PER_MINUTE = 20
        const val RATE_WINDOW_MILLIS = 60_000L

        private const val MAX_BACKOFF_EXPONENT = 10
        private const val MIN_JITTER = 0.5
    }
}
