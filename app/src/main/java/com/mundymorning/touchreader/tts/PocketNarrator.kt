package com.mundymorning.touchreader.tts

import com.mundymorning.touchreader.core.audio.Synthesizer
import com.mundymorning.touchreader.core.pockettts.PocketTts
import com.mundymorning.touchreader.core.pockettts.PocketVoice
import com.mundymorning.touchreader.core.pockettts.VoiceEmbedding
import com.mundymorning.touchreader.core.voicestudio.Dsp
import com.mundymorning.touchreader.core.voicestudio.WavIo
import com.mundymorning.touchreader.studio.VoiceLibrary
import java.io.File

/**
 * A saved voice spoken by Pocket TTS (it copies the voice from 15 seconds of the saved recording). Used for audiobook
 * export and the voice lab. Blocking and heavy to create; call [load] from a worker thread and [close] when done.
 */
class PocketNarrator private constructor(
    private val tts: PocketTts,
    private val voice: PocketVoice,
    val synthesizer: Synthesizer,
) : AutoCloseable {
    override fun close() {
        runCatching { voice.close() }
        runCatching { tts.close() }
    }

    companion object {
        const val ENGINE = "pocket-tts-onnx"
        /** How much of the recording the voice is copied from. Longer was tried: 30 s sounded worse, 45 s ran a phone out of memory. */
        const val REFERENCE_SECONDS = 15

        fun threads() = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)

        private fun version(startSeconds: Int) = "1-${REFERENCE_SECONDS}s-from${startSeconds}s" // a different part is a different voice fingerprint

        /**
         * The voice fingerprint for the part of the saved recording starting at [startSeconds]: from the profile's cache,
         * otherwise worked out now (and cached). The second value is how many seconds that took, or null if it was cached.
         */
        fun embeddingFor(tts: PocketTts, library: VoiceLibrary, profileId: String, startSeconds: Int): Pair<VoiceEmbedding, Double?> {
            val v = version(startSeconds)
            library.engineData(profileId, ENGINE, v)?.let { bytes ->
                PocketTts.decodeEmbedding(bytes, tts.bundle.conditioningDim)?.let { return it to null }
            }
            val file = library.sampleFile(profileId) ?: error("This voice has no saved recording.")
            val t0 = System.nanoTime()
            val pcm = Dsp.resample(WavIo.read(file.readBytes()), tts.bundle.sampleRate)
            val from = (startSeconds * tts.bundle.sampleRate).coerceAtMost(pcm.samples.size)
            val e = tts.encodeVoice(pcm.samples.copyOfRange(from, pcm.samples.size), REFERENCE_SECONDS.toFloat())
            library.saveEngineData(profileId, ENGINE, v, PocketTts.encodeEmbedding(e))
            return e to (System.nanoTime() - t0) / 1e9
        }

        /** [lsdSteps]: 1 is faster, 2 is the "better quality" setting. [pace]: 1.0 as spoken, lower = slower. */
        fun load(dir: File, library: VoiceLibrary, profileId: String, startSeconds: Int, lsdSteps: Int, pace: Double): PocketNarrator {
            val tts = PocketTts.load(dir, threads())
            try {
                val (embedding, _) = embeddingFor(tts, library, profileId, startSeconds)
                val voice = tts.prepareVoice(embedding)
                return PocketNarrator(tts, voice, tts.synthesizer(voice, lsdSteps = lsdSteps, pace = pace))
            } catch (t: Throwable) {
                tts.close(); throw t
            }
        }
    }
}
