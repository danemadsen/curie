package com.danemadsen.curie.assistant.wakeword

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Robolectric runs the framework's real AudioRecordingMonitorImpl, which starts a thread for each
 * AudioRecord with a registered recording callback.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class MicrophoneFeedTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO)
    }

    /** Handler threads named "android.media.AudioRecordingMonitor.RecordingCallback". */
    private fun recordingCallbackThreads(): Set<Thread> {
        val threads = Thread.getAllStackTraces().keys
        val callbackThreads = threads.filter { thread -> thread.name.contains(RECORDING_CALLBACK_THREAD) }
        return callbackThreads.toSet()
    }

    @Test
    fun startingDeliversThePipeAndRegistersTheSilenceCallback() {
        val threadsBefore = recordingCallbackThreads()
        val feed = AudioRecordFeed(context)

        val readSide = feed.start { }

        assertTrue(readSide.fileDescriptor.valid())
        val newThreads = recordingCallbackThreads() - threadsBefore
        assertEquals(1, newThreads.size)

        feed.stop()
    }

    @Test
    fun stoppingEndsTheSilenceCallbackOfEverySession() {
        val threadsBefore = recordingCallbackThreads()

        repeat(SESSIONS) {
            val feed = AudioRecordFeed(context)
            feed.start { }
            feed.stop()
        }

        // quit() ends a handler thread after its current message: give each a moment to finish.
        val newThreads = recordingCallbackThreads() - threadsBefore
        for (thread in newThreads) {
            thread.join(THREAD_END_TIMEOUT_MILLIS)
        }
        val stillRunning = newThreads.filter { thread -> thread.isAlive }
        assertEquals(emptyList<Thread>(), stillRunning)
    }

    private companion object {
        const val RECORDING_CALLBACK_THREAD = "AudioRecordingMonitor"
        const val SESSIONS = 3
        const val THREAD_END_TIMEOUT_MILLIS = 2_000L
    }
}
