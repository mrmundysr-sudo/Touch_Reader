package com.mundymorning.touchreader.core.audio

/** Turns text into raw audio. Implemented by neural engines (and, later, the audiobook exporter reuses it). */
interface Synthesizer {
    val sampleRate: Int

    /**
     * Blocking. Returns mono 16-bit PCM for [text] at [sampleRate]. [speed] is 1.0 for normal.
     * Long-running implementations should poll [isCancelled] and return early (the result is then discarded).
     */
    fun synthesize(text: String, speed: Float, isCancelled: () -> Boolean): ShortArray

    fun release() {}

    /**
     * The next text spoken does not follow on from what was spoken before (a new paragraph, a jump, a restart), so a
     * synthesizer that carries delivery over from sentence to sentence should forget it. Safe to call from any thread.
     */
    fun resetContext() {}
}

/** Where PCM goes to be heard (an AudioTrack on Android). */
interface AudioSink {
    fun open(sampleRate: Int)

    /** Blocks until the device has accepted all of [pcm] (or [isCancelled] turns true). */
    fun write(pcm: ShortArray, isCancelled: () -> Boolean)

    /** Drop anything buffered and stop immediately; must unblock a pending [write]. */
    fun flush()

    fun close()
}
