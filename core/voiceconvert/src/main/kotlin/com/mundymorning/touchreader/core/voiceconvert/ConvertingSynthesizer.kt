package com.mundymorning.touchreader.core.voiceconvert

import com.mundymorning.touchreader.core.audio.Synthesizer
import com.mundymorning.touchreader.core.voicestudio.Dsp
import com.mundymorning.touchreader.core.voicestudio.Pcm

/** The fast base voice: produces float speech and can switch between its built-in preset voices. */
interface BaseVoice {
    val sampleRate: Int
    fun usePreset(name: String)
    fun synthesizeFloat(text: String, speed: Float, isCancelled: () -> Boolean): FloatArray?
    fun release() {}
}

/**
 * Narrates with either a built-in preset voice, or a custom voice: base voice (nearest preset) -> tone converter.
 * Output is always at the base voice's sample rate so the audio device never has to be reopened.
 */
class ConvertingSynthesizer(
    private val base: BaseVoice,
    private val converter: ToneConverter?,
    private val presetFingerprints: Map<String, FloatArray>,
) : Synthesizer {
    override val sampleRate: Int get() = base.sampleRate

    private class Custom(val forcePreset: String?, val fingerprint: () -> FloatArray?) { @Volatile var resolved: FloatArray? = null; @Volatile var preset: String? = null }

    @Volatile private var custom: Custom? = null

    fun usePreset(name: String) { custom = null; base.usePreset(name) }

    /** [fingerprint] may be slow (it can analyse a recording); it runs on the synthesis thread the first time it is needed. */
    fun useCustom(forcePreset: String? = null, fingerprint: () -> FloatArray?) { custom = Custom(forcePreset, fingerprint) }

    override fun synthesize(text: String, speed: Float, isCancelled: () -> Boolean): ShortArray {
        val c = custom
        val conv = converter
        if (c == null || conv == null) {
            val wav = base.synthesizeFloat(text, speed, isCancelled) ?: return ShortArray(0)
            return toShort(wav)
        }
        val target = c.resolved ?: c.fingerprint()?.also {
            c.resolved = it
            c.preset = (c.forcePreset ?: PresetChooser.choose(it, presetFingerprints)).also { p -> base.usePreset(p) }
        } ?: error("This voice's recording is no longer available, so it cannot be used.")
        val preset = c.preset!!
        val wav = base.synthesizeFloat(text, speed, isCancelled) ?: return ShortArray(0)
        if (isCancelled()) return ShortArray(0)
        val atConv = Dsp.resample(Pcm(base.sampleRate, wav), conv.sampleRate).samples
        if (atConv.size < 2048) return toShort(wav)               // too short to convert; keep the base voice
        val converted = conv.convert(atConv, presetFingerprints.getValue(preset), target, isCancelled) ?: return ShortArray(0)
        return toShort(Dsp.resample(Pcm(conv.sampleRate, converted), base.sampleRate).samples)
    }

    private fun toShort(w: FloatArray) = ShortArray(w.size) { (w[it] * 32767f).coerceIn(-32768f, 32767f).toInt().toShort() }

    override fun release() { base.release(); (converter as? AutoCloseable)?.close() }
}
