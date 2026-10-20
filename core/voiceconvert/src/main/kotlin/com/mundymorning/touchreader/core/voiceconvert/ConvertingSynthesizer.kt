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

    /** Progress and timing messages for the UI ("Preparing your voice...", "converter took 12 s"); called from the synthesis thread. */
    @Volatile var trace: ((String) -> Unit)? = null
    private fun say(m: String) { trace?.invoke(m) }
    private fun secs(fromNanos: Long) = "%.1f".format((System.nanoTime() - fromNanos) / 1e9)

    fun usePreset(name: String) { custom = null; base.usePreset(name) }

    /** [fingerprint] may be slow (it can analyse a recording); it runs on the synthesis thread the first time it is needed. */
    fun useCustom(forcePreset: String? = null, fingerprint: () -> FloatArray?) { custom = Custom(forcePreset, fingerprint) }

    override fun synthesize(text: String, speed: Float, isCancelled: () -> Boolean): ShortArray {
        val c = custom
        val conv = converter
        if (c == null || conv == null) {
            say("Speaking with the built-in voice...")
            val t0 = System.nanoTime()
            val wav = base.synthesizeFloat(text, speed, isCancelled) ?: return ShortArray(0)
            say("Built-in voice: ${audioSeconds(wav.size)} s of speech took ${secs(t0)} s")
            return toShort(wav)
        }
        val target = c.resolved ?: run {
            say("Preparing your voice (one time, from your recording)...")
            val t0 = System.nanoTime()
            c.fingerprint()?.also {
                c.resolved = it
                c.preset = (c.forcePreset ?: PresetChooser.choose(it, presetFingerprints)).also { p -> base.usePreset(p) }
                say("Your voice is ready (${secs(t0)} s). Base voice: ${c.preset}")
            }
        } ?: error("This voice's recording is no longer available, so it cannot be used.")
        val preset = c.preset!!
        say("Step 1 of 2: base voice...")
        val t1 = System.nanoTime()
        val wav = base.synthesizeFloat(text, speed, isCancelled) ?: return ShortArray(0)
        if (isCancelled()) return ShortArray(0)
        val baseTime = secs(t1)
        val atConv = Dsp.resample(Pcm(base.sampleRate, wav), conv.sampleRate).samples
        if (atConv.size < 2048) return toShort(wav)               // too short to convert; keep the base voice
        say("Step 2 of 2: changing to your voice... (base voice took $baseTime s)")
        val t2 = System.nanoTime()
        val converted = conv.convert(atConv, presetFingerprints.getValue(preset), target, isCancelled) ?: return ShortArray(0)
        say("Your voice: ${audioSeconds(wav.size)} s of speech; base voice $baseTime s + your voice ${secs(t2)} s")
        return toShort(Dsp.resample(Pcm(conv.sampleRate, converted), base.sampleRate).samples)
    }

    private fun audioSeconds(samples: Int) = "%.1f".format(samples.toDouble() / base.sampleRate)

    private fun toShort(w: FloatArray) = ShortArray(w.size) { (w[it] * 32767f).coerceIn(-32768f, 32767f).toInt().toShort() }

    override fun release() { base.release(); (converter as? AutoCloseable)?.close() }
}
