package com.mundymorning.touchreader.core.pockettts

import java.util.Random
import kotlin.math.ceil
import kotlin.math.sqrt

/** The generation loop of the reference `pocket_tts_onnx.py`, step for step. */
class PocketGenerator(
    private val bundle: PocketBundle,
    private val text: PocketText,
    private val runtime: PocketRuntime,
    private val temperature: Float = 0.7f,
    private val lsdSteps: Int = 1,
    private val decodeChunkFrames: Int = 12,
    seed: Long? = null,
) {
    private val random = if (seed != null) Random(seed) else Random()

    /** Speaks [raw] (which may be several sentences) in [voice]. Returns mono audio at [PocketBundle.sampleRate], or null if cancelled. */
    fun generate(raw: String, voice: PocketVoice, isCancelled: () -> Boolean = { false }): FloatArray? {
        val parts = ArrayList<FloatArray>()
        for (chunk in text.splitIntoChunks(raw)) {
            if (isCancelled()) return null
            val prepared = text.prepare(chunk)
            val framesAfterEos = bundle.recommendedFramesAfterEos ?: (prepared.framesAfterEosGuess + 2)
            val ids = text.tokenize(chunk)
            val audio = speakChunk(voice, ids, framesAfterEos, isCancelled) ?: return null
            parts += audio
        }
        val out = FloatArray(parts.sumOf { it.size })
        var o = 0
        for (p in parts) { p.copyInto(out, o); o += p.size }
        return out
    }

    private fun speakChunk(voice: PocketVoice, ids: LongArray, framesAfterEos: Int, isCancelled: () -> Boolean): FloatArray? {
        val frameLimit = ceil((ids.size / TOKENS_PER_SECOND_ESTIMATE + GEN_SECONDS_PADDING) * bundle.frameRate).toInt()
        val dt = 1f / lsdSteps
        val std = sqrt(temperature.toDouble()).toFloat()
        val latents = ArrayList<FloatArray>()
        runtime.newSentence(voice, ids).use { sentence ->
            var previous: FloatArray? = null
            var eosStep = -1
            for (step in 0 until frameLimit) {
                if (isCancelled()) return null
                val out = sentence.step(previous)
                if (out.eosLogit > EOS_THRESHOLD && eosStep < 0) eosStep = step
                if (eosStep >= 0 && step >= eosStep + framesAfterEos) break
                var x = FloatArray(bundle.latentDim) { if (temperature > 0f) (random.nextGaussian().toFloat() * std) else 0f }
                for (j in 0 until lsdSteps) {
                    val flow = runtime.flow(out.conditioning, j.toFloat() / lsdSteps, j.toFloat() / lsdSteps + dt, x)
                    x = FloatArray(x.size) { x[it] + flow[it] * dt }
                }
                latents += x
                previous = x
            }
        }
        if (latents.isEmpty()) return FloatArray(0)
        val parts = ArrayList<FloatArray>()
        runtime.newDecoder().use { decoder ->
            var i = 0
            while (i < latents.size) {
                if (isCancelled()) return null
                val end = minOf(i + decodeChunkFrames, latents.size)
                parts += decoder.decode(latents.subList(i, end))
                i = end
            }
        }
        val out = FloatArray(parts.sumOf { it.size })
        var o = 0
        for (p in parts) { p.copyInto(out, o); o += p.size }
        return out
    }

    private companion object {
        const val TOKENS_PER_SECOND_ESTIMATE = 3.0
        const val GEN_SECONDS_PADDING = 2.0
        const val EOS_THRESHOLD = -4.0f
    }
}
