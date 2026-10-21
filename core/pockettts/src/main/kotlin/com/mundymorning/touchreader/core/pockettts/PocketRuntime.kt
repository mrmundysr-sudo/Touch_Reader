package com.mundymorning.touchreader.core.pockettts

/** A prepared voice: the transformer's memory after it has "listened" to the reference clip. Shared by all sentences. */
interface PocketVoice : AutoCloseable

/** One sentence being generated, one frame at a time. */
interface PocketSentence : AutoCloseable {
    /** Feeds the previous frame (or null for the very first one) and returns the model's conditioning vector and end-of-speech score. */
    fun step(previousLatent: FloatArray?): Step
    class Step(val conditioning: FloatArray, val eosLogit: Float)
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
