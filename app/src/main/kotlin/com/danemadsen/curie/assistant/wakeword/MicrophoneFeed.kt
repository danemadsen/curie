package com.danemadsen.curie.assistant.wakeword

import android.Manifest
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresPermission
import java.io.IOException
import java.io.OutputStream

/** Curie's own recording, handed to the recognizer through a pipe (RecognizerIntent.EXTRA_AUDIO_SOURCE). */
internal interface MicrophoneFeed {
    /**
     * Starts recording and returns the pipe's read side for the recognizer. [onSilencedChanged] runs on
     * the main thread when Android silences or unsilences the recording for another app.
     */
    fun start(onSilencedChanged: (Boolean) -> Unit): ParcelFileDescriptor

    /**
     * How long the pipe has gone without taking audio. The pipe holds about 2 s of audio, so a
     * recognizer that stops reading shows here within a few seconds.
     */
    fun millisSinceLastWrite(): Long

    /** Stops recording and closes the pipe, which ends the recognizer's segmented session. */
    fun stop()
}

/**
 * Records 16 kHz mono PCM16 with the VOICE_RECOGNITION source, which (unlike VOICE_COMMUNICATION or a
 * camcorder) is not privacy-sensitive, so calls and other recorders take the microphone over.
 */
internal class AudioRecordFeed(private val context: Context) : MicrophoneFeed {
    private var audioRecord: AudioRecord? = null
    private var silenceCallback: AudioManager.AudioRecordingCallback? = null
    private var pumpThread: Thread? = null
    private var readSide: ParcelFileDescriptor? = null
    private var writeStream: OutputStream? = null

    @Volatile
    private var isPumping = false

    @Volatile
    private var lastWriteUptimeMillis = 0L

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun start(onSilencedChanged: (Boolean) -> Unit): ParcelFileDescriptor {
        check(audioRecord == null) { "The feed is already running" }

        val record = createAudioRecord()
        return try {
            startPumping(record, onSilencedChanged)
        } catch (exception: IOException) {
            releaseRecord(record)
            throw exception
        } catch (exception: IllegalStateException) {
            releaseRecord(record)
            throw IOException("The microphone did not start", exception)
        }
    }

    private fun startPumping(record: AudioRecord, onSilencedChanged: (Boolean) -> Unit): ParcelFileDescriptor {
        val callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                val ownConfig = configs.firstOrNull { config -> config.clientAudioSessionId == record.audioSessionId }
                if (ownConfig != null) {
                    onSilencedChanged(ownConfig.isClientSilenced)
                }
            }
        }
        // Must be registered before startRecording() to see the initial state.
        record.registerAudioRecordingCallback(context.mainExecutor, callback)
        silenceCallback = callback

        val pipe = ParcelFileDescriptor.createPipe()
        val pipeReadSide = pipe[0]
        val pipeWriteSide = pipe[1]
        val outputStream = ParcelFileDescriptor.AutoCloseOutputStream(pipeWriteSide)

        try {
            record.startRecording()
        } catch (exception: IllegalStateException) {
            closeQuietly(outputStream)
            closeQuietly(pipeReadSide)
            throw exception
        }

        audioRecord = record
        readSide = pipeReadSide
        writeStream = outputStream
        isPumping = true
        lastWriteUptimeMillis = SystemClock.uptimeMillis()

        val pump = Runnable { pump(record, outputStream) }
        val thread = Thread(pump, "curie-wake-word-audio")
        thread.start()
        pumpThread = thread

        return pipeReadSide
    }

    override fun millisSinceLastWrite(): Long {
        return SystemClock.uptimeMillis() - lastWriteUptimeMillis
    }

    override fun stop() {
        isPumping = false

        val record = audioRecord
        try {
            // Unblocks a read() waiting for audio.
            record?.stop()
        } catch (exception: IllegalStateException) {
            Log.w(TAG, "The recording had already stopped", exception)
        }

        // Closing the write side ends the recognizer's session (end of audio) and wakes a write()
        // blocked on a full pipe. The read side stayed open until now because startListening() sends
        // the intent, and with it this file descriptor, asynchronously.
        closeQuietly(writeStream)
        writeStream = null
        closeQuietly(readSide)
        readSide = null

        pumpThread?.join(STOP_TIMEOUT_MILLIS)
        pumpThread = null

        if (record != null) {
            releaseRecord(record)
        }
        audioRecord = null
    }

    /**
     * Unregisters the silence callback before releasing: AudioRecord.release() leaves it registered,
     * and with it a handler thread in this process and a listener in the system's audio service.
     */
    private fun releaseRecord(record: AudioRecord) {
        val callback = silenceCallback
        if (callback != null) {
            record.unregisterAudioRecordingCallback(callback)
            silenceCallback = null
        }
        record.release()
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun createAudioRecord(): AudioRecord {
        val formatBuilder = AudioFormat.Builder()
        formatBuilder.setSampleRate(RecognizerIntents.SAMPLE_RATE_HZ)
        formatBuilder.setChannelMask(AudioFormat.CHANNEL_IN_MONO)
        formatBuilder.setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        val audioFormat = formatBuilder.build()

        val minBufferBytes = AudioRecord.getMinBufferSize(
            RecognizerIntents.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferBytes = maxOf(minBufferBytes, HALF_SECOND_BYTES)

        val recordBuilder = AudioRecord.Builder()
        recordBuilder.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        recordBuilder.setAudioFormat(audioFormat)
        recordBuilder.setBufferSizeInBytes(bufferBytes)

        // build() throws UnsupportedOperationException when the recorder cannot be created.
        val record = try {
            recordBuilder.build()
        } catch (exception: UnsupportedOperationException) {
            throw IOException("The microphone could not be opened", exception)
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IOException("The microphone could not be opened")
        }
        return record
    }

    /** Runs on the pump thread: copies 100 ms chunks from the microphone into the pipe. */
    private fun pump(record: AudioRecord, outputStream: OutputStream) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val chunk = ByteArray(CHUNK_BYTES)
        try {
            while (isPumping) {
                val readBytes = record.read(chunk, 0, chunk.size)
                if (readBytes < 0) {
                    Log.w(TAG, "The recording failed with $readBytes")
                    break
                }
                // Blocks once the pipe is full, so lastWriteUptimeMillis stops moving if nobody reads.
                outputStream.write(chunk, 0, readBytes)
                lastWriteUptimeMillis = SystemClock.uptimeMillis()
            }
        } catch (exception: IOException) {
            // EPIPE: the recognizer closed its side (the session ended or its service died).
            Log.d(TAG, "The recognizer stopped reading", exception)
        } finally {
            closeQuietly(outputStream)
        }
    }

    private fun closeQuietly(closeable: AutoCloseable?) {
        try {
            closeable?.close()
        } catch (exception: IOException) {
            Log.w(TAG, "Closing the audio pipe failed", exception)
        }
    }

    private companion object {
        const val TAG = "WakeWordAudio"

        /** 16-bit mono at 16 kHz: 32,000 bytes a second. */
        const val HALF_SECOND_BYTES = 16_000

        const val CHUNK_BYTES = 3_200
        const val STOP_TIMEOUT_MILLIS = 500L
    }
}
