package com.mundymorning.touchreader.core.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Slows speech down (or speeds it up) without changing its pitch, using waveform-similarity overlap-add (WSOLA):
 * short windows are re-laid at a different spacing, each nudged to the position that continues the previous one most
 * smoothly. Good for modest changes (about 80% to 125%); much slower than that starts to sound smeared.
 */
object TimeStretch {
    private const val WINDOW = 1024
    private const val HOP = WINDOW / 2
    private const val TOLERANCE = 256
    private const val STRIDE = 4 // correlation is measured on every 4th sample: plenty to find the best alignment, 4x cheaper

    /** [pace] 1.0 leaves [x] as it is; 0.8 makes it 25% longer (slower); values are limited to 0.5..2.0. */
    fun stretch(x: FloatArray, pace: Double): FloatArray {
        val p = pace.coerceIn(0.5, 2.0)
        if (p == 1.0 || x.size < WINDOW * 2) return x
        val frames = ((x.size / p - WINDOW) / HOP).toInt() + 1
        if (frames < 2) return x
        val out = FloatArray((frames - 1) * HOP + WINDOW)
        val hann = FloatArray(WINDOW) { (0.5 - 0.5 * cos(2.0 * PI * it / WINDOW)).toFloat() }
        val maxPos = x.size - WINDOW
        var prev = 0
        for (k in 0 until frames) {
            val pos = if (k == 0) 0 else best(x, prev + HOP, (k * HOP * p).toInt().coerceIn(0, maxPos), maxPos)
            val o = k * HOP
            for (i in 0 until WINDOW) out[o + i] += x[pos + i] * hann[i]
            prev = pos
        }
        // the first and last half windows are not overlapped by a neighbour, so they were scaled down by the window
        for (i in 0 until HOP) { out[i] = x[i] }
        return out.copyOf(minOf(out.size, (x.size / p).toInt()))
    }

    /** The start in [target - TOLERANCE, target + TOLERANCE] whose window best continues the one starting at [natural]. */
    private fun best(x: FloatArray, natural: Int, target: Int, maxPos: Int): Int {
        if (natural > maxPos) return target
        val lo = maxOf(0, target - TOLERANCE)
        val hi = minOf(maxPos, target + TOLERANCE)
        var bestPos = target.coerceIn(lo, hi)
        var bestScore = -Double.MAX_VALUE
        for (c in lo..hi) {
            var dot = 0.0
            var e = 1e-9
            var i = 0
            while (i < WINDOW) { val a = x[c + i]; dot += a * x[natural + i]; e += a * a; i += STRIDE }
            val score = dot / sqrt(e)
            if (score > bestScore) { bestScore = score; bestPos = c }
        }
        return bestPos
    }
}
