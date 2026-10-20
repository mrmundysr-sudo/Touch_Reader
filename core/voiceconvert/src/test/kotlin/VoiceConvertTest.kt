import com.mundymorning.touchreader.core.voiceconvert.*
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.*

class FftTest {
    @Test fun matchesNaiveDft() {
        for (n in listOf(8, 64, 1024)) {
            val rnd = Random(n.toLong())
            val re = DoubleArray(n) { rnd.nextGaussian() }; val im = DoubleArray(n) { rnd.nextGaussian() }
            val er = DoubleArray(n); val ei = DoubleArray(n)
            for (k in 0 until n) for (t in 0 until n) {
                val a = -2.0 * PI * k * t / n
                er[k] += re[t] * cos(a) - im[t] * sin(a); ei[k] += re[t] * sin(a) + im[t] * cos(a)
            }
            val r = re.copyOf(); val i = im.copyOf(); Fft(n).transform(r, i)
            for (k in 0 until n) { assertEquals(er[k], r[k], 1e-8, "re[$k] n=$n"); assertEquals(ei[k], i[k], 1e-8, "im[$k] n=$n") }
        }
    }

    @Test fun rejectsNonPowerOfTwo() { assertFailsWith<IllegalArgumentException> { Fft(100) } }
}

class SpectrogramTest {
    /** Direct implementation of the PyTorch definition, with no FFT and no shared code, as the oracle. */
    private fun naive(x: FloatArray, nFft: Int, hop: Int, win: Int): Array<DoubleArray> {
        val pad = (nFft - hop) / 2
        val p = DoubleArray(x.size + 2 * pad) { i ->
            var k = i - pad
            if (k < 0) k = -k
            if (k >= x.size) k = 2 * (x.size - 1) - k
            x[k].toDouble()
        }
        val frames = 1 + (p.size - nFft) / hop
        return Array(nFft / 2 + 1) { f ->
            DoubleArray(frames) { t ->
                var re = 0.0; var im = 0.0
                for (n in 0 until win) {
                    val w = 0.5 - 0.5 * cos(2 * PI * n / win)
                    val v = p[t * hop + n] * w
                    re += v * cos(-2 * PI * f * n / nFft); im += v * sin(-2 * PI * f * n / nFft)
                }
                sqrt(re * re + im * im + 1e-6)
            }
        }
    }

    @Test fun matchesTheDefinitionForASmallConfig() {
        val rnd = Random(3)
        val x = FloatArray(200) { (rnd.nextGaussian() * 0.3).toFloat() }
        val spec = SpectrogramExtractor(SpectrogramConfig(32, 8, 32)).compute(x)
        val ref = naive(x, 32, 8, 32)
        assertEquals(ref.size, spec.freqBins); assertEquals(ref[0].size, spec.frames)
        for (f in 0 until spec.freqBins) for (t in 0 until spec.frames) assertEquals(ref[f][t], spec[f, t].toDouble(), 1e-4, "f=$f t=$t")
    }

    @Test fun matchesTheDefinitionForTheRealConfig() {
        val rnd = Random(4)
        val x = FloatArray(6000) { (rnd.nextGaussian() * 0.2).toFloat() }
        val cfg = SpectrogramConfig(1024, 256, 1024)
        val spec = SpectrogramExtractor(cfg).compute(x)
        val ref = naive(x, 1024, 256, 1024)
        var worst = 0.0
        for (f in 0 until spec.freqBins step 7) for (t in 0 until spec.frames) worst = maxOf(worst, abs(ref[f][t] - spec[f, t]) / (ref[f][t] + 1e-3))
        assertTrue(worst < 1e-3, "relative error $worst")
        assertEquals(513, spec.freqBins)
        assertEquals(1 + (6000 + 2 * 384 - 1024) / 256, spec.frames)
    }

    @Test fun toneLandsInTheRightBin() {
        val sr = 22050
        val x = FloatArray(sr) { sin(2 * PI * 2000 * it / sr).toFloat() * 0.5f }
        val spec = SpectrogramExtractor(SpectrogramConfig(1024, 256, 1024)).compute(x)
        val mid = spec.frames / 2
        val peak = (0 until spec.freqBins).maxByOrNull { spec[it, mid] }!!
        assertTrue(abs(peak - (2000.0 * 1024 / sr).toInt()) <= 1, "peak bin $peak")
    }

