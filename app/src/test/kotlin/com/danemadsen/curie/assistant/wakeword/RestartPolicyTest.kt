package com.danemadsen.curie.assistant.wakeword

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RestartPolicyTest {
    /** nextDouble() is always 0, so every backoff takes the lowest jitter factor (0.5). */
    private object LowestJitter : Random() {
        override fun nextBits(bitCount: Int): Int = 0
    }

    /** nextDouble() is just below 1, so every backoff takes (almost) the full delay. */
    private object HighestJitter : Random() {
        override fun nextBits(bitCount: Int): Int = (1 shl bitCount) - 1
    }

    private var now = 1_000_000L
    private val classicSetup = RecognizerSetup(isOnDevice = false, usesOwnMicrophone = false)
    private val onDeviceSetup = RecognizerSetup(isOnDevice = true, usesOwnMicrophone = true)

    private fun policy(random: Random = LowestJitter): RestartPolicy {
        return RestartPolicy(elapsedRealtime = { now }, random = random)
    }

    private fun error(code: Int): SessionOutcome {
        return SessionOutcome.Error(code)
    }

    @Test
    fun normalEndsRestartQuicklyWithTheSameRecognizer() {
        val restartPolicy = policy()

        val afterResults = restartPolicy.decide(SessionOutcome.Ended, wasHealthy = true, classicSetup)
        val afterNoMatch = restartPolicy.decide(error(SpeechRecognizer.ERROR_NO_MATCH), false, classicSetup)
        val afterTimeout = restartPolicy.decide(error(SpeechRecognizer.ERROR_SPEECH_TIMEOUT), false, classicSetup)

        val quickRestart = RestartDecision.Restart(RestartPolicy.RESTART_DELAY_MILLIS, recreateRecognizer = false)
        assertEquals(quickRestart, afterResults)
        assertEquals(quickRestart, afterNoMatch)
        assertEquals(quickRestart, afterTimeout)
    }

    @Test
    fun aBusyRecognizerBacksOffExponentiallyUpToTheCap() {
        val restartPolicy = policy(HighestJitter)
        val busy = error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)

        val delays = (0 until 8).map {
            val decision = restartPolicy.decide(busy, wasHealthy = false, classicSetup) as RestartDecision.Restart
            assertEquals(false, decision.recreateRecognizer)
            decision.delayMillis
        }

        // 1 s doubling up to the 60 s cap, each scaled by a jitter just under 1.
        val expectedCeilings = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L)
        for (index in delays.indices) {
            val ceiling = expectedCeilings[index]
            assertTrue("delay $index", delays[index] <= ceiling)
            assertTrue("delay $index", delays[index] >= ceiling * 99 / 100)
        }
    }

    @Test
    fun jitterNeverGoesBelowHalfTheDelay() {
        val restartPolicy = policy(LowestJitter)

        val decision = restartPolicy.decide(error(SpeechRecognizer.ERROR_AUDIO), false, classicSetup)

        assertEquals(RestartDecision.Restart(500L, recreateRecognizer = false), decision)
    }

    @Test
    fun brokenConnectionsRecreateTheRecognizer() {
        val clientError = policy().decide(error(SpeechRecognizer.ERROR_CLIENT), false, classicSetup)
        val disconnected = policy().decide(error(SpeechRecognizer.ERROR_SERVER_DISCONNECTED), false, classicSetup)
        val network = policy().decide(error(SpeechRecognizer.ERROR_NETWORK), false, classicSetup)
        val readyTimeout = policy().decide(SessionOutcome.ReadyTimeout, false, classicSetup)

        assertEquals(RestartDecision.Restart(250L, recreateRecognizer = true), clientError)
        assertEquals(RestartDecision.Restart(250L, recreateRecognizer = true), disconnected)
        assertEquals(RestartDecision.Restart(1_000L, recreateRecognizer = true), network)
        assertEquals(RestartDecision.Restart(500L, recreateRecognizer = true), readyTimeout)
    }

    @Test
    fun tooManyRequestsWaitsLongAndRecreates() {
        val decision = policy().decide(error(SpeechRecognizer.ERROR_TOO_MANY_REQUESTS), false, classicSetup)

        assertEquals(RestartDecision.Restart(15_000L, recreateRecognizer = true), decision)
    }

    @Test
    fun missingPermissionStopsInsteadOfRetrying() {
        val decision = policy().decide(error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS), false, classicSetup)

        assertEquals(RestartDecision.Stop(StopReason.NO_PERMISSION), decision)
    }

    @Test
    fun aLanguageProblemMovesFromTheOnDeviceToTheDefaultRecognizer() {
        val unavailable = error(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
        val notSupported = error(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)

        val afterUnavailable = policy().decide(unavailable, false, onDeviceSetup)
        val afterNotSupported = policy().decide(notSupported, false, onDeviceSetup)

        assertEquals(RestartDecision.UseDefaultRecognizer, afterUnavailable)
        assertEquals(RestartDecision.UseDefaultRecognizer, afterNotSupported)
    }

    @Test
    fun anUnsupportedLanguageStopsTheDefaultRecognizer() {
        val decision = policy().decide(error(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED), false, classicSetup)

        assertEquals(RestartDecision.Stop(StopReason.LANGUAGE_NOT_SUPPORTED), decision)
    }

    /**
     * Google's recognizer reports ERROR_LANGUAGE_UNAVAILABLE when its network request fails and no
     * offline language pack is installed (seen on an Android 16 emulator right after boot): it passes.
     */
    @Test
    fun aLanguageThatIsUnavailableForNowIsRetriedLikeANetworkError() {
        val restartPolicy = policy(HighestJitter)
        val unavailable = error(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)

        val first = restartPolicy.decide(unavailable, false, classicSetup) as RestartDecision.Restart
        val second = restartPolicy.decide(unavailable, false, classicSetup) as RestartDecision.Restart

        // 2 s doubling (the network errors' backoff), each scaled by a jitter just under 1.
        assertTrue(first.recreateRecognizer)
        assertTrue(first.delayMillis in 1_900L..2_000L)
        assertTrue(second.delayMillis in 3_900L..4_000L)
    }

    @Test
    fun aRecognizerThatIgnoresThePipeGetsTheMicrophone() {
        val decision = policy().decide(SessionOutcome.AudioNotRead, false, onDeviceSetup)

        assertEquals(RestartDecision.UseRecognizerMicrophone, decision)
    }

    @Test
    fun aSilencedMicrophoneWaitsForTheOtherApp() {
        val decision = policy().decide(SessionOutcome.Silenced, false, onDeviceSetup)

        val expected = RestartDecision.Restart(RestartPolicy.SILENCED_RETRY_MILLIS, false, silenced = true)
        assertEquals(expected, decision)
    }

    @Test
    fun unknownErrorCodesBackOff() {
        val decision = policy().decide(error(1_234), false, classicSetup)

        assertEquals(RestartDecision.Restart(500L, recreateRecognizer = false), decision)
    }

    @Test
    fun aHealthySessionForgivesEarlierErrors() {
        val restartPolicy = policy()
        val busy = error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
        restartPolicy.decide(busy, false, classicSetup)
        restartPolicy.decide(busy, false, classicSetup)
        restartPolicy.decide(busy, false, classicSetup)

        val afterHealthySession = restartPolicy.decide(busy, wasHealthy = true, classicSetup)

        assertEquals(RestartDecision.Restart(500L, recreateRecognizer = false), afterHealthySession)
    }

    @Test
    fun restartsAreCappedPerMinute() {
        val restartPolicy = policy()
        val noMatch = error(SpeechRecognizer.ERROR_NO_MATCH)

        repeat(RestartPolicy.MAX_RESTARTS_PER_MINUTE) {
            val decision = restartPolicy.decide(noMatch, false, classicSetup) as RestartDecision.Restart
            assertEquals(RestartPolicy.RESTART_DELAY_MILLIS, decision.delayMillis)
            now += decision.delayMillis
        }

        // The 21st restart waits until the first of the 20 is a minute old.
        val throttled = restartPolicy.decide(noMatch, false, classicSetup) as RestartDecision.Restart
        val firstRestartAt = 1_000_000L + RestartPolicy.RESTART_DELAY_MILLIS
        val expectedDelay = firstRestartAt + RestartPolicy.RATE_WINDOW_MILLIS - now
        assertEquals(expectedDelay, throttled.delayMillis)
    }

    @Test
    fun theCapForgetsRestartsOlderThanAMinute() {
        val restartPolicy = policy()
        val noMatch = error(SpeechRecognizer.ERROR_NO_MATCH)
        repeat(RestartPolicy.MAX_RESTARTS_PER_MINUTE) {
            restartPolicy.decide(noMatch, false, classicSetup)
        }

        now += RestartPolicy.RATE_WINDOW_MILLIS + RestartPolicy.RESTART_DELAY_MILLIS

        val decision = restartPolicy.decide(noMatch, false, classicSetup) as RestartDecision.Restart
        assertEquals(RestartPolicy.RESTART_DELAY_MILLIS, decision.delayMillis)
    }
}
