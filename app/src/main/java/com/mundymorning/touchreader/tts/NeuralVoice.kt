package com.mundymorning.touchreader.tts

import com.mundymorning.touchreader.core.supertonic.SupertonicLanguages
import com.mundymorning.touchreader.core.supertonic.SupertonicModel
import com.mundymorning.touchreader.core.supertonic.SupertonicSynthesizer
import com.mundymorning.touchreader.core.supertonic.VoiceStyle
import com.mundymorning.touchreader.core.voiceconvert.BaseVoice
import com.mundymorning.touchreader.core.voiceconvert.ConverterConfig
import com.mundymorning.touchreader.core.voiceconvert.ConvertingSynthesizer
import com.mundymorning.touchreader.core.voiceconvert.EmbeddingCodec
import com.mundymorning.touchreader.core.voiceconvert.LazyToneConverter
import com.mundymorning.touchreader.core.voiceconvert.OnnxToneConverter
import com.mundymorning.touchreader.core.voiceconvert.PresetChooser
import com.mundymorning.touchreader.core.voicestudio.Pcm
import com.mundymorning.touchreader.core.voicestudio.Dsp
import com.mundymorning.touchreader.core.voicestudio.WavIo
import com.mundymorning.touchreader.studio.VoiceLibrary
import java.io.File
import java.util.Locale

/**
 * The loaded neural voice: the Supertonic model, and (when the cloning add-on is installed) the tone converter
 * that makes a saved voice. Used for live narration and for rendering audiobooks.
 */
class NeuralVoice private constructor(
    val synthesizer: ConvertingSynthesizer,
    private val converter: LazyToneConverter?,
    private val library: VoiceLibrary?,
    private val converterInstalled: Boolean,
    val presets: List<String>,
    val defaultPreset: String,
    private val presetFingerprints: Map<String, FloatArray>,
) {
    /** How close a saved voice gets to its recording, measured with the converter's own voice fingerprint (1.0 = identical). */
    class MatchReport(
        val basePreset: String,
        /** Recording vs the built-in voice the conversion starts from: how much there is to change. */
        val recordingVsBase: Double,
        /** The base voice itself vs the recording. */
        val baseOutput: Double,
        /** Strength multiplier -> similarity of the converted speech to the recording. */
        val converted: List<Pair<Double, Double>>,
    )

    /** Speaks [sentence] with the nearest built-in voice and with the saved voice (at several strengths) and compares both with the recording. Slow; call from a worker thread. */
    fun checkMatch(voiceName: String, sentence: String, strengths: List<Double> = listOf(1.0, 1.4, 1.8)): MatchReport? {
        val id = voiceName.takeIf { it.startsWith(Engines.CUSTOM_PREFIX) }?.removePrefix(Engines.CUSTOM_PREFIX) ?: return null
        val conv = converter ?: return null
        val target = fingerprintFor(id) ?: return null
        val preset = PresetChooser.choose(target, presetFingerprints)
        val src = presetFingerprints[preset] ?: return null
        synthesizer.usePreset(preset)
        val pcm = synthesizer.synthesize(sentence, 1f) { false }
        if (pcm.isEmpty()) return null
        val atConv = Dsp.resample(Pcm(synthesizer.sampleRate, FloatArray(pcm.size) { pcm[it] / 32768f }), conv.sampleRate).samples
        val baseOutput = EmbeddingCodec.cosine(conv.embed(atConv), target)
        val results = strengths.mapNotNull { a ->
            val tgt = FloatArray(target.size) { src[it] + (a * (target[it] - src[it])).toFloat() }
            val out = conv.convert(atConv, src, tgt) { false } ?: return@mapNotNull null
            a to EmbeddingCodec.cosine(conv.embed(out), target)
        }
        return MatchReport(preset, EmbeddingCodec.cosine(src, target), baseOutput, results)
    }

    /** Picks the narrator: a built-in preset such as "M1", or "custom:<id>" for a saved voice. Anything unknown becomes the default preset. */
    fun setVoice(name: String?) {
        val id = name?.takeIf { it.startsWith(Engines.CUSTOM_PREFIX) }?.removePrefix(Engines.CUSTOM_PREFIX)
        if (id != null && converterInstalled && library?.profiles?.value?.any { it.id == id } == true) {
            synthesizer.useCustom { fingerprintFor(id) }
            return
        }
        val target = name?.takeIf { it in presets } ?: defaultPreset
        runCatching { synthesizer.usePreset(target) }
    }

    /** Fingerprint of a saved voice: cached with the profile, otherwise computed once from its recording (on the synthesis thread). */
    private fun fingerprintFor(id: String): FloatArray? {
        val lib = library ?: return null
        lib.engineData(id, CLONE_ENGINE, CLONE_VERSION)?.let { bytes ->
            runCatching { EmbeddingCodec.decode(bytes) }.getOrNull()?.takeIf { it.size == FINGERPRINT_SIZE }?.let { return it }
        }
        val file = lib.sampleFile(id) ?: return null
        val conv = converter ?: return null
        val pcm = WavIo.read(file.readBytes())
        val fp = conv.embed(Dsp.resample(pcm, conv.sampleRate).samples)
        lib.saveEngineData(id, CLONE_ENGINE, CLONE_VERSION, EmbeddingCodec.encode(fp))
        return fp
    }

    fun release() = synthesizer.release()

    companion object {
        private const val CLONE_ENGINE = "openvoice-v2-converter"
        private const val CLONE_VERSION = "1"
        private const val FINGERPRINT_SIZE = 256

        fun presetNames(packDir: File): List<String> =
            File(packDir, "voice_styles").listFiles { f -> f.extension == "json" }.orEmpty().map { it.nameWithoutExtension }.sorted()

        fun defaultPreset(presets: List<String>) = if ("M1" in presets) "M1" else presets.firstOrNull() ?: "M1"

        /** Blocking and heavy (reads the model files); call from a worker thread. */
        fun load(
            packDir: File,
            converterDir: File?,
            library: VoiceLibrary?,
            steps: Int,
            initialVoice: String?,
            trace: ((String) -> Unit)? = null,
        ): NeuralVoice {
            val presets = presetNames(packDir)
            val default = defaultPreset(presets)
            fun loadStyle(name: String) = VoiceStyle.parse(File(packDir, "voice_styles/$name.json").readText())
            val lang = Locale.getDefault().language.takeIf { SupertonicLanguages.isValid(it) } ?: "na"
            val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
            val model = SupertonicModel.load(File(packDir, "onnx"), threads)
            val raw = SupertonicSynthesizer(model, loadStyle(default), lang, steps)
            val base = object : BaseVoice {
                override val sampleRate = raw.sampleRate
                override fun usePreset(name: String) { raw.style = loadStyle(name) }
                override fun synthesizeFloat(text: String, speed: Float, isCancelled: () -> Boolean) = raw.synthesizeFloat(text, speed, isCancelled)
                override fun release() = raw.release()
            }
            val conv = converterDir?.let { d ->
                val cfg = ConverterConfig.parse(File(d, "converter_config.json").readText())
                LazyToneConverter(cfg.sampleRate) { OnnxToneConverter.load(d, threads) }
            }
            val fingerprints = converterDir?.let { OnnxToneConverter.loadSourceEmbeddings(it) } ?: emptyMap()
            val s = ConvertingSynthesizer(base, conv, fingerprints)
            s.trace = trace
            val voice = NeuralVoice(s, conv, library, converterDir != null, presets, default, fingerprints)
            voice.setVoice(initialVoice)
            return voice
        }
    }
}
