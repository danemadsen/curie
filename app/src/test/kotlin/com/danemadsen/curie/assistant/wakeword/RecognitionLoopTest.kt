package com.danemadsen.curie.assistant.wakeword

import android.app.Application
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSpeechRecognizer
import java.io.File
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class RecognizerIntentsTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    private fun audioSource(): ParcelFileDescriptor {
        val file = File(context.cacheDir, "audio.pcm")
        file.writeBytes(ByteArray(0))
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    @Test
    fun listensForEnglishWithPartialResults() {
        val intent = RecognizerIntents.build(audioSource = null, sdkInt = Build.VERSION_CODES.VANILLA_ICE_CREAM)

        assertEquals(RecognizerIntent.ACTION_RECOGNIZE_SPEECH, intent.action)
        assertEquals(RecognizerIntent.LANGUAGE_MODEL_FREE_FORM, intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL))
        assertEquals("en-US", intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
        assertTrue(intent.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false))
        assertEquals(listOf("Hey Curie", "Curie"), intent.getStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS))
        assertFalse(intent.hasExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE))
        assertFalse(intent.hasExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION))
    }

    @Test
    fun feedsCuriesOwnAudioAsOneSegmentedSession() {
        val source = audioSource()

        val intent = RecognizerIntents.build(source, sdkInt = Build.VERSION_CODES.TIRAMISU)

        @Suppress("DEPRECATION")
        val passedSource = intent.getParcelableExtra<ParcelFileDescriptor>(RecognizerIntent.EXTRA_AUDIO_SOURCE)
        assertSame(source, passedSource)
        assertEquals(1, intent.getIntExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 0))
        assertEquals(AudioFormat.ENCODING_PCM_16BIT, intent.getIntExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, 0))
        assertEquals(16_000, intent.getIntExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 0))
        assertEquals(RecognizerIntent.EXTRA_AUDIO_SOURCE, intent.getStringExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION))
    }

    @Test
    fun beforeAndroid13ThereIsNoBiasingAndNoAudioSource() {
        val intent = RecognizerIntents.build(audioSource(), sdkInt = Build.VERSION_CODES.S_V2)

        assertFalse(intent.hasExtra(RecognizerIntent.EXTRA_BIASING_STRINGS))
        assertFalse(intent.hasExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE))
        assertTrue(intent.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false))
    }

    @Test
    fun recognisesTheWakeLanguageInAnySpelling() {
        assertTrue(RecognizerIntents.isWakeLanguage("en-US"))
        assertTrue(RecognizerIntents.isWakeLanguage("en_us"))
        assertFalse(RecognizerIntents.isWakeLanguage("en-GB"))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class RecognitionLoopTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val states = mutableListOf<String>()
    private val factoryRequests = mutableListOf<Boolean>()
    private var createsOnDevice = false

    private val loopListener = object : RecognitionLoopListener {
        override fun onListening() {
            states.add("listening")
        }

        override fun onPaused(reason: PauseReason) {
            states.add("paused:$reason")
        }
    }

    private val factory = RecognizerFactory { preferOnDevice ->
        factoryRequests.add(preferOnDevice)
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val isOnDevice = createsOnDevice && preferOnDevice
        ActiveRecognizer(recognizer, isOnDevice)
    }

    /** Records into a file instead of the microphone; can pretend the recognizer stopped reading. */
    private inner class FakeFeed : MicrophoneFeed {
        var starts = 0
        var stops = 0
        var stalledMillis = 0L
        var onSilencedChanged: ((Boolean) -> Unit)? = null

        override fun start(onSilencedChanged: (Boolean) -> Unit): ParcelFileDescriptor {
            starts += 1
            this.onSilencedChanged = onSilencedChanged
            val file = File(context.cacheDir, "feed-$starts.pcm")
            file.writeBytes(ByteArray(0))
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        override fun millisSinceLastWrite(): Long = stalledMillis

        override fun stop() {
            stops += 1
        }
    }

    @Before
    fun setUp() {
        ShadowSpeechRecognizer.reset()
    }

    private fun TestScope.newLoop(feed: FakeFeed? = null): RecognitionLoop {
        val clock = { testScheduler.currentTime }
        val policy = RestartPolicy(clock, Random(1))
        val createFeed: () -> MicrophoneFeed = { feed ?: error("This loop has no microphone feed") }
        return RecognitionLoop(factory, createFeed, policy, clock, canUseOwnMicrophone = feed != null, loopListener)
    }

    private fun TestScope.start(loop: RecognitionLoop): Deferred<ListenResult> {
        val result = backgroundScope.async { loop.run() }
        pump()
        return result
    }

    /** Runs what is due now: coroutines, then the main looper (SpeechRecognizer posts to it). */
    private fun TestScope.pump() {
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
    }

    private fun TestScope.advance(millis: Long) {
        advanceTimeBy(millis)
        pump()
    }

    private fun recognizer(): ShadowSpeechRecognizer {
        return shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
    }

    private fun results(vararg hypotheses: String): Bundle {
        val bundle = Bundle()
        bundle.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(*hypotheses))
        return bundle
    }

    @Test
    fun theSameHitInTwoPartialResultsWakesCurie() = runTest {
        val result = start(newLoop())
        val shadow = recognizer()
        assertNotNull(shadow.lastRecognizerIntent)

        shadow.triggerOnReadyForSpeech(Bundle())
        shadow.triggerOnPartialResults(results("hey"))
        shadow.triggerOnPartialResults(results("hey curie"))
        pump()
        assertEquals(listOf("listening"), states)
        assertFalse(result.isCompleted)

        shadow.triggerOnPartialResults(results("hey curie what's"))
        pump()

        assertEquals(ListenResult.WakeWordHeard, result.await())
        assertTrue(shadow.isDestroyed)
    }

    @Test
    fun aFinalResultWakesCurieAtOnce() = runTest {
        val result = start(newLoop())
        val shadow = recognizer()

        shadow.triggerOnReadyForSpeech(Bundle())
        shadow.triggerOnResults(results("okay, hey curry"))
        pump()

        assertEquals(ListenResult.WakeWordHeard, result.await())
    }

    @Test
    fun nothingSaidRestartsTheSameRecognizerQuickly() = runTest {
        val result = start(newLoop())
        val shadow = recognizer()
        shadow.triggerOnReadyForSpeech(Bundle())
        shadow.triggerOnError(SpeechRecognizer.ERROR_NO_MATCH)
        pump()
        val firstIntent = shadow.lastRecognizerIntent

        advance(RestartPolicy.RESTART_DELAY_MILLIS - 1)
        assertSame(firstIntent, shadow.lastRecognizerIntent)
        advance(1)

        assertNotSame(firstIntent, shadow.lastRecognizerIntent)
        assertEquals(1, factoryRequests.size)
        assertFalse(result.isCompleted)
    }

    @Test
    fun anEarlyNoMatchBeforeReadyIsIgnored() = runTest {
        val result = start(newLoop())
        val shadow = recognizer()
        val firstIntent = shadow.lastRecognizerIntent

        shadow.triggerOnError(SpeechRecognizer.ERROR_NO_MATCH)
        advance(1_000)

        assertSame(firstIntent, shadow.lastRecognizerIntent)
        assertFalse(result.isCompleted)
    }

    @Test
    fun aRecognizerThatNeverGetsReadyIsRecreated() = runTest {
        val result = start(newLoop())
        val firstRecognizer = recognizer()

        advance(RecognitionLoop.READY_TIMEOUT_MILLIS)
        assertTrue(firstRecognizer.isDestroyed)
        advance(1_000)

        assertEquals(2, factoryRequests.size)
        assertFalse(result.isCompleted)
    }

    @Test
    fun missingMicrophoneAccessStopsListening() = runTest {
        val result = start(newLoop())
        val shadow = recognizer()

        shadow.triggerOnReadyForSpeech(Bundle())
        shadow.triggerOnError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
        pump()

        assertEquals(ListenResult.Stopped(StopReason.NO_PERMISSION), result.await())
        assertTrue(shadow.isDestroyed)
    }

    @Test
    fun anOnDeviceRecognizerWithoutEnglishGivesWayToTheDefaultOne() = runTest {
        createsOnDevice = true
        val result = start(newLoop())
        val onDevice = recognizer()

        onDevice.triggerOnError(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
        pump()
        assertTrue(onDevice.isDestroyed)
        advance(RestartPolicy.RESTART_DELAY_MILLIS)

        assertEquals(listOf(true, false), factoryRequests)
        assertFalse(result.isCompleted)
    }

    @Test
    fun curiesOwnAudioFeedsTheSegmentedSession() = runTest {
        val feed = FakeFeed()
        val result = start(newLoop(feed))
        val shadow = recognizer()

        val intent: Intent? = shadow.lastRecognizerIntent
        assertTrue(intent?.hasExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE) == true)
        assertEquals(1, feed.starts)

        shadow.triggerOnReadyForSpeech(Bundle())
        shadow.triggerOnSegmentResults(results("hey curie"))
        pump()

        assertEquals(ListenResult.WakeWordHeard, result.await())
        assertEquals(1, feed.stops)
    }

    @Test
    fun aRecognizerThatIgnoresThePipeOpensTheMicrophoneItself() = runTest {
        val feed = FakeFeed()
        val result = start(newLoop(feed))
        val shadow = recognizer()
        shadow.triggerOnReadyForSpeech(Bundle())
        feed.stalledMillis = RecognitionLoop.PIPE_STALL_MILLIS

        advance(RecognitionLoop.PIPE_GRACE_MILLIS + RecognitionLoop.CHECK_INTERVAL_MILLIS)
        advance(RestartPolicy.RESTART_DELAY_MILLIS)

        val classicIntent = shadow.lastRecognizerIntent
        assertNotNull(classicIntent)
        assertFalse(classicIntent?.hasExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE) == true)
        assertEquals(1, feed.starts)
        assertEquals(1, feed.stops)
        assertFalse(result.isCompleted)
    }

    @Test
    fun aSilencedMicrophonePausesAndRetriesLater() = runTest {
        val feed = FakeFeed()
        val result = start(newLoop(feed))
        recognizer().triggerOnReadyForSpeech(Bundle())
        pump()

        feed.onSilencedChanged?.invoke(true)
        pump()
        assertEquals(listOf("listening", "paused:MICROPHONE_SILENCED"), states)
        assertEquals(1, feed.stops)

        advance(RestartPolicy.SILENCED_RETRY_MILLIS - 1)
        assertEquals(1, feed.starts)
        advance(1)

        assertEquals(2, feed.starts)
        assertFalse(result.isCompleted)
    }

    @Test
    fun theEndOfASegmentedSessionStartsTheNextOne() = runTest {
        val feed = FakeFeed()
        val result = start(newLoop(feed))
        recognizer().triggerOnReadyForSpeech(Bundle())
        recognizer().triggerOnEndOfSegmentedSession()
        pump()

        advance(RestartPolicy.RESTART_DELAY_MILLIS)

        assertEquals(2, feed.starts)
        assertFalse(result.isCompleted)
    }

    @Test
    fun cancellingReleasesTheRecognizerAndTheMicrophone() = runTest {
        val feed = FakeFeed()
        val result = start(newLoop(feed))
        val shadow = recognizer()
        shadow.triggerOnReadyForSpeech(Bundle())
        pump()

        result.cancel()
        pump()

        assertTrue(shadow.isDestroyed)
        assertEquals(1, feed.stops)
    }
}
