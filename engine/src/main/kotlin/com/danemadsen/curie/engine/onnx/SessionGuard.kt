package com.danemadsen.curie.engine.onnx

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Keeps an engine's native sessions alive while anything runs on them.
 *
 * Every use of the sessions (a generation, a prefill, a codec chunk) runs inside [whileOpen], any
 * number at a time. [close] refuses new runs, asks the running ones to stop, waits for them to
 * return and only then releases the native memory: closing from another thread never frees what a
 * run still reads (ONNX Runtime would crash the process).
 */
internal class SessionGuard {
    private val lock = ReentrantReadWriteLock()

    /** Set once, when [close] starts: from then on [whileOpen] refuses to run. */
    @Volatile
    var isClosing = false
        private set

    /** @throws IllegalStateException once [close] has started */
    fun checkOpen() {
        check(!isClosing) { "the engine is closed" }
    }

    /**
     * Runs [block] while the sessions stay open.
     *
     * @throws IllegalStateException once [close] has started
     */
    fun <T> whileOpen(block: () -> T): T =
        lock.read {
            checkOpen()
            block()
        }

    /**
     * Closes once: refuses new runs, calls [stopRuns] (which makes the runs in flight return
     * soon), waits until none runs, then calls [release]. A later call returns at once.
     *
     * @throws IllegalStateException when called from inside [whileOpen], which would wait for itself
     */
    fun close(stopRuns: () -> Unit, release: () -> Unit) {
        check(lock.readHoldCount == 0) { "the engine cannot close from inside one of its own runs" }
        synchronized(this) {
            if (isClosing) {
                return
            }
            isClosing = true
        }
        stopRuns()
        lock.write {
            release()
        }
    }
}
