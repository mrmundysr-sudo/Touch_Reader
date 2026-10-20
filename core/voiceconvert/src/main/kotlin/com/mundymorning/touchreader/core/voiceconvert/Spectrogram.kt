package com.mundymorning.touchreader.core.voiceconvert

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/** Magnitude spectrogram laid out [frequency][frame] (like the PyTorch tensor [1, freq, frames]). */
class Spectrogram(val freqBins: Int, val frames: Int, val data: FloatArray) {
    operator fun get(freq: Int, frame: Int) = data[freq * frames + frame]

    /** Layout [frame][frequency], the transpose the speaker-embedding model expects. */
    fun transposed(): FloatArray {
        val out = FloatArray(data.size)
        for (f in 0 until freqBins) for (t in 0 until frames) out[t * freqBins + f] = data[f * frames + t]
        return out
    }
}

/**
 * Port of OpenVoice's `spectrogram_torch`: reflect-pad by (nFft - hop) / 2 on both sides, periodic Hann window,
 * `center = false`, one-sided STFT, magnitude `sqrt(re^2 + im^2 + 1e-6)`.
 */
class SpectrogramConfig(val nFft: Int, val hop: Int, val win: Int) {
    val freqBins get() = nFft / 2 + 1
    val pad get() = (nFft - hop) / 2
}

class SpectrogramExtractor(val config: SpectrogramConfig) {
    private val fft = Fft(config.nFft)
    private val window = DoubleArray(config.win) { 0.5 - 0.5 * cos(2.0 * PI * it / config.win) } // torch.hann_window (periodic)

    /** Minimum number of input samples (reflect padding needs more samples than the pad size). */
    val minSamples get() = config.pad + 1

    fun compute(x: FloatArray): Spectrogram {
        require(x.size >= minSamples) { "Audio is too short to analyse" }
        val pad = config.pad
        val n = x.size + 2 * pad
        val padded = FloatArray(n)
        for (i in 0 until n) {
            var k = i - pad
            if (k < 0) k = -k                            // reflect (edge sample not repeated), as torch's 'reflect'
            if (k >= x.size) k = 2 * (x.size - 1) - k
            padded[i] = x[k]
        }
        val frames = 1 + (n - config.nFft) / config.hop
        require(frames > 0) { "Audio is too short to analyse" }
        val bins = config.freqBins
        val out = FloatArray(bins * frames)
        val re = DoubleArray(config.nFft); val im = DoubleArray(config.nFft)
        val winOffset = (config.nFft - config.win) / 2   // torch centres a shorter window inside the FFT frame
        for (t in 0 until frames) {
            java.util.Arrays.fill(re, 0.0); java.util.Arrays.fill(im, 0.0)
            val start = t * config.hop
            for (i in 0 until config.win) re[winOffset + i] = padded[start + winOffset + i] * window[i]
            fft.transform(re, im)
            for (f in 0 until bins) out[f * frames + t] = sqrt(re[f] * re[f] + im[f] * im[f] + 1e-6).toFloat()
        }
        return Spectrogram(bins, frames, out)
    }
}
