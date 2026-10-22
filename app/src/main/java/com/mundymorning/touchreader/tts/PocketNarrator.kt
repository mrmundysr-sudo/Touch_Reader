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

        private const val PREFS = "pocket-lab-prefs"

        /** The part of the recording (start, in seconds) and the pace (percent) last chosen for a saved voice, or null if never chosen. */
        fun choiceOrNull(library: VoiceLibrary, profileId: String): VoiceChoice? = runCatching {
            val f = String(library.engineData(profileId, PREFS, "1")!!).split(",").map { it.trim() }
            // Linking sentences was tried and skipped sentences, so it is always off whatever was saved; the code is kept for later.
            VoiceChoice(f[0].toInt(), f[1].toInt().coerceIn(80, 100), f.getOrNull(2)?.toIntOrNull()?.coerceIn(0, 2) ?: 1, false)
        }.getOrNull()

        fun choice(library: VoiceLibrary, profileId: String): VoiceChoice = choiceOrNull(library, profileId) ?: VoiceChoice(0, 100)

        fun saveChoice(library: VoiceLibrary, profileId: String, c: VoiceChoice) {
            runCatching { library.saveEngineData(profileId, PREFS, "1", "${c.start},${c.pace.coerceIn(80, 100)},${c.delivery.coerceIn(0, 2)},${if (c.link) 1 else 0}".toByteArray()) }
        }

        /** Changes the part and pace, keeping the delivery and linking choices. */
        fun saveChoice(library: VoiceLibrary, profileId: String, startSeconds: Int, pacePercent: Int) =
            saveChoice(library, profileId, choice(library, profileId).copy(start = startSeconds, pace = pacePercent))

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

        /**
         * [lsdSteps]: 1 is faster, 2 is the "better quality" setting. [pace]: 1.0 as spoken, lower = slower. [temperature] is the
         * delivery (random variation) and [link] lets each piece carry on from the one before; all are read for every piece.
         */
        fun load(
            dir: File, library: VoiceLibrary, profileId: String, startSeconds: Int, lsdSteps: Int,
            pace: () -> Double, temperature: () -> Float = { 0.7f }, link: () -> Boolean = { false },
        ): PocketNarrator {
            val tts = PocketTts.load(dir, threads())
            try {
                val (embedding, _) = embeddingFor(tts, library, profileId, startSeconds)
                val voice = tts.prepareVoice(embedding)
                return PocketNarrator(tts, voice, tts.synthesizer(voice, temperature, lsdSteps, pace, link))
            } catch (t: Throwable) {
                tts.close(); throw t
            }
        }
    }
}

/** What was last chosen for a saved voice: the part of the recording, the pace, the delivery (0 steady, 1 normal, 2 lively) and whether pieces are linked. */
data class VoiceChoice(val start: Int, val pace: Int, val delivery: Int = 1, val link: Boolean = false) {
    val first: Int get() = start
    val second: Int get() = pace
}
