package com.mundymorning.touchreader.core.voiceconvert

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer

/** Changes the timbre of speech towards a target voice. Implemented over ONNX Runtime; fakes are used in tests. */
interface ToneConverter {
    val sampleRate: Int
    /** Voice fingerprint of [audio] (mono at [sampleRate]); averaged over a few segments for long recordings. */
    fun embed(audio: FloatArray): FloatArray
    /** Speech from [audio] with the voice of [src] replaced by [tgt], or null if cancelled. */
    fun convert(audio: FloatArray, src: FloatArray, tgt: FloatArray, isCancelled: () -> Boolean): FloatArray?
}

class ConverterConfig(val sampleRate: Int, val spectrogram: SpectrogramConfig, val embeddingSize: Int) {
    companion object {
        fun parse(json: String): ConverterConfig {
            val d = JSONObject(json).getJSONObject("data")
            return ConverterConfig(d.getInt("sampling_rate"), SpectrogramConfig(d.getInt("filter_length"), d.getInt("hop_length"), d.getInt("win_length")), 256)
        }
    }
}

object EmbeddingCodec {
    fun encode(e: FloatArray): ByteArray = ByteBuffer.allocate(e.size * 4).order(ByteOrder.LITTLE_ENDIAN).also { b -> e.forEach { b.putFloat(it) } }.array()
    fun decode(bytes: ByteArray): FloatArray {
        require(bytes.size % 4 == 0 && bytes.isNotEmpty()) { "Not a voice fingerprint" }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { b.getFloat() }
    }
    fun cosine(a: FloatArray, b: FloatArray): Double {
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return dot / (Math.sqrt(na) * Math.sqrt(nb) + 1e-12)
    }
}

/** Picks the built-in base voice whose fingerprint is closest to the target, so conversion has the least to change. */
object PresetChooser {
    fun choose(target: FloatArray, sources: Map<String, FloatArray>): String =
        sources.maxByOrNull { EmbeddingCodec.cosine(target, it.value) }?.key ?: error("No base voices available")
}

/**
 * OpenVoice V2 tone-colour converter exported to ONNX (`vc.onnx` + `se.onnx`) with its `converter_config.json`.
 * Not thread-safe; use from one worker at a time.
 */
class OnnxToneConverter private constructor(
    private val cfg: ConverterConfig,
    private val env: OrtEnvironment,
    private val vc: OrtSession,
    private val se: OrtSession,
) : ToneConverter, AutoCloseable {
    private val extractor = SpectrogramExtractor(cfg.spectrogram)
    override val sampleRate: Int get() = cfg.sampleRate

    override fun embed(audio: FloatArray): FloatArray {
        val seg = 12 * sampleRate
        val pieces = ArrayList<FloatArray>()
        var s = 0
        while (s < audio.size && pieces.size < 4) {
            val e = minOf(audio.size, s + seg)
            if (e - s >= 2 * sampleRate || pieces.isEmpty()) pieces += audio.copyOfRange(s, e)
            s = e
        }
        val sum = FloatArray(cfg.embeddingSize)
        for (p in pieces) {
            val spec = extractor.compute(p)
            OnnxTensor.createTensor(env, FloatBuffer.wrap(spec.transposed()), longArrayOf(1, spec.frames.toLong(), spec.freqBins.toLong())).use { t ->
                se.run(mapOf("spec_t" to t)).use { r ->
                    val b = (r.get(0) as OnnxTensor).floatBuffer
                    for (i in sum.indices) sum[i] += b.get(i)
                }
            }
        }
        return FloatArray(sum.size) { sum[it] / pieces.size }
    }

    override fun convert(audio: FloatArray, src: FloatArray, tgt: FloatArray, isCancelled: () -> Boolean): FloatArray? {
        if (isCancelled()) return null
        val spec = extractor.compute(audio)
        val g = longArrayOf(1, cfg.embeddingSize.toLong(), 1)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(spec.data), longArrayOf(1, spec.freqBins.toLong(), spec.frames.toLong())).use { specT ->
        OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(spec.frames.toLong())), longArrayOf(1)).use { lenT ->
        OnnxTensor.createTensor(env, FloatBuffer.wrap(src), g).use { srcT ->
        OnnxTensor.createTensor(env, FloatBuffer.wrap(tgt), g).use { tgtT ->
            vc.run(mapOf("spec" to specT, "spec_len" to lenT, "g_src" to srcT, "g_tgt" to tgtT)).use { r ->
                val b = (r.get(0) as OnnxTensor).floatBuffer
                val out = FloatArray(b.remaining()); b.get(out)
                return out
            }
        } } } }
    }

    override fun close() { runCatching { vc.close() }; runCatching { se.close() } }

    companion object {
        /** [dir] holds vc.onnx, se.onnx and converter_config.json. */
        fun load(dir: File, threads: Int = 2): OnnxToneConverter {
            val cfg = ConverterConfig.parse(File(dir, "converter_config.json").readText())
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(threads) }
            return OnnxToneConverter(cfg, env, env.createSession(File(dir, "vc.onnx").path, opts), env.createSession(File(dir, "se.onnx").path, opts))
        }

        /** Built-in voice fingerprints shipped with the converter pack: {"M1":[...256 floats...], ...}. */
        fun loadSourceEmbeddings(dir: File): Map<String, FloatArray> {
            val o = JSONObject(File(dir, "source_embeddings.json").readText())
            return o.keys().asSequence().associateWith { k -> o.getJSONArray(k).let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } } }
        }
    }
}
