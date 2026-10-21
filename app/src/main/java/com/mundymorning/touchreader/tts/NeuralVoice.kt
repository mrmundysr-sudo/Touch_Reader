package com.mundymorning.touchreader.tts

import com.mundymorning.touchreader.core.supertonic.SupertonicLanguages
import com.mundymorning.touchreader.core.supertonic.SupertonicModel
import com.mundymorning.touchreader.core.supertonic.SupertonicSynthesizer
import com.mundymorning.touchreader.core.supertonic.VoiceStyle
import java.io.File
import java.util.Locale

/** The loaded built-in neural voice (Supertonic 3), used for live narration and for rendering audiobooks. */
class NeuralVoice private constructor(
    val synthesizer: SupertonicSynthesizer,
    private val loadStyle: (String) -> VoiceStyle,
    val presets: List<String>,
    val defaultPreset: String,
) {
    /** Picks the narrator: a built-in preset such as "M1". Anything unknown becomes the default preset. */
    fun setVoice(name: String?) {
        val target = name?.takeIf { it in presets } ?: defaultPreset
        runCatching { synthesizer.style = loadStyle(target) }
    }

    fun release() = synthesizer.release()

    companion object {
        fun presetNames(packDir: File): List<String> =
            File(packDir, "voice_styles").listFiles { f -> f.extension == "json" }.orEmpty().map { it.nameWithoutExtension }.sorted()

        fun defaultPreset(presets: List<String>) = if ("M1" in presets) "M1" else presets.firstOrNull() ?: "M1"

        /** Blocking and heavy (reads the model files); call from a worker thread. */
        fun load(packDir: File, steps: Int, initialVoice: String?): NeuralVoice {
            val presets = presetNames(packDir)
            val default = defaultPreset(presets)
            fun loadStyle(name: String) = VoiceStyle.parse(File(packDir, "voice_styles/$name.json").readText())
            val lang = Locale.getDefault().language.takeIf { SupertonicLanguages.isValid(it) } ?: "na"
            val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
            val model = SupertonicModel.load(File(packDir, "onnx"), threads)
            val raw = SupertonicSynthesizer(model, loadStyle(default), lang, steps)
            return NeuralVoice(raw, ::loadStyle, presets, default).also { it.setVoice(initialVoice) }
        }
    }
}
