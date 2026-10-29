package com.danemadsen.curie.engine.onnx

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * The ONNX Runtime environment that every session of the process runs in: CPU only, BASIC graph
 * optimisations, denormals flushed to zero, and one global intra-op thread pool shared by all
 * sessions (spinning allowed, one inter-op thread).
 *
 * BASIC optimisations: on ARM64, decoder_q4 under BASIC runs as fast as under ALL with the same
 * logits, EXTENDED runs 15-40% slower, and BASIC loads faster than both.
 *
 * ONNX Runtime has one environment per process and fixes its thread pools when it is created: the
 * first [acquire] creates it with that call's thread count. A later call that asks for another
 * count (or finds an environment created elsewhere without global pools) gets sessions with their
 * own pools of that size instead.
 */
internal class OrtRuntime private constructor(
    val environment: OrtEnvironment,
    /** Intra-op threads of each session's pool. */
    val threads: Int,
    private val usesGlobalPool: Boolean,
) {
    /**
     * A CPU session of the graph at [file]. [quietGraph] logs only errors: the patched decoder
     * graphs declare their LoRA inputs as initializers too (so they default to rank 0), which
     * ONNX Runtime reports with a warning per input.
     */
    fun createSession(file: File, quietGraph: Boolean = false): OrtSession {
        val logLevel = if (quietGraph) {
            OrtLoggingLevel.ORT_LOGGING_LEVEL_ERROR
        } else {
            OrtLoggingLevel.ORT_LOGGING_LEVEL_WARNING
        }
        return OrtSession.SessionOptions().use { options ->
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            options.setSessionLogLevel(logLevel)
            options.addConfigEntry(DENORMAL_AS_ZERO_KEY, "1")
            if (usesGlobalPool) {
                options.disablePerSessionThreads()
            } else {
                options.setIntraOpNumThreads(threads)
                options.setInterOpNumThreads(1)
            }
            environment.createSession(file.path, options)
        }
    }

    companion object {
        private const val ENVIRONMENT_NAME = "curie"
        private const val DENORMAL_AS_ZERO_KEY = "session.set_denormal_as_zero"

        /** Phones run fastest on their performance cores; four is a safe count for most. */
        private const val MAX_DEFAULT_THREADS = 4

        private var environment: OrtEnvironment? = null

        /** Intra-op threads of the environment's global pool, or 0 when it has none. */
        private var globalPoolThreads = 0

        /** The runtime for sessions with [requestedThreads] intra-op threads (0: the default). */
        @Synchronized
        fun acquire(requestedThreads: Int): OrtRuntime {
            require(requestedThreads >= 0) { "threads must not be negative, got $requestedThreads" }
            val threads = if (requestedThreads > 0) requestedThreads else defaultThreads()
            val current = environment ?: createEnvironment(threads)
            environment = current
            return OrtRuntime(current, threads, usesGlobalPool = globalPoolThreads == threads)
        }

        private fun defaultThreads(): Int {
            val processors = Runtime.getRuntime().availableProcessors()
            return processors.coerceIn(1, MAX_DEFAULT_THREADS)
        }

        private fun createEnvironment(threads: Int): OrtEnvironment {
            val created = OrtEnvironment.ThreadingOptions().use { threading ->
                threading.setGlobalIntraOpNumThreads(threads)
                threading.setGlobalInterOpNumThreads(1)
                threading.setGlobalSpinControl(true)
                threading.setGlobalDenormalAsZero()
                try {
                    val logLevel = OrtLoggingLevel.ORT_LOGGING_LEVEL_WARNING
                    OrtEnvironment.getEnvironment(logLevel, ENVIRONMENT_NAME, threading)
                } catch (error: IllegalStateException) {
                    // Without a cause: another part of the process created the environment, with
                    // pools we know nothing of. With one: creating it failed.
                    if (error.cause != null) {
                        throw error
                    }
                    null
                }
            }
            val environment = if (created != null) {
                globalPoolThreads = threads
                created
            } else {
                OrtEnvironment.getEnvironment()
            }
            environment.setTelemetry(false)
            return environment
        }
    }
}

/** Direct buffers in native byte order, which ONNX Runtime reads and writes in place. */
internal object DirectBuffers {
    fun floats(count: Int): FloatBuffer {
        val bytes = ByteBuffer.allocateDirect(count * Float.SIZE_BYTES)
        bytes.order(ByteOrder.nativeOrder())
        return bytes.asFloatBuffer()
    }

    fun longs(count: Int): LongBuffer {
        val bytes = ByteBuffer.allocateDirect(count * Long.SIZE_BYTES)
        bytes.order(ByteOrder.nativeOrder())
        return bytes.asLongBuffer()
    }

    fun bytes(count: Int): ByteBuffer {
        val bytes = ByteBuffer.allocateDirect(count)
        bytes.order(ByteOrder.nativeOrder())
        return bytes
    }

    /** A view of the first [count] values of [buffer] (whose position stays 0). */
    fun prefix(buffer: LongBuffer, count: Int): LongBuffer {
        val view = buffer.duplicate()
        // Through Buffer: the covariant LongBuffer.limit is newer than Android's runtime
        (view as Buffer).limit(count)
        return view
    }

    /** Copies all of [source] (whose position stays 0) into [destination]. */
    fun copy(source: FloatBuffer, destination: FloatArray) {
        val view = source.duplicate()
        view.get(destination)
    }

    /** Writes [values] over [destination] from its start. */
    fun fill(destination: FloatBuffer, values: FloatArray) {
        val view = destination.duplicate()
        view.put(values)
    }
}

/** Tensors over direct buffers: ONNX Runtime uses the buffer's memory without copying it. */
internal object Tensors {
    fun floats(environment: OrtEnvironment, buffer: FloatBuffer, vararg shape: Long): OnnxTensor =
        OnnxTensor.createTensor(environment, buffer, shape)

    fun longs(environment: OrtEnvironment, buffer: LongBuffer, vararg shape: Long): OnnxTensor =
        OnnxTensor.createTensor(environment, buffer, shape)

    fun booleans(environment: OrtEnvironment, buffer: ByteBuffer, vararg shape: Long): OnnxTensor =
        OnnxTensor.createTensor(environment, buffer, shape, OnnxJavaType.BOOL)

    /** A float32 tensor of [shape] holding a copy of [values]. */
    fun copyOf(environment: OrtEnvironment, values: FloatArray, vararg shape: Long): OnnxTensor {
        val buffer = DirectBuffers.floats(values.size)
        DirectBuffers.fill(buffer, values)
        return OnnxTensor.createTensor(environment, buffer, shape)
    }

    /** An int64 tensor of [shape] holding [values]. */
    fun copyOf(environment: OrtEnvironment, values: LongArray, vararg shape: Long): OnnxTensor {
        val buffer = DirectBuffers.longs(values.size)
        buffer.duplicate().put(values)
        return OnnxTensor.createTensor(environment, buffer, shape)
    }

    /** The values of a float32 tensor, row-major. */
    fun floatsOf(tensor: OnnxTensor): FloatArray {
        val buffer = tensor.floatBuffer
        val values = FloatArray(buffer.remaining())
        buffer.get(values)
        return values
    }
}
