package com.mundymorning.touchreader.core.pockettts

import com.mundymorning.touchreader.core.audio.AudioTrim
import com.mundymorning.touchreader.core.audio.Synthesizer
import com.mundymorning.touchreader.core.audio.TimeStretch
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
        // The encoder's memory use grows with the length of audio it sees at once, so long recordings go through in
        // pieces (each a whole number of frames) and the embeddings are joined.
        val piece = SEGMENT_SECONDS * bundle.sampleRate / bundle.samplesPerFrame * bundle.samplesPerFrame
        val parts = ArrayList<FloatArray>()
        var at = 0
        while (at < clip.size) {
            var end = minOf(at + piece, clip.size)
            if (clip.size - end < MIN_TAIL_SECONDS * bundle.sampleRate) end = clip.size // a tiny tail is folded into the last piece
            parts += runtime.encodeVoice(if (at == 0 && end == clip.size) clip else clip.copyOfRange(at, end))
            at = end
        }
        val data = FloatArray(parts.sumOf { it.size }).also { a -> var o = 0; parts.forEach { p -> p.copyInto(a, o); o += p.size } }
        return VoiceEmbedding(data, data.size / bundle.conditioningDim)
    }

    fun prepareVoice(e: VoiceEmbedding): PocketVoice {
        val all = if (bundle.insertBosBeforeVoice && bos != null) bos + e.data else e.data
        return runtime.prepareVoice(all)
    }

    fun generator(temperature: Float = 0.7f, lsdSteps: Int = 1, seed: Long? = null) = PocketGenerator(bundle, text, runtime, temperature, lsdSteps, seed = seed)

    /** A [Synthesizer] that speaks in [voice] (24 kHz, 16-bit), slowed by [pace] (1.0 = as spoken; read for every sentence) and by the speed it is asked for, without changing pitch. Closing it does not close the voice or the model. */
    fun synthesizer(voice: PocketVoice, temperature: Float = 0.7f, lsdSteps: Int = 1, pace: () -> Double = { 1.0 }): Synthesizer {
        val g = generator(temperature, lsdSteps)
        return object : Synthesizer {
            override val sampleRate: Int get() = bundle.sampleRate
            override fun synthesize(text: String, speed: Float, isCancelled: () -> Boolean): ShortArray {
                val raw = g.generate(text, voice, isCancelled) ?: return ShortArray(0)
                // [pace] slows (below 1) the voice; the reader's speed setting speeds it up or slows it further. Both keep the pitch.
                val factor = (pace() * speed).coerceIn(0.5, 2.0)
                // the model leaves a little quiet before and after what it says: cut it so the pauses can be set on purpose
                val trimmed = AudioTrim.trim(raw, bundle.sampleRate)
                val f = if (kotlin.math.abs(factor - 1.0) < 0.01) trimmed else TimeStretch.stretch(trimmed, factor)
                return ShortArray(f.size) { (f[it] * 32767f).coerceIn(-32768f, 32767f).toInt().toShort() }
            }
        }
    }

    override fun close() { runtime.close() }

    companion object {
        const val DEFAULT_MAX_REFERENCE_SECONDS = 15f
        private const val SEGMENT_SECONDS = 15
        private const val MIN_TAIL_SECONDS = 2

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
