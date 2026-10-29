package com.danemadsen.curie.assistant.wakeword

import android.annotation.SuppressLint
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.ParcelFileDescriptor
import android.speech.RecognizerIntent

/** The RecognizerIntent for listening to "Hey Curie". */
internal object RecognizerIntents {
    /** The wake phrase and the matcher are English. */
    const val LANGUAGE_TAG = "en-US"

    const val SAMPLE_RATE_HZ = 16_000

    private const val MAX_RESULTS = 3
    private val BIASING_STRINGS = arrayListOf("Hey Curie", "Curie")

    /**
     * With [audioSource] (API 33+) the recognizer reads Curie's own recording, 16 kHz mono PCM16, as one
     * segmented session that ends only when Curie closes the audio; without it the recognizer opens the
     * microphone itself for one utterance. [sdkInt] is a parameter so tests can cover each API level;
     * the API 33 extras are only added when it allows them, which lint cannot follow.
     */
    @SuppressLint("InlinedApi")
    fun build(audioSource: ParcelFileDescriptor?, sdkInt: Int = Build.VERSION.SDK_INT): Intent {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE_TAG)
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_RESULTS)

        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            intent.putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, BIASING_STRINGS)

            if (audioSource != null) {
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, audioSource)
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE_HZ)
                intent.putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            }
        }
        return intent
    }

    /** "en-US", "en_us" and "EN-US" all name the same language. */
    fun isWakeLanguage(languageTag: String): Boolean {
        val normalized = languageTag.replace('_', '-')
        return normalized.equals(LANGUAGE_TAG, ignoreCase = true)
    }
}
