package com.danemadsen.curie.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/** The clip that is playing or paused, and where. */
data class ClipPlaybackState(
    val clipId: String,
    val isPlaying: Boolean,
    val positionMs: Long,
)

/** Plays one audio file at a time: voice messages and spoken answers in a chat. */
interface ClipPlayback {
    /** The current clip; null when nothing is playing or paused. */
    val state: StateFlow<ClipPlaybackState?>

    /**
     * Pauses or resumes [clipId] when it is the current clip, else plays [file] as [clipId] from
     * the start. False when it may not play now (another app keeps the audio, such as a phone call).
     */
    fun toggle(clipId: String, file: File): Boolean

    /** Plays [file] as [clipId] from the start; false when it may not play now (see [toggle]). */
    fun play(clipId: String, file: File): Boolean

    fun stop()

    fun release()
}

/**
 * [ClipPlayback] with MediaPlayer, on the main thread of [scope], holding transient audio focus
 * while it plays: other apps' audio pauses, a notification or a call pauses the clip, and an app
 * that takes the audio for good leaves it paused (a tap resumes it).
 */
class ClipPlayer(context: Context, private val scope: CoroutineScope) : ClipPlayback {
    private val attributes = AudioFocus.speechAttributes(AudioAttributes.USAGE_MEDIA)
    private val focus = AudioFocus(context.applicationContext, attributes, ::onFocusChange)
    private val current = MutableStateFlow<ClipPlaybackState?>(null)
    private var player: MediaPlayer? = null
    private var progressJob: Job? = null

    override val state: StateFlow<ClipPlaybackState?> = current.asStateFlow()

    override fun toggle(clipId: String, file: File): Boolean {
        val playing = current.value
        val mediaPlayer = player
        if (playing == null || playing.clipId != clipId || mediaPlayer == null) {
            return play(clipId, file)
        }

        if (playing.isPlaying) {
            mediaPlayer.pause()
            focus.abandon()
            publish(isPlaying = false)
            return true
        }
        if (!focus.request()) {
            return false
        }
        mediaPlayer.start()
        publish(isPlaying = true)
        followProgress()
        return true
    }

    override fun play(clipId: String, file: File): Boolean {
        stop()
        if (!focus.request()) {
            return false
        }

        val mediaPlayer = MediaPlayer()
        player = mediaPlayer
        current.value = ClipPlaybackState(clipId, isPlaying = false, positionMs = 0)
        mediaPlayer.setAudioAttributes(attributes)
        mediaPlayer.setOnPreparedListener { prepared ->
            if (player !== prepared) {
                return@setOnPreparedListener
            }
            prepared.start()
            publish(isPlaying = true)
            followProgress()
        }
        mediaPlayer.setOnCompletionListener { completed ->
            if (player === completed) {
                stop()
            }
        }
        mediaPlayer.setOnErrorListener { failed, what, extra ->
            Log.w(TAG, "cannot play $clipId: $what/$extra")
            if (player === failed) {
                stop()
            }
            true
        }

        try {
            mediaPlayer.setDataSource(file.path)
            mediaPlayer.prepareAsync()
        } catch (error: IOException) {
            Log.w(TAG, "cannot open $file", error)
            stop()
        } catch (error: IllegalStateException) {
            Log.w(TAG, "cannot open $file", error)
            stop()
        }
        return true
    }

    override fun stop() {
        progressJob?.cancel()
        progressJob = null
        val mediaPlayer = player
        player = null
        mediaPlayer?.release()
        focus.abandon()
        current.value = null
    }

    override fun release() {
        stop()
    }

    /** Another app's audio pauses the clip; one that keeps the audio also ends the focus request. */
    private fun onFocusChange(change: FocusChange) {
        if (change == FocusChange.GAINED) {
            return
        }
        val mediaPlayer = player
        val playing = current.value
        if (mediaPlayer != null && playing != null && playing.isPlaying) {
            mediaPlayer.pause()
            publish(isPlaying = false)
        }
        if (change == FocusChange.LOST) {
            focus.abandon()
        }
    }

    private fun publish(isPlaying: Boolean) {
        val playing = current.value ?: return
        val mediaPlayer = player ?: return
        val position = mediaPlayer.currentPosition.toLong()
        current.value = playing.copy(isPlaying = isPlaying, positionMs = position)
    }

    private fun followProgress() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                delay(PROGRESS_INTERVAL_MS)
                val playing = current.value ?: break
                if (!playing.isPlaying) {
                    break
                }
                publish(isPlaying = true)
            }
        }
    }

    private companion object {
        const val TAG = "ClipPlayer"
        const val PROGRESS_INTERVAL_MS = 100L
    }
}