    @Test fun transposeSwapsAxes() {
        val s = Spectrogram(2, 3, floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f)) // [freq][frame]
        assertContentEquals(floatArrayOf(1f, 4f, 2f, 5f, 3f, 6f), s.transposed())
    }

    @Test fun tooShortAudioIsRejected() {
        assertFailsWith<IllegalArgumentException> { SpectrogramExtractor(SpectrogramConfig(1024, 256, 1024)).compute(FloatArray(100)) }
    }
}

class CodecAndChooserTest {
    @Test fun embeddingRoundTrips() {
        val e = FloatArray(256) { it * 0.01f - 1f }
        assertContentEquals(e, EmbeddingCodec.decode(EmbeddingCodec.encode(e)))
        assertFailsWith<IllegalArgumentException> { EmbeddingCodec.decode(byteArrayOf(1, 2, 3)) }
    }

    @Test fun chooserPicksTheClosestPreset() {
        val presets = mapOf("A" to floatArrayOf(1f, 0f, 0f), "B" to floatArrayOf(0f, 1f, 0f), "C" to floatArrayOf(0f, 0f, 1f))
        assertEquals("B", PresetChooser.choose(floatArrayOf(0.1f, 0.9f, 0.2f), presets))
        assertEquals("C", PresetChooser.choose(floatArrayOf(-1f, -1f, 2f), presets))
    }

    @Test fun cosineOfIdenticalVectorsIsOne() {
        assertEquals(1.0, EmbeddingCodec.cosine(floatArrayOf(1f, 2f, 3f), floatArrayOf(2f, 4f, 6f)), 1e-9)
    }
}

class ConvertingSynthesizerTest {
    private class FakeBase(override val sampleRate: Int = 44100, val seconds: Double = 1.0) : BaseVoice {
        val presets = mutableListOf<String>(); var calls = 0; var cancelAfter = false
        override fun usePreset(name: String) { presets += name }
        override fun synthesizeFloat(text: String, speed: Float, isCancelled: () -> Boolean): FloatArray? {
            calls++; return if (cancelAfter) null else FloatArray((sampleRate * seconds).toInt()) { sin(2 * PI * 220 * it / sampleRate).toFloat() * 0.4f }
        }
    }

    private class FakeConverter(override val sampleRate: Int = 22050) : ToneConverter {
        var converts = 0; var lastSrc: FloatArray? = null; var lastTgt: FloatArray? = null; var inLen = 0
        override fun embed(audio: FloatArray) = floatArrayOf(0f, 1f)
        override fun convert(audio: FloatArray, src: FloatArray, tgt: FloatArray, isCancelled: () -> Boolean): FloatArray? {
            converts++; lastSrc = src; lastTgt = tgt; inLen = audio.size
            return FloatArray(audio.size) { audio[it] * 0.5f }
        }
    }

    private val presets = mapOf("M1" to floatArrayOf(1f, 0f), "F1" to floatArrayOf(0f, 1f))
    private val never = { false }

    @Test fun presetVoiceSkipsTheConverter() {
        val base = FakeBase(); val conv = FakeConverter()
        val s = ConvertingSynthesizer(base, conv, presets)
        s.usePreset("M1")
        val out = s.synthesize("hi", 1f, never)
        assertEquals(44100, out.size); assertEquals(0, conv.converts); assertEquals(listOf("M1"), base.presets)
    }

    @Test fun customVoiceUsesNearestPresetAndConverts() {
        val base = FakeBase(); val conv = FakeConverter()
        val s = ConvertingSynthesizer(base, conv, presets)
        s.useCustom { floatArrayOf(0.1f, 0.9f) }
        val out = s.synthesize("hi", 1f, never)
        assertEquals(listOf("F1"), base.presets)
        assertContentEquals(presets["F1"], conv.lastSrc); assertContentEquals(floatArrayOf(0.1f, 0.9f), conv.lastTgt)
        assertTrue(abs(conv.inLen - 22050) <= 2 && abs(out.size - 44100) <= 2, "resampled down for conversion and back up: ${conv.inLen} / ${out.size}")
        assertTrue(out.maxOf { abs(it.toInt()) } in 3000..8000, "converted audio should be about half the base level")
    }

