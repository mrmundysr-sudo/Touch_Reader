package com.mundymorning.touchreader.core.audio

/** Rolling measurements used to decide whether a model is fast enough for continuous narration. */
class SynthesisStats(private val window: Int = 6) {
    data class Snapshot(
        val sentences: Int,
        /** Seconds spent synthesizing divided by seconds of audio produced; < 1.0 keeps up with playback. */
        val averageRtf: Double,
        val lastRtf: Double,
        /** Delay from the first request after idle until audio started, or -1 if none yet. */
        val timeToFirstAudioMs: Long,
    )

    private val rtfs = ArrayDeque<Double>()
    private var first = -1L
    private var total = 0

    @Synchronized fun record(synthNanos: Long, audioSeconds: Double) {
        if (audioSeconds <= 0.0) return
        val rtf = synthNanos / 1e9 / audioSeconds
        rtfs.addLast(rtf)
        while (rtfs.size > window) rtfs.removeFirst()
        total++
    }

    @Synchronized fun recordFirstAudio(ms: Long) { first = ms }

    @Synchronized fun snapshot() = Snapshot(
        total,
        if (rtfs.isEmpty()) 0.0 else rtfs.average(),
        rtfs.lastOrNull() ?: 0.0,
        first,
    )

    @Synchronized fun windowFull(minSamples: Int) = rtfs.size >= minSamples
}
