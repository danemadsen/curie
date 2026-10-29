package com.danemadsen.curie.engine.onnx

import com.danemadsen.curie.engine.generation.GenerationListener

/** Everything a generation reports, and when its slots came (for the speed logs). */
internal class RecordingListener(private val onSlot: (RecordingListener) -> Unit = {}) : GenerationListener {
    val prefillReports = ArrayList<Pair<Int, Int>>()
    val text = StringBuilder()
    val frames = ArrayList<IntArray>()
    var audioEnds = 0
    val slotTimes = ArrayList<Pair<String, Long>>()

    /** The positions (plus encoded media rows) the last prefill fed, or 0 when it fed none. */
    val prefillTotal: Int
        get() = prefillReports.lastOrNull()?.second ?: 0

    override fun onPrefill(done: Int, total: Int) {
        prefillReports.add(done to total)
    }

    override fun onText(delta: String) {
        text.append(delta)
        onSlot(this)
    }

    override fun onAudioFrame(codes: IntArray) {
        frames.add(codes.copyOf())
        slotTimes.add("frame" to System.nanoTime())
        onSlot(this)
    }

    override fun onAudioEnd() {
        audioEnds++
        slotTimes.add("end" to System.nanoTime())
    }

    /** Mean milliseconds between consecutive frames, or null with fewer than two. */
    fun millisPerFrame(): Double? {
        val times = slotTimes.filter { (kind, _) -> kind == "frame" }.map { (_, time) -> time }
        if (times.size < 2) {
            return null
        }
        return (times.last() - times.first()) / 1e6 / (times.size - 1)
    }
}
