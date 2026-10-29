package com.danemadsen.curie.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/** What another app's audio means for ours. */
enum class FocusChange {
    /** We have the focus (again): play. */
    GAINED,

    /** Another app plays for a while (a notification, navigation, a call): pause and wait. */
    PAUSE,

    /** Another app took over for good: stop. */
    LOST,
}

/**
 * Transient audio focus for speech: other apps' media pauses while Curie talks. Speech is not
 * worth listening to ducked under other audio, so a "duck" request pauses it too (the system
 * would otherwise lower it itself).
 */
class AudioFocus(
    context: Context,
    private val attributes: AudioAttributes,
    private val onChange: (FocusChange) -> Unit,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var request: AudioFocusRequest? = null

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        val focusChange = when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> FocusChange.GAINED
            AudioManager.AUDIOFOCUS_LOSS -> FocusChange.LOST
            else -> FocusChange.PAUSE
        }
        onChange(focusChange)
    }

    /** Asks for the focus; true when it was granted now (a delayed grant arrives as GAINED). */
    @Synchronized
    fun request(): Boolean {
        var current = request
        if (current == null) {
            current = buildRequest()
            request = current
        }
        val result = audioManager?.requestAudioFocus(current) ?: AudioManager.AUDIOFOCUS_REQUEST_FAILED
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    @Synchronized
    fun abandon() {
        val current = request ?: return
        audioManager?.abandonAudioFocusRequest(current)
        request = null
    }

    private fun buildRequest(): AudioFocusRequest {
        val builder = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        builder.setAudioAttributes(attributes)
        builder.setWillPauseWhenDucked(true)
        builder.setAcceptsDelayedFocusGain(false)
        builder.setOnAudioFocusChangeListener(listener, mainHandler)
        return builder.build()
    }

    companion object {
        /** Attributes of Curie's spoken answers. */
        fun speechAttributes(usage: Int): AudioAttributes {
            val builder = AudioAttributes.Builder()
            builder.setUsage(usage)
            builder.setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            return builder.build()
        }
    }
}
