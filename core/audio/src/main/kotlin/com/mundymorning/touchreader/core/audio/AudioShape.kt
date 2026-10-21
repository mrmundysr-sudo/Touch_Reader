package com.mundymorning.touchreader.core.audio

import kotlin.math.abs
import kotlin.math.sqrt

/** Cuts the near-silence a speech model leaves before and after what it says, so pauses can be set deliberately. */
object AudioTrim {
    private const val FRAME_MS = 10

    /**
     * Removes leading and trailing audio whose level stays below [threshold] (about -38 dB), keeping [leadMs] before the
     * first sound and [tailMs] after the last so nothing is clipped. Audio that is silent throughout is returned as is.
     */
    fun trim(x: FloatArray, sampleRate: Int, threshold: Float = 0.012f, leadMs: Int = 40, tailMs: Int = 90): FloatArray {
        val frame = sampleRate * FRAME_MS / 1000
        if (frame <= 0 || x.size < frame * 2) return x
        fun loud(from: Int): Boolean {
            var sum = 0.0
            for (i in from until minOf(from + frame, x.size)) sum += x[i].toDouble() * x[i]
            return sqrt(sum / frame) > threshold
        }
        var first = -1
        var f = 0
        while (f + frame <= x.size) { if (loud(f)) { first = f; break }; f += frame }
        if (first < 0) return x
        var last = first
        f = (x.size / frame - 1) * frame
        while (f >= first) { if (loud(f)) { last = f + frame; break }; f -= frame }
        val from = maxOf(0, first - leadMs * sampleRate / 1000)
        val to = minOf(x.size, last + tailMs * sampleRate / 1000)
        return if (from == 0 && to == x.size) x else x.copyOfRange(from, to)
    }
}

/**
 * Splits one stretch of speech that covers several sentences back into the sentences. The cuts are placed in the
 * quietest spot near where each sentence should end by length, which is where the speaker paused between them.
 */
object AudioSplit {
    private const val FRAME_MS = 10

    /** Sample offsets where each of the [weights].size parts ends (the last is [pcm].size); strictly increasing when the audio allows. */
    fun boundaries(pcm: ShortArray, weights: List<Int>, sampleRate: Int): IntArray {
        val n = weights.size
        val out = IntArray(n)
        if (n == 0) return out
        out[n - 1] = pcm.size
        if (n == 1) return out
        val total = weights.sum().coerceAtLeast(1).toDouble()
        val frame = (sampleRate * FRAME_MS / 1000).coerceAtLeast(1)
        val minPart = frame * 5
        var cumulative = 0.0
        var prev = 0
        for (k in 0 until n - 1) {
            cumulative += weights[k]
            val target = (pcm.size * cumulative / total).toInt()
            val span = (pcm.size * weights[k] / total).toInt()
            val reach = maxOf(sampleRate * 150 / 1000, span / 4)
            val lo = maxOf(prev + minPart, target - reach)
            val hi = minOf(pcm.size - minPart * (n - 1 - k), target + reach)
            out[k] = if (lo >= hi) target.coerceIn(prev + 1, pcm.size) else quietest(pcm, lo, hi, frame, target)
            prev = out[k]
        }
        return out
    }

    fun split(pcm: ShortArray, weights: List<Int>, sampleRate: Int): List<ShortArray> {
        val b = boundaries(pcm, weights, sampleRate)
        var from = 0
        return b.map { end -> pcm.copyOfRange(from, maxOf(from, end)).also { from = maxOf(from, end) } }
    }

    /** Centre of the lowest-energy 10 ms frame in [lo, hi); ties go to the one nearest [target]. */
    private fun quietest(pcm: ShortArray, lo: Int, hi: Int, frame: Int, target: Int): Int {
        var bestEnergy = Double.MAX_VALUE
        var best = target.coerceIn(lo, hi)
        var at = lo
        while (at + frame <= hi) {
            var e = 0.0
            for (i in at until at + frame) e += abs(pcm[i].toDouble())
            val centre = at + frame / 2
            if (e < bestEnergy - 1e-6 || (abs(e - bestEnergy) <= 1e-6 && abs(centre - target) < abs(best - target))) { bestEnergy = e; best = centre }
            at += frame / 2
        }
        return best
    }
}