    @Test fun reportsProgressAndTimingsWhileConverting() {
        val s = ConvertingSynthesizer(FakeBase(), FakeConverter(), presets)
        val said = mutableListOf<String>()
        s.trace = { said += it }
        s.useCustom { floatArrayOf(0.1f, 0.9f) }
        s.synthesize("a", 1f, never); s.synthesize("b", 1f, never)
        assertEquals(1, said.count { it.startsWith("Preparing your voice") }, "fingerprint step is announced once: $said")
        assertEquals(2, said.count { it.startsWith("Step 1 of 2") })
        assertEquals(2, said.count { it.startsWith("Your voice: 1.0 s of speech") }, said.toString())
        said.clear(); s.usePreset("M1"); s.synthesize("c", 1f, never)
        assertTrue(said.last().startsWith("Built-in voice: 1.0 s of speech"), said.toString())
    }

    @Test fun fingerprintIsComputedOnceAndOnlyWhenNeeded() {
        var computed = 0
        val s = ConvertingSynthesizer(FakeBase(), FakeConverter(), presets)
        s.useCustom { computed++; floatArrayOf(1f, 0f) }
        assertEquals(0, computed)
        s.synthesize("a", 1f, never); s.synthesize("b", 1f, never)
        assertEquals(1, computed)
    }

    @Test fun switchingBackToAPresetStopsConverting() {
        val conv = FakeConverter(); val s = ConvertingSynthesizer(FakeBase(), conv, presets)
        s.useCustom { floatArrayOf(1f, 0f) }; s.synthesize("a", 1f, never)
        s.usePreset("F1"); s.synthesize("b", 1f, never)
        assertEquals(1, conv.converts)
    }

    @Test fun missingRecordingIsAClearError() {
        val s = ConvertingSynthesizer(FakeBase(), FakeConverter(), presets)
        s.useCustom { null }
        val e = assertFailsWith<IllegalStateException> { s.synthesize("a", 1f, never) }
        assertTrue(e.message!!.contains("no longer available"))
    }

    @Test fun cancelledSynthesisReturnsNothing() {
        val base = FakeBase().also { it.cancelAfter = true }
        val s = ConvertingSynthesizer(base, FakeConverter(), presets)
        s.useCustom { floatArrayOf(1f, 0f) }
        assertEquals(0, s.synthesize("a", 1f, never).size)
    }

    @Test fun veryShortAudioKeepsTheBaseVoice() {
        val conv = FakeConverter(); val s = ConvertingSynthesizer(FakeBase(seconds = 0.05), conv, presets)
        s.useCustom { floatArrayOf(1f, 0f) }
        assertTrue(s.synthesize("a", 1f, never).isNotEmpty()); assertEquals(0, conv.converts)
    }

    @Test fun outputRateAlwaysMatchesTheBaseVoice() {
        assertEquals(44100, ConvertingSynthesizer(FakeBase(), FakeConverter(), presets).sampleRate)
    }
}

class LazyToneConverterTest {
    private class Counting : ToneConverter, AutoCloseable {
        override val sampleRate = 22050; var closed = false
        override fun embed(audio: FloatArray) = floatArrayOf(1f)
        override fun convert(audio: FloatArray, src: FloatArray, tgt: FloatArray, isCancelled: () -> Boolean) = audio
        override fun close() { closed = true }
    }

    @Test fun loadsOnFirstUseOnlyAndClosesWhatItLoaded() {
        var loads = 0; val inner = Counting()
        val lazy = LazyToneConverter(22050) { loads++; inner }
        assertEquals(22050, lazy.sampleRate); assertEquals(0, loads)
        lazy.embed(FloatArray(1)); lazy.convert(FloatArray(1), floatArrayOf(), floatArrayOf()) { false }
        assertEquals(1, loads); lazy.close(); assertTrue(inner.closed)
    }

    @Test fun closingWithoutUseLoadsNothing() {
        var loads = 0
        LazyToneConverter(22050) { loads++; Counting() }.close()
        assertEquals(0, loads)
    }
}
