package com.mundymorning.touchreader.core.supertonic

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.mundymorning.touchreader.core.audio.Synthesizer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

/** Values from the pack's tts.json. */
data class SupertonicConfig(val sampleRate: Int, val baseChunkSize: Int, val chunkCompressFactor: Int, val latentDim: Int) {
    companion object {
        fun parse(json: String): SupertonicConfig {
            val o = JSONObject(json)
            val ae = o.getJSONObject("ae"); val ttl = o.getJSONObject("ttl")
            return SupertonicConfig(ae.getInt("sample_rate"), ae.getInt("base_chunk_size"), ttl.getInt("chunk_compress_factor"), ttl.getInt("latent_dim"))
        }
    }
}

/** One preset voice (a pair of style tensors). Supertonic has presets only; it cannot clone from a recording. */
class VoiceStyle(val ttl: FloatArray, val ttlShape: LongArray, val dp: FloatArray, val dpShape: LongArray) {
    companion object {
        fun parse(json: String): VoiceStyle {
            val o = JSONObject(json)
            val (ttl, ttlShape) = flatten(o.getJSONObject("style_ttl"))
            val (dp, dpShape) = flatten(o.getJSONObject("style_dp"))
            return VoiceStyle(ttl, ttlShape, dp, dpShape)
        }

        private fun flatten(node: JSONObject): Pair<FloatArray, LongArray> {
            val dims = node.getJSONArray("dims").let { LongArray(it.length()) { i -> it.getLong(i) } }
            val out = FloatArray(dims.fold(1L) { a, b -> a * b }.toInt())
            var idx = 0
            fun walk(a: JSONArray) {
                for (i in 0 until a.length()) {
                    val v = a.get(i)
                    if (v is JSONArray) walk(v) else out[idx++] = (v as Number).toFloat()
                }
            }
            walk(node.getJSONArray("data"))
            require(idx == out.size) { "Voice style data does not match its dims" }
            return out to dims
        }
    }
}

/** Pure latent helpers (kept separate from ONNX so they can be unit tested). */
internal object Latents {
    fun latentLength(wavSamples: Long, cfg: SupertonicConfig): Int {
        val chunk = cfg.baseChunkSize.toLong() * cfg.chunkCompressFactor
        return ((wavSamples + chunk - 1) / chunk).toInt()
    }

    /** Gaussian noise [latentDim*compress, latentLen] with the tail beyond the audio length masked to zero. */
    fun noisy(durationSec: Float, cfg: SupertonicConfig, rng: Random): Triple<FloatArray, FloatArray, Int> {
        val wavLen = (durationSec * cfg.sampleRate).toLong()
        val len = latentLength(wavLen, cfg)
        val dim = cfg.latentDim * cfg.chunkCompressFactor
        val mask = FloatArray(len) { 1f }
        val x = FloatArray(dim * len)
        for (d in 0 until dim) for (t in 0 until len) {
            val u1 = maxOf(1e-10, rng.nextDouble()); val u2 = rng.nextDouble()
            x[d * len + t] = (sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)).toFloat() * mask[t]
        }
        return Triple(x, mask, len)
    }
}

/**
 * The four Supertonic ONNX networks. Pipeline (from the upstream sample): duration predictor ->
 * text encoder -> N-step flow-matching vector estimator on a noisy latent -> vocoder.
 */
