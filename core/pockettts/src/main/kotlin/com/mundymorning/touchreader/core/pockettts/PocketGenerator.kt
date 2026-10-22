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
    fun generate(raw: String, voice: PocketVoice, isCancelled: () -> Boolean = { false }): FloatArray? =
        generateLinked(raw, voice, null, false, isCancelled)?.audio

    /** Audio, plus (when linking) the model's memory after the last chunk, which the caller now owns and must close. */
    class Generated(val audio: FloatArray, val state: PocketVoice?)

    /**
     * Like [generate], but with *linking*: when [carry] is true each chunk starts from where the one before it left off
     * (its text and speech stay in the model's memory) instead of from the bare [voice], so the delivery can carry over.
     * [continuation] is the memory left by the previous call (or null to start from [voice]). If the memory would run
     * out, that chunk starts again from [voice]. The reference model was not trained to be used this way, so this is
     * an experiment.
     */
    fun generateLinked(raw: String, voice: PocketVoice, continuation: PocketVoice?, carry: Boolean, isCancelled: () -> Boolean = { false }): Generated? {
        val parts = ArrayList<FloatArray>()
        var current: PocketVoice? = continuation
        var owned: PocketVoice? = null // memory states made inside this call (never the caller's continuation)
        try {
            for (chunk in text.splitIntoChunks(raw)) {
                if (isCancelled()) return null
                val prepared = text.prepare(chunk)
                val framesAfterEos = bundle.recommendedFramesAfterEos ?: (prepared.framesAfterEosGuess + 2)
                val ids = text.tokenize(chunk)
                val worst = ids.size + frameLimit(ids.size)
                val start = if (carry && current != null && current.frames + worst <= MEMORY_BUDGET_FRAMES) current else voice
                val spoken = speakChunk(start, ids, framesAfterEos, carry, isCancelled) ?: return null
                parts += spoken.audio
                if (carry) {
                    val next = spoken.state
                    if (owned != null) owned.close()
                    owned = next; current = next
                } 
            }
            val out = FloatArray(parts.sumOf { it.size })
            var o = 0
            for (p in parts) { p.copyInto(out, o); o += p.size }
            val result = Generated(out, if (carry) owned else null)
            owned = null // handed to the caller
            return result
        } finally {
            owned?.close()
        }
    }

    private fun frameLimit(tokens: Int) = ceil((tokens / TOKENS_PER_SECOND_ESTIMATE + GEN_SECONDS_PADDING) * bundle.frameRate).toInt()

    private class Spoken(val audio: FloatArray, val state: PocketVoice?)

    private fun speakChunk(voice: PocketVoice, ids: LongArray, framesAfterEos: Int, carry: Boolean, isCancelled: () -> Boolean): Spoken? {
        val frameLimit = frameLimit(ids.size)
        val dt = 1f / lsdSteps
        val std = sqrt(temperature.toDouble()).toFloat()
        val latents = ArrayList<FloatArray>()
        var state: PocketVoice? = null
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
            if (carry) state = sentence.detachAsVoice(voice.frames + ids.size + latents.size)
        }
        try {
            if (latents.isEmpty()) return Spoken(FloatArray(0), state)
            val parts = ArrayList<FloatArray>()
            runtime.newDecoder().use { decoder ->
                var i = 0
                while (i < latents.size) {
                    if (isCancelled()) { state?.close(); return null }
                    val end = minOf(i + decodeChunkFrames, latents.size)
                    parts += decoder.decode(latents.subList(i, end))
                    i = end
                }
            }
            val out = FloatArray(parts.sumOf { it.size })
            var o = 0
            for (p in parts) { p.copyInto(out, o); o += p.size }
            return Spoken(out, state)
        } catch (t: Throwable) {
            state?.close(); throw t
        }
    }

    private companion object {
        const val TOKENS_PER_SECOND_ESTIMATE = 3.0
        const val GEN_SECONDS_PADDING = 2.0
        const val EOS_THRESHOLD = -4.0f
        /** The transformer keeps 1000 positions; stay well inside so linking never overruns it. */
        const val MEMORY_BUDGET_FRAMES = 900
    }
}
