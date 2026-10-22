package com.mundymorning.touchreader.core.pockettts

/** A prepared voice: the transformer's memory after it has "listened" to the reference clip. Shared by all sentences. */
interface PocketVoice : AutoCloseable {
    /** How many positions of the transformer's fixed-size memory this voice (plus anything spoken after it) already takes. */
    val frames: Int get() = 0
}

/** One sentence being generated, one frame at a time. */
interface PocketSentence : AutoCloseable {
    /** Feeds the previous frame (or null for the very first one) and returns the model's conditioning vector and end-of-speech score. */
    fun step(previousLatent: FloatArray?): Step
    class Step(val conditioning: FloatArray, val eosLogit: Float)

    /**
     * Hands over the transformer's memory as it is now (the voice, the text and everything spoken so far) as a voice that
     * a following sentence can start from, so it carries on from what was just said. [framesUsed] is how much of the
     * fixed-size memory that takes. The caller must close it. Null if this runtime cannot do it.
     */
    fun detachAsVoice(framesUsed: Int): PocketVoice? = null
}

/** Turns frames into audio, keeping the decoder's memory between calls. */
interface PocketDecoder : AutoCloseable {
    fun decode(latents: List<FloatArray>): FloatArray
}

/** The five neural models behind a small interface, so the generation loop can be tested without them. */
interface PocketRuntime : AutoCloseable {
    /** Reference audio (24 kHz mono) -> voice embeddings, row-major frames x conditioningDim. */
    fun encodeVoice(samples24k: FloatArray): FloatArray

    /** [embeddings] are frames x conditioningDim (any begin-of-voice marker is added by the caller). */
    fun prepareVoice(embeddings: FloatArray): PocketVoice

    fun newSentence(voice: PocketVoice, tokenIds: LongArray): PocketSentence

    /** One Euler step of the flow network. */
    fun flow(conditioning: FloatArray, s: Float, t: Float, x: FloatArray): FloatArray

    fun newDecoder(): PocketDecoder
}
