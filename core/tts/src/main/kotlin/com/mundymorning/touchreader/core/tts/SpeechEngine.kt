package com.mundymorning.touchreader.core.tts

/**
 * A text-to-speech backend. Utterances are queued in order; the engine reports
 * progress through [Listener]. Android TTS, and later Piper/Kokoro/cloning engines,
 * all implement this so the reader and player never depend on one technology.
 */
interface SpeechEngine {
    interface Listener {
        fun onStart(id: Int)
        fun onDone(id: Int)
        fun onError(id: Int, message: String)
    }

    var listener: Listener?

    /** Queue [text] after anything already queued. [id] is echoed back in callbacks. */
    fun enqueue(id: Int, text: String)

    /** Immediately stop speaking and discard everything queued. */
    fun stop()

    fun setSpeed(speed: Float)
    fun release()
}
