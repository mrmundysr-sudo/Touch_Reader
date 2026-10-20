package com.mundymorning.touchreader.core.voiceconvert

/**
 * Loads the converter models only when a custom voice is first used, so built-in voices never pay for them
 * (about 130 MB of weights). [sampleRate] is read from the small config file without loading the networks.
 */
class LazyToneConverter(override val sampleRate: Int, private val loader: () -> ToneConverter) : ToneConverter, AutoCloseable {
    private val delegate by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { loader() }
    @Volatile private var loaded = false

    private fun d(): ToneConverter = delegate.also { loaded = true }
    override fun embed(audio: FloatArray) = d().embed(audio)
    override fun convert(audio: FloatArray, src: FloatArray, tgt: FloatArray, isCancelled: () -> Boolean) = d().convert(audio, src, tgt, isCancelled)
    override fun close() { if (loaded) (delegate as? AutoCloseable)?.close() }
}
