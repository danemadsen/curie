package com.danemadsen.curie.inference

import android.os.SharedMemory
import android.system.ErrnoException
import com.danemadsen.curie.engine.CacheMemory
import com.danemadsen.curie.engine.MemoryBlock
import java.nio.ByteBuffer

/**
 * KV caches in anonymous shared memory. Direct buffers would come out of the Java heap on Android,
 * which cannot hold a conversation's hundreds of megabytes; this memory is outside it, and is
 * unmapped as soon as its conversation closes instead of whenever the garbage collector runs.
 */
object SharedCacheMemory : CacheMemory {
    private const val NAME = "curie-kv-cache"

    override fun allocate(bytes: Int): MemoryBlock {
        val memory = try {
            SharedMemory.create(NAME, bytes)
        } catch (error: ErrnoException) {
            throw outOfMemory(bytes, error)
        }

        try {
            val buffer = memory.mapReadWrite()
            return SharedMemoryBlock(memory, buffer)
        } catch (error: ErrnoException) {
            memory.close()
            throw outOfMemory(bytes, error)
        }
    }

    /** What the engine's callers expect when a cache does not fit (see AssistantEngine). */
    private fun outOfMemory(bytes: Int, cause: ErrnoException): OutOfMemoryError {
        val error = OutOfMemoryError("cannot map $bytes bytes for a KV cache: ${cause.message}")
        error.initCause(cause)
        return error
    }

    private class SharedMemoryBlock(
        private val memory: SharedMemory,
        override val buffer: ByteBuffer,
    ) : MemoryBlock {
        override fun close() {
            SharedMemory.unmap(buffer)
            memory.close()
        }
    }
}
