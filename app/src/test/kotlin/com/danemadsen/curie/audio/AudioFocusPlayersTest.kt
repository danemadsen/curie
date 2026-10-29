package com.danemadsen.curie.audio

import android.app.Application
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The chat's players and the audio focus: no sound without it, and an end when it is gone for good. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AudioFocusPlayersTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val audioManager = shadowOf(context.getSystemService(AudioManager::class.java))

    @Test
    fun aReadAloudDoesNotStartWhileAnotherAppKeepsTheAudio() {
        audioManager.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        val player = SpeechPlayer(context, holdsFocus = true)

        try {
            assertFalse(player.begin())
        } finally {
            player.release()
        }
    }

    @Test
    fun anotherAppTakingTheAudioForGoodEndsAReadAloud() = runTest {
        audioManager.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val player = SpeechPlayer(context, holdsFocus = true)
        try {
            assertTrue(player.begin())
            val finished = async(start = CoroutineStart.UNDISPATCHED) { player.awaitFinished() }

            val request = assertNotNull(audioManager.lastAudioFocusRequest)
            request.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)

            // The answer ends (it does not wait, paused, for ever) and the focus request is given up
            assertFalse(finished.await())
            assertSame(request.audioFocusRequest, audioManager.lastAbandonedAudioFocusRequest)
        } finally {
            player.release()
        }
    }

    @Test
    fun aClipDoesNotPlayWhileAnotherAppKeepsTheAudio() {
        audioManager.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        val scope = CoroutineScope(Job())
        val clips = ClipPlayer(context, scope)

        val isPlaying = clips.play("clip", File(context.cacheDir, "voice.wav"))

        assertFalse(isPlaying)
        assertNull(clips.state.value)
        clips.release()
        scope.cancel()
    }
}
