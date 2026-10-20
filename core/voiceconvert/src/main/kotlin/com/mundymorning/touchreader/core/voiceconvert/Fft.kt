package com.mundymorning.touchreader.core.voiceconvert

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Iterative radix-2 complex FFT (size must be a power of two). Twiddle factors are cached per size. */
class Fft(val size: Int) {
    private val cosT = DoubleArray(size / 2) { cos(-2.0 * PI * it / size) }
    private val sinT = DoubleArray(size / 2) { sin(-2.0 * PI * it / size) }
    private val rev = IntArray(size)

    init {
        require(size >= 2 && size and (size - 1) == 0) { "FFT size must be a power of two" }
        val bits = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size) rev[i] = Integer.reverse(i) ushr (32 - bits)
    }

    /** In-place forward transform of (re, im). */
    fun transform(re: DoubleArray, im: DoubleArray) {
        for (i in 0 until size) {
            val j = rev[i]
            if (j > i) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= size) {
            val half = len / 2
            val step = size / len
            var start = 0
            while (start < size) {
                for (k in 0 until half) {
                    val wr = cosT[k * step]; val wi = sinT[k * step]
                    val a = start + k; val b = a + half
                    val tr = re[b] * wr - im[b] * wi
                    val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr; im[b] = im[a] - ti
                    re[a] += tr; im[a] += ti
                }
                start += len
            }
            len *= 2
        }
    }
}
