package com.danemadsen.curie.engine.onnx

/** Thread helpers of the concurrency tests. */
internal object TestThreads {
    private const val TIMEOUT_NANOS = 10_000_000_000L

    /** Waits until [thread] blocks on a lock (or ends), e.g. a close waiting for a run. */
    fun awaitWaiting(thread: Thread) {
        val deadline = System.nanoTime() + TIMEOUT_NANOS
        while (thread.state != Thread.State.WAITING && thread.isAlive) {
            check(System.nanoTime() < deadline) { "${thread.name} never waited" }
            Thread.onSpinWait()
        }
    }
}
