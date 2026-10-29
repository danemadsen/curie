package com.danemadsen.curie.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import androidx.core.content.ContextCompat
import com.danemadsen.curie.engine.Lfm3Omni
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.IOException

/** The microphone cannot be used: no permission, or the platform refused to open it. */
class MicrophoneUnavailableException(message: String, val isPermissionMissing: Boolean = false) :
    IOException(message)

/** What the microphone is recording for, which picks the platform's audio processing. */
enum class RecordingPurpose(val audioSource: Int, val usesVoiceEffects: Boolean) {
    /** A voice message: tuned for speech recognition, no echo cancellation needed. */
    VOICE_MESSAGE(MediaRecorder.AudioSource.VOICE_RECOGNITION, usesVoiceEffects = false),

    /** A call: echo cancellation and noise suppression, so Curie does not hear herself. */
    CALL(MediaRecorder.AudioSource.VOICE_COMMUNICATION, usesVoiceEffects = true),
}

/** The microphone as 16 kHz mono float chunks; collecting opens it, cancelling closes it. */
fun interface MicrophoneSource {
    fun record(purpose: RecordingPurpose): Flow<FloatArray>
}

/**
 * Records 16 kHz mono 16-bit PCM with AudioRecord and emits it as float chunks of [CHUNK_MS].
 * The AudioRecord lives exactly as long as the collection: it is released when the collector
 * stops or fails. Reading blocks, so the flow runs on [Dispatchers.IO].
 */
class AudioRecorder(context: Context) : MicrophoneSource {
    private val appContext = context.applicationContext

    override fun record(purpose: RecordingPurpose): Flow<FloatArray> {
        val chunks = flow {
            val audioRecord = open(purpose)
            val effects = if (purpose.usesVoiceEffects) attachVoiceEffects(audioRecord.audioSessionId) else emptyList()
            try {
                audioRecord.startRecording()
                if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    throw MicrophoneUnavailableException("the microphone is in use by another app")
                }

                val chunk = ShortArray(CHUNK_SAMPLES)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = audioRecord.read(chunk, 0, chunk.size)
                    if (count < 0) {
                        throw MicrophoneUnavailableException("reading the microphone failed ($count)")
                    }
                    if (count > 0) {
                        val samples = toFloats(chunk, count)
                        emit(samples)
                    }
                }
            } finally {
                close(audioRecord, effects)
            }
        }
        return chunks.flowOn(Dispatchers.IO)
    }

    @SuppressLint("MissingPermission")
    private fun open(purpose: RecordingPurpose): AudioRecord {
        val permission = ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO)
        if (permission != PackageManager.PERMISSION_GRANTED) {
            throw MicrophoneUnavailableException("the microphone permission is not granted", isPermissionMissing = true)
        }

        val minimumBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minimumBytes <= 0) {
            throw MicrophoneUnavailableException("16 kHz mono recording is not supported ($minimumBytes)")
        }
        val bufferBytes = maxOf(minimumBytes, SAMPLE_RATE * 2 * BUFFER_MS / 1000)

        val formatBuilder = AudioFormat.Builder()
        formatBuilder.setSampleRate(SAMPLE_RATE)
        formatBuilder.setChannelMask(CHANNEL)
        formatBuilder.setEncoding(ENCODING)
        val format = formatBuilder.build()

        val builder = AudioRecord.Builder()
        builder.setAudioSource(purpose.audioSource)
        builder.setAudioFormat(format)
        builder.setBufferSizeInBytes(bufferBytes)

        val audioRecord = try {
            builder.build()
        } catch (error: UnsupportedOperationException) {
            throw MicrophoneUnavailableException("the microphone cannot record: ${error.message}")
        } catch (error: SecurityException) {
            throw MicrophoneUnavailableException("the microphone permission was refused", isPermissionMissing = true)
        }
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            throw MicrophoneUnavailableException("the microphone could not be opened")
        }
        return audioRecord
    }

    /** The platform's echo canceller and noise suppressor, where the phone has them. */
    private fun attachVoiceEffects(sessionId: Int): List<AudioEffect> {
        val effects = ArrayList<AudioEffect>(2)
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                val echoCanceler = AcousticEchoCanceler.create(sessionId)
                if (echoCanceler != null) {
                    echoCanceler.enabled = true
                    effects.add(echoCanceler)
                }
            }
            if (NoiseSuppressor.isAvailable()) {
                val noiseSuppressor = NoiseSuppressor.create(sessionId)
                if (noiseSuppressor != null) {
                    noiseSuppressor.enabled = true
                    effects.add(noiseSuppressor)
                }
            }
        } catch (error: RuntimeException) {
            // VOICE_COMMUNICATION already applies the platform's own processing on most phones
            Log.w(TAG, "voice effects are unavailable", error)
        }
        return effects
    }

    private fun close(audioRecord: AudioRecord, effects: List<AudioEffect>) {
        try {
            audioRecord.stop()
        } catch (error: IllegalStateException) {
            // never started
        }
        for (effect in effects) {
            effect.release()
        }
        audioRecord.release()
    }

    private fun toFloats(chunk: ShortArray, count: Int): FloatArray {
        val samples = FloatArray(count)
        for (index in 0 until count) {
            samples[index] = chunk[index] / PCM16_SCALE
        }
        return samples
    }

    companion object {
        const val SAMPLE_RATE = Lfm3Omni.INPUT_SAMPLE_RATE
        const val CHUNK_MS = 20

        private const val TAG = "AudioRecorder"
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_MS = 200
        private const val CHUNK_SAMPLES = SAMPLE_RATE * CHUNK_MS / 1000
        private const val PCM16_SCALE = 32768f
    }
}
