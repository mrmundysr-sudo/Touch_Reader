package com.mundymorning.touchreader.tts

import com.mundymorning.touchreader.core.audio.Synthesizer
import com.mundymorning.touchreader.core.pockettts.PocketTts
import com.mundymorning.touchreader.core.pockettts.PocketVoice
import com.mundymorning.touchreader.studio.VoiceLibrary
import java.io.File

/**
 * Live narration in a saved voice (Pocket TTS). The model loads once; the voice is prepared from the saved recording
 * the first time it is needed and again whenever the narrator or the chosen part of the recording changes. The pace is
 * read for every sentence, so changing it takes effect on the next sentence.
 */
class PocketLive private constructor(
    private val tts: PocketTts,
    private val library: VoiceLibrary,
) {
    @Volatile private var requested: String? = null
    private var loadedId: String? = null
    private var loadedStart = -1
    private var voice: PocketVoice? = null
    private var synth: Synthesizer? = null

    /** [name] is "custom:<profile id>"; anything else leaves no voice selected. */
    fun setVoice(name: String?) { requested = name?.takeIf { it.startsWith(Engines.CUSTOM_PREFIX) }?.removePrefix(Engines.CUSTOM_PREFIX) }

    /** Called on the synthesis thread (one sentence at a time), so the voice is prepared there on first use. */
    private fun current(): Synthesizer {
        val id = requested ?: error("No saved voice is selected")
        val start = PocketNarrator.choice(library, id).first
        if (id != loadedId || start != loadedStart || synth == null) {
            runCatching { voice?.close() }
            voice = null; synth = null
            val (embedding, _) = PocketNarrator.embeddingFor(tts, library, id, start)
            val v = tts.prepareVoice(embedding)
            voice = v
            synth = tts.synthesizer(
                v, lsdSteps = 1,
                temperature = { PocketTts.Delivery.temperature(PocketNarrator.choice(library, id).delivery) },
                pace = { PocketNarrator.choice(library, id).pace / 100.0 },
                link = { PocketNarrator.choice(library, id).link },
            )
            loadedId = id; loadedStart = start
        }
        return synth!!
    }

    val synthesizer: Synthesizer = object : Synthesizer {
        override val sampleRate: Int get() = tts.sampleRate
        override fun synthesize(text: String, speed: Float, isCancelled: () -> Boolean): ShortArray = current().synthesize(text, speed, isCancelled)
        override fun resetContext() { synth?.resetContext() }
        override fun release() { this@PocketLive.release() }
    }

    fun release() {
        runCatching { synth?.release() }
        runCatching { voice?.close() }
        voice = null; synth = null
        runCatching { tts.close() }
    }

    companion object {
        /** Blocking and heavy; call from a worker thread. */
        fun load(dir: File, library: VoiceLibrary, initialVoice: String?): PocketLive =
            PocketLive(PocketTts.load(dir, PocketNarrator.threads()), library).also { it.setVoice(initialVoice) }
    }
}
