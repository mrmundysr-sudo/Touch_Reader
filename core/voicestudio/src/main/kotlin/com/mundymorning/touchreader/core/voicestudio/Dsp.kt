package com.mundymorning.touchreader.core.voicestudio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Small, dependency-free audio helpers used to prepare a voice sample. */
object Dsp {
    const val FRAME_SECONDS = 0.02

    fun db(x: Double) = 20.0 * log10(x + 1e-9)

    /** Windowed-sinc resampler (low-passes when reducing the rate). */
    fun resample(pcm: Pcm, targetRate: Int): Pcm {
        if (pcm.sampleRate == targetRate || pcm.samples.isEmpty()) return Pcm(targetRate, pcm.samples)
        val ratio = pcm.sampleRate.toDouble() / targetRate
        val cutoff = min(1.0, targetRate.toDouble() / pcm.sampleRate)
        val half = (16.0 / cutoff).toInt()
        val n = (pcm.samples.size / ratio).toInt()
        val x = pcm.samples
        val out = FloatArray(n)
        for (i in 0 until n) {
            val t = i * ratio
            val lo = max(0, floor(t).toInt() - half + 1)
            val hi = min(x.size - 1, floor(t).toInt() + half)
            var acc = 0.0
            for (k in lo..hi) {
                val d = t - k
                // sin(pi*d*cutoff)/(pi*d) is cutoff*sinc(cutoff*d); its limit at d = 0 is cutoff (not 1)
                val s = if (abs(d) < 1e-9) cutoff else sin(PI * d * cutoff) / (PI * d)
                val w = 0.5 + 0.5 * kotlin.math.cos(PI * d / half)
                acc += x[k] * s * w
            }
            out[i] = acc.toFloat()
        }
        return Pcm(targetRate, out)
    }

    /** Removes rumble below [cutoffHz] (first-order high-pass). */
    fun highPass(pcm: Pcm, cutoffHz: Double = 80.0): Pcm {
        val a = exp(-2.0 * PI * cutoffHz / pcm.sampleRate)
        val out = FloatArray(pcm.samples.size)
        var prevIn = 0f; var prevOut = 0f
        for (i in pcm.samples.indices) {
            val y = (a * (prevOut + pcm.samples[i] - prevIn)).toFloat()
            out[i] = y; prevIn = pcm.samples[i]; prevOut = y
        }
        return Pcm(pcm.sampleRate, out)
    }

    /** Per-frame level in dBFS (20 ms frames). */
    fun frameDb(pcm: Pcm): DoubleArray {
        val frame = max(1, (pcm.sampleRate * FRAME_SECONDS).toInt())
        val n = pcm.samples.size / frame
        return DoubleArray(n) { f ->
            var sum = 0.0
            for (i in f * frame until (f + 1) * frame) sum += pcm.samples[i].toDouble() * pcm.samples[i]
            db(sqrt(sum / frame))
        }
    }

    fun percentile(values: DoubleArray, p: Double): Double {
        if (values.isEmpty()) return -120.0
        val s = values.sortedArray()
        return s[((s.size - 1) * p).toInt().coerceIn(0, s.size - 1)]
    }

    /** Level above which a frame counts as speech: just above the noise floor, and not too far below the loud parts. */
    fun speechThresholdDb(frameDb: DoubleArray): Double {
        val floor = percentile(frameDb, 0.10)
        val loud = percentile(frameDb, 0.95)
        return max(floor + 9.0, loud - 35.0)
    }

    /** Cuts leading/trailing silence, keeping [padSeconds] of room around the speech. Empty if nothing is loud enough. */
    fun trimSilence(pcm: Pcm, padSeconds: Double = 0.15): Pcm {
        val db = frameDb(pcm)
        if (db.isEmpty()) return pcm
        val thr = speechThresholdDb(db)
        val first = db.indexOfFirst { it >= thr }
        val last = db.indexOfLast { it >= thr }
        if (first < 0) return Pcm(pcm.sampleRate, FloatArray(0))
        val frame = (pcm.sampleRate * FRAME_SECONDS).toInt()
        val pad = (padSeconds * pcm.sampleRate).toInt()
        val from = max(0, first * frame - pad)
        val to = min(pcm.samples.size, (last + 1) * frame + pad)
        return Pcm(pcm.sampleRate, pcm.samples.copyOfRange(from, to))
    }

    /** Scales so loud (speech) frames sit near [targetRmsDb], never pushing the peak above [peakLimitDb]. */
    fun normalize(pcm: Pcm, targetRmsDb: Double = -23.0, peakLimitDb: Double = -1.0): Pcm {
        val db = frameDb(pcm)
        if (db.isEmpty()) return pcm
        val thr = speechThresholdDb(db)
        val voiced = db.filter { it >= thr }
        if (voiced.isEmpty()) return pcm
        // RMS of voiced frames (average power, not average dB)
        val rmsDb = 10.0 * log10(voiced.map { 10.0.pow(it / 10.0) }.average() + 1e-12)
        var gainDb = targetRmsDb - rmsDb
        val peak = pcm.samples.maxOf { abs(it) }.toDouble()
        if (peak > 0) gainDb = min(gainDb, peakLimitDb - db(peak))
        val g = 10.0.pow(gainDb / 20.0).toFloat()
        return Pcm(pcm.sampleRate, FloatArray(pcm.samples.size) { pcm.samples[it] * g })
    }
}
