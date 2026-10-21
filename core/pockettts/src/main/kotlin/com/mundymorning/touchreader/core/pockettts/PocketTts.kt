package com.mundymorning.touchreader.core.pockettts

import com.mundymorning.touchreader.core.audio.Synthesizer
import java.io.File

/** A voice taken from a recording: what the encoder made of it, ready to be saved with the voice profile. */
class VoiceEmbedding(val data: FloatArray, val frames: Int)

/**
 * Kyutai's Pocket TTS (community ONNX export) for audiobook narration in a cloned voice:
 * [load] the model folder, [encodeVoice] a recording once, then [synthesizer] speaks text in that voice.
 */
class PocketTts private constructor(
    val bundle: PocketBundle,
    val tokenizer: SentencePieceModel,
    val text: PocketText,
    val runtime: PocketRuntime,
    private val bos: FloatArray?,
) : AutoCloseable {
    val sampleRate: Int get() = bundle.sampleRate

    /** Encodes up to [maxSeconds] of 24 kHz mono audio. Slow-ish (about a second); the result should be cached. */
    fun encodeVoice(samples24k: FloatArray, maxSeconds: Float = DEFAULT_MAX_REFERENCE_SECONDS): VoiceEmbedding {
        val limit = (maxSeconds * bundle.sampleRate).toInt()
        val clip = if (samples24k.size > limit) samples24k.copyOf(limit) else samples24k
        require(clip.size >= bundle.samplesPerFrame * 8) { "The recording is too short to copy a voice from" }
        val data = runtime.encodeVoice(clip)
        return VoiceEmbedding(data, data.size / bundle.conditioningDim)
    }

    fun prepareVoice(e: VoiceEmbedding): PocketVoice {
        val all = if (bundle.insertBosBeforeVoice && bos != null) bos + e.data else e.data
        return runtime.prepareVoice(all)
    }

    fun generator(temperature: Float = 0.7f, lsdSteps: Int = 1, seed: Long? = null) = PocketGenerator(bundle, text, runtime, temperature, lsdSteps, seed = seed)

    /** A [Synthesizer] that speaks in [voice] (24 kHz, 16-bit). Closing it does not close the voice or the model. */
    fun synthesizer(voice: PocketVoice, temperature: Float = 0.7f, lsdSteps: Int = 1): Synthesizer {
        val g = generator(temperature, lsdSteps)
        return object : Synthesizer {
            override val sampleRate: Int get() = bundle.sampleRate
            override fun synthesize(text: String, speed: Float, isCancelled: () -> Boolean): ShortArray {
                val f = g.generate(text, voice, isCancelled) ?: return ShortArray(0)
                return ShortArray(f.size) { (f[it] * 32767f).coerceIn(-32768f, 32767f).toInt().toShort() }
            }
        }
    }

    override fun close() { runtime.close() }

    companion object {
        const val DEFAULT_MAX_REFERENCE_SECONDS = 15f

        /** A folder with `bundle.json`, `tokenizer.model`, `bos_before_voice.npy` and the five `.onnx` models. */
        fun load(dir: File, threads: Int): PocketTts {
            val bundle = PocketBundle.parse(File(dir, "bundle.json").readText())
            val tokenizer = SentencePieceModel.parse(File(dir, bundle.tokenizerFile).readBytes())
            val bos = bundle.bosBeforeVoiceFile?.let { Npy.readFloat32(File(dir, it).readBytes()).data }
            return PocketTts(bundle, tokenizer, PocketText(tokenizer, bundle), OrtPocketRuntime(bundle, dir, threads), bos)
        }

        /** For tests: a model assembled from a fake runtime. */
        fun forTesting(bundle: PocketBundle, tokenizer: SentencePieceModel, runtime: PocketRuntime, bos: FloatArray? = null) =
            PocketTts(bundle, tokenizer, PocketText(tokenizer, bundle), runtime, bos)

        fun encodeEmbedding(e: VoiceEmbedding): ByteArray {
            val b = java.nio.ByteBuffer.allocate(4 + e.data.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            b.putInt(e.frames); e.data.forEach { b.putFloat(it) }
            return b.array()
        }

        fun decodeEmbedding(bytes: ByteArray, conditioningDim: Int): VoiceEmbedding? {
            if (bytes.size < 8 || (bytes.size - 4) % 4 != 0) return null
            val b = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val frames = b.int
            val data = FloatArray((bytes.size - 4) / 4).also { b.asFloatBuffer().get(it) }
            return if (frames > 0 && data.size == frames * conditioningDim) VoiceEmbedding(data, frames) else null
        }
    }
}