class SupertonicModel private constructor(
    val config: SupertonicConfig,
    private val indexer: UnicodeIndexer,
    private val env: OrtEnvironment,
    private val dp: OrtSession,
    private val textEnc: OrtSession,
    private val vectorEst: OrtSession,
    private val vocoder: OrtSession,
) : AutoCloseable {

    /** Returns mono float audio in [-1, 1], or null if [isCancelled] became true. */
    fun infer(text: String, lang: String, style: VoiceStyle, speed: Float, totalSteps: Int, isCancelled: () -> Boolean, rng: Random = Random()): FloatArray? {
        val ids = indexer.encode(SupertonicText.preprocess(text, lang))
        if (ids.isEmpty()) return FloatArray(0)
        val n = ids.size
        val maskArr = FloatArray(n) { 1f }

        OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(1, n.toLong())).use { idsT ->
        OnnxTensor.createTensor(env, FloatBuffer.wrap(maskArr), longArrayOf(1, 1, n.toLong())).use { maskT ->
        OnnxTensor.createTensor(env, FloatBuffer.wrap(style.dp), style.dpShape).use { dpStyleT ->
        OnnxTensor.createTensor(env, FloatBuffer.wrap(style.ttl), style.ttlShape).use { ttlStyleT ->
            val duration = dp.run(mapOf("text_ids" to idsT, "style_dp" to dpStyleT, "text_mask" to maskT)).use { r ->
                (r.get(0) as OnnxTensor).floatBuffer.get(0) / speed
            }
            if (isCancelled()) return null

            textEnc.run(mapOf("text_ids" to idsT, "style_ttl" to ttlStyleT, "text_mask" to maskT)).use { enc ->
                val textEmb = enc.get(0) as OnnxTensor
                var (xt, latentMask, latentLen) = Latents.noisy(duration, config, rng)
                val dim = config.latentDim * config.chunkCompressFactor
                val latentShape = longArrayOf(1, dim.toLong(), latentLen.toLong())
                OnnxTensor.createTensor(env, FloatBuffer.wrap(latentMask), longArrayOf(1, 1, latentLen.toLong())).use { lmT ->
                OnnxTensor.createTensor(env, floatArrayOf(totalSteps.toFloat())).use { totalT ->
                    for (step in 0 until totalSteps) {
                        if (isCancelled()) return null
                        OnnxTensor.createTensor(env, floatArrayOf(step.toFloat())).use { stepT ->
                        OnnxTensor.createTensor(env, FloatBuffer.wrap(xt), latentShape).use { xtT ->
                            vectorEst.run(
                                mapOf(
                                    "noisy_latent" to xtT, "text_emb" to textEmb, "style_ttl" to ttlStyleT,
                                    "latent_mask" to lmT, "text_mask" to maskT, "current_step" to stepT, "total_step" to totalT,
                                )
                            ).use { out ->
                                val buf = (out.get(0) as OnnxTensor).floatBuffer
                                val next = FloatArray(buf.remaining()); buf.get(next)
                                xt = next
                            }
                        } }
                    }
                }}
                OnnxTensor.createTensor(env, FloatBuffer.wrap(xt), latentShape).use { latentT ->
                    vocoder.run(mapOf("latent" to latentT)).use { out ->
                        val buf = (out.get(0) as OnnxTensor).floatBuffer
                        val wav = FloatArray(buf.remaining()); buf.get(wav)
                        val keep = minOf(wav.size, (config.sampleRate * duration).toInt())
                        return wav.copyOf(keep)
                    }
                }
            }
        } } } }
    }

    override fun close() {
        listOf(dp, textEnc, vectorEst, vocoder).forEach { runCatching { it.close() } }
    }

    companion object {
        /** [dir] holds duration_predictor.onnx, text_encoder.onnx, vector_estimator.onnx, vocoder.onnx, tts.json, unicode_indexer.json. */
        fun load(dir: File, threads: Int = 2): SupertonicModel {
            val config = SupertonicConfig.parse(File(dir, "tts.json").readText())
            val table = JSONArray(File(dir, "unicode_indexer.json").readText()).let { a -> LongArray(a.length()) { a.getLong(it) } }
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(threads) }
            fun session(name: String) = env.createSession(File(dir, name).path, opts)
            return SupertonicModel(
                config, UnicodeIndexer(table), env,
                session("duration_predictor.onnx"), session("text_encoder.onnx"),
                session("vector_estimator.onnx"), session("vocoder.onnx"),
            )
        }
    }
}

/** Adapts [SupertonicModel] to the engine-neutral [Synthesizer] so the buffered pipeline can drive it. */
class SupertonicSynthesizer(
    private val model: SupertonicModel,
    private val style: VoiceStyle,
    private val lang: String = "en",
    private val totalSteps: Int = 8,
    private val chunkSilenceSec: Float = 0.3f,
) : Synthesizer {
    override val sampleRate: Int get() = model.config.sampleRate

    override fun synthesize(text: String, speed: Float, isCancelled: () -> Boolean): ShortArray {
        val parts = ArrayList<FloatArray>()
        for ((i, chunk) in SupertonicText.chunk(text, SupertonicText.maxChunkFor(lang)).withIndex()) {
            if (isCancelled()) return ShortArray(0)
            val wav = model.infer(chunk, lang, style, speed, totalSteps, isCancelled) ?: return ShortArray(0)
            if (i > 0) parts += FloatArray((chunkSilenceSec * sampleRate).toInt())
            parts += wav
        }
        val out = ShortArray(parts.sumOf { it.size })
        var o = 0
        for (p in parts) for (s in p) out[o++] = (s * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
        return out
    }

    override fun release() = model.close()
}
