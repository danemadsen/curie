package com.danemadsen.curie.ui.call.eclipse

/**
 * Decides which display frames redraw the eclipse. While it is calm it changes so slowly that about 30 frames
 * a second look the same as 60 (or 120) and cost the GPU half as much over a long call; otherwise every frame
 * redraws, so the glint and the reaction to the voice stay smooth.
 */
internal class FramePacer(private val calmIntervalNanos: Long = CALM_INTERVAL_NANOS) {
    private var lastShownNanos: Long? = null

    /** Whether the frame at [frameTimeNanos] should be drawn, given whether the eclipse is [calm] now. */
    fun shouldDraw(frameTimeNanos: Long, calm: Boolean): Boolean {
        val lastShown = lastShownNanos
        val dueForCalmFrame = lastShown == null || frameTimeNanos - lastShown >= calmIntervalNanos
        val draw = !calm || dueForCalmFrame
        if (draw) {
            lastShownNanos = frameTimeNanos
        }
        return draw
    }

    companion object {
        /**
         * Calm frames are at least 30 ms apart: every second frame at 60 Hz, every third at 90 Hz and every
         * fourth at 120 Hz, all about 30 a second. Slightly under 1/30 s, so frame-time jitter never skips one.
         */
        const val CALM_INTERVAL_NANOS = 30_000_000L
    }
}
