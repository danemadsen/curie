package com.danemadsen.curie.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.danemadsen.curie.engine.Lfm3Omni
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Streams spoken answers to an AudioTrack (24 kHz mono float, USAGE_ASSISTANT) through a
 * [JitterBuffer], which holds back enough to play through without pauses when the model speaks
 * slower than real time. A playback thread moves samples from the buffer to the track in 20 ms chunks,
 * pausing the track while the buffer re-fills and measuring the [level] of each chunk.
 *
 * With [holdsFocus] the player takes transient audio focus for each answer (reading a chat
 * message aloud): it does not start without it, pauses while another app talks over it, and stops
 * when another app takes the audio for good. Without it the caller holds the focus (a call, for
 * its whole length).
 */
class SpeechPlayer(
    context: Context,
    private val holdsFocus: Boolean = false,
    private val sampleRate: Int = Lfm3Omni.OUTPUT_SAMPLE_RATE,
) : SpeechOutput {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val focus = AudioFocus(
        context = context.applicationContext,
        attributes = AudioFocus.speechAttributes(AudioAttributes.USAGE_ASSISTANT),
        onChange = ::onFocusChange,
    )
    private val buffer = JitterBuffer(sampleRate)
    private val meter = LevelMeter()
    private val playing = MutableStateFlow(false)
    private val lock = Any()
    private val chunkSamples = sampleRate * CHUNK_MS / 1000

    private var track: AudioTrack? = null
    private var thread: Thread? = null
    private var finished: CompletableDeferred<Boolean>? = null

    /** Bumped by [begin] and [stop], so the playback thread notices that its answer is over. */
    @Volatile
    private var answerId = 0

    @Volatile
    private var isAnswerActive = false

    @Volatile
    private var isPaused = false

    /** Set by [begin] and [stop]: the playback thread drops what the track still holds. */
    @Volatile
    private var isFlushPending = false

    @Volatile
    private var isReleased = false

    private var isTrackPlaying = false
    private var writtenFrames = 0L
    private var speakerOn = true

    override val level: Float
        get() = meter.level

    override val isPlaying: StateFlow<Boolean> = playing.asStateFlow()

    override fun begin(): Boolean {
        check(!isReleased) { "the player was released" }
        if (holdsFocus && !focus.request()) {
            stop()
            return false
        }
        synchronized(lock) {
            finished?.complete(false)
            finished = CompletableDeferred()
            answerId++
            buffer.reset()
            isFlushPending = true
            isAnswerActive = true
            if (holdsFocus) {
                // It was just granted the focus. Otherwise the caller pauses and resumes it.
                isPaused = false
            }
            startThreadIfNeeded()
        }
        return true
    }

    override fun write(samples: FloatArray) {
        buffer.write(samples)
    }

    override fun expectDuration(seconds: Double) {
        buffer.expectDuration(seconds)
    }

    override fun endOfAnswer() {
        buffer.complete()
    }

    override suspend fun awaitFinished(): Boolean {
        val current = synchronized(lock) { finished } ?: return true
        return current.await()
    }

    override fun stop() {
        synchronized(lock) {
            answerId++
            isAnswerActive = false
            buffer.reset()
            isFlushPending = true
            finished?.complete(false)
            finished = null
        }
        meter.reset()
        playing.value = false
        if (holdsFocus) {
            focus.abandon()
        }
    }

    override fun setPaused(paused: Boolean) {
        isPaused = paused
    }

    /** Only while it holds the focus itself ([holdsFocus]): a lasting loss ends the answer. */
    private fun onFocusChange(change: FocusChange) {
        when (change) {
            FocusChange.GAINED -> setPaused(false)
            FocusChange.PAUSE -> setPaused(true)
            FocusChange.LOST -> stop()
        }
    }

    override fun setSpeakerOn(speakerOn: Boolean) {
        synchronized(lock) {
            this.speakerOn = speakerOn
            val current = track ?: return
            applyRoute(current)
        }
    }

    override fun release() {
        val playbackThread = synchronized(lock) {
            isReleased = true
            answerId++
            finished?.complete(false)
            finished = null
            thread
        }
        playbackThread?.interrupt()
        playbackThread?.join(JOIN_TIMEOUT_MS)
        synchronized(lock) {
            track?.release()
            track = null
        }
        playing.value = false
    }

    private fun startThreadIfNeeded() {
        if (thread != null) {
            return
        }
        val playbackThread = Thread(::runPlayback, THREAD_NAME)
        playbackThread.priority = Thread.MAX_PRIORITY
        thread = playbackThread
        playbackThread.start()
    }

    private fun runPlayback() {
        try {
            while (!isReleased) {
                playNext()
            }
        } catch (interrupted: InterruptedException) {
            // released
        } catch (error: RuntimeException) {
            Log.e(TAG, "speech playback failed", error)
            stop()
        } finally {
            pauseTrack()
        }
    }

    /** Moves one chunk to the track, or waits for the buffer to change. The track is only used here. */
    private fun playNext() {
        val currentAnswer = answerId
        if (isFlushPending) {
            isFlushPending = false
            flushTrack()
        }
        if (!isAnswerActive || isPaused) {
            pauseTrack()
            buffer.awaitChange(WAIT_MS)
            return
        }

        val chunk = buffer.read(chunkSamples)
        if (chunk.isNotEmpty()) {
            writeChunk(chunk, currentAnswer)
            return
        }

        when (buffer.state) {
            JitterBuffer.State.FINISHED -> finishAnswer(currentAnswer)
            JitterBuffer.State.BUFFERING -> {
                pauseTrack()
                buffer.awaitChange(WAIT_MS)
            }
            JitterBuffer.State.PLAYING -> buffer.awaitChange(WAIT_MS)
        }
    }

    private fun writeChunk(chunk: FloatArray, currentAnswer: Int) {
        val audioTrack = ensureTrack()
        if (!isTrackPlaying) {
            audioTrack.play()
            isTrackPlaying = true
            playing.value = true
        }

        val rms = AudioLevels.rms(chunk)
        meter.update(rms)

        // Non-blocking writes, so a stop never leaves this thread stuck on a paused track
        var offset = 0
        while (offset < chunk.size && currentAnswer == answerId && !isReleased) {
            val written = audioTrack.write(chunk, offset, chunk.size - offset, AudioTrack.WRITE_NON_BLOCKING)
            if (written < 0) {
                throw IllegalStateException("AudioTrack.write failed with $written")
            }
            offset += written
            writtenFrames += written
            if (offset < chunk.size) {
                Thread.sleep(FULL_BUFFER_SLEEP_MS)
            }
        }
    }

    /** Waits for the track to play what was written, then reports the answer as finished. */
    private fun finishAnswer(currentAnswer: Int) {
        val audioTrack = track
        val hasUnplayedFrames = audioTrack != null && playedFrames(audioTrack) < writtenFrames
        if (audioTrack != null && hasUnplayedFrames) {
            // A re-buffering pause may have stopped the track with the answer's last chunks in it
            if (!isTrackPlaying) {
                audioTrack.play()
                isTrackPlaying = true
                playing.value = true
            }
            drain(audioTrack, currentAnswer)
        }

        synchronized(lock) {
            if (currentAnswer != answerId) {
                return
            }
            isAnswerActive = false
            finished?.complete(true)
            finished = null
        }
        // How smoothly speech played on this phone: "adb logcat -s SpeechPlayer"
        val speed = "%.2f".format(Locale.ROOT, buffer.arrivalSpeed)
        Log.i(TAG, "an answer played to its end: speech arrived at ${speed}x real time, ${buffer.underruns} pauses to re-buffer")
        pauseTrack()
        meter.reset()
        playing.value = false
        if (holdsFocus) {
            focus.abandon()
        }
    }

    private fun drain(audioTrack: AudioTrack, currentAnswer: Int) {
        val pendingFrames = writtenFrames - playedFrames(audioTrack)
        val pendingMs = pendingFrames.coerceAtLeast(0) * 1000 / sampleRate
        val deadline = System.nanoTime() + (pendingMs + DRAIN_MARGIN_MS) * 1_000_000
        while (playedFrames(audioTrack) < writtenFrames && System.nanoTime() < deadline) {
            if (currentAnswer != answerId || isReleased) {
                return
            }
            Thread.sleep(WAIT_MS)
        }
    }

    private fun playedFrames(audioTrack: AudioTrack): Long {
        // The head position is an unsigned 32-bit frame count
        return audioTrack.playbackHeadPosition.toLong() and 0xFFFFFFFFL
    }

    private fun pauseTrack() {
        val audioTrack = track ?: return
        if (isTrackPlaying) {
            audioTrack.pause()
            isTrackPlaying = false
            playing.value = false
        }
    }

    private fun flushTrack() {
        val audioTrack = track ?: return
        audioTrack.pause()
        audioTrack.flush()
        isTrackPlaying = false
        writtenFrames = playedFrames(audioTrack)
        playing.value = false
    }

    private fun ensureTrack(): AudioTrack {
        synchronized(lock) {
            val existing = track
            if (existing != null) {
                return existing
            }
            val created = createTrack()
            applyRoute(created)
            track = created
            writtenFrames = 0
            return created
        }
    }

    private fun createTrack(): AudioTrack {
        val attributes = AudioFocus.speechAttributes(AudioAttributes.USAGE_ASSISTANT)

        val formatBuilder = AudioFormat.Builder()
        formatBuilder.setSampleRate(sampleRate)
        formatBuilder.setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
        formatBuilder.setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
        val format = formatBuilder.build()

        val channelMask = AudioFormat.CHANNEL_OUT_MONO
        val minimumBytes = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_FLOAT)
        val preferredBytes = sampleRate * Float.SIZE_BYTES * TRACK_BUFFER_MS / 1000
        val bufferBytes = maxOf(minimumBytes, preferredBytes)

        val builder = AudioTrack.Builder()
        builder.setAudioAttributes(attributes)
        builder.setAudioFormat(format)
        builder.setBufferSizeInBytes(bufferBytes)
        builder.setTransferMode(AudioTrack.MODE_STREAM)
        return builder.build()
    }

    /** The loudspeaker is the default route for the assistant; the earpiece is asked for explicitly. */
    private fun applyRoute(audioTrack: AudioTrack) {
        if (speakerOn) {
            audioTrack.setPreferredDevice(null)
            return
        }
        val outputs = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS) ?: return
        val earpiece = outputs.firstOrNull { device -> device.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
        audioTrack.setPreferredDevice(earpiece)
    }

    private companion object {
        const val TAG = "SpeechPlayer"
        const val THREAD_NAME = "curie-speech-player"
        const val CHUNK_MS = 20
        const val TRACK_BUFFER_MS = 120
        const val WAIT_MS = 10L
        const val FULL_BUFFER_SLEEP_MS = 5L
        const val DRAIN_MARGIN_MS = 500L
        const val JOIN_TIMEOUT_MS = 1_000L
    }
}
