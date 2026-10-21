package com.mundymorning.touchreader.core.pockettts

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.LongBuffer

/** [PocketRuntime] on ONNX Runtime: the five exported models, with their state tensors threaded from call to call. */
class OrtPocketRuntime(private val bundle: PocketBundle, private val dir: File, threads: Int) : PocketRuntime {
    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        setInterOpNumThreads(1)
    }

    private fun session(vararg names: String): OrtSession {
        val f = names.map { File(dir, it) }.firstOrNull { it.isFile } ?: error("Missing model file: ${names.joinToString(" or ")}")
        return env.createSession(f.path, options)
    }

    private val mimiEncoder = session("mimi_encoder.onnx")
    private val textConditioner = session("text_conditioner.onnx")
    private val flowMain = session("flow_lm_main_int8.onnx", "flow_lm_main.onnx")
    private val flowNet = session("flow_lm_flow_int8.onnx", "flow_lm_flow.onnx")
    private val mimiDecoder = session("mimi_decoder_int8.onnx", "mimi_decoder.onnx")

    /** Decoder memory at the start of every sentence. Never modified (outputs are new tensors), so it is shared. */
    private val sharedStart = ArrayList<OnnxTensor>()
    private val mimiStart: Map<String, OnnxTensor> by lazy { initialState(bundle.mimiState).also { sharedStart += it.values } }
    private val emptySequence: OnnxTensor by lazy { floats(floatArrayOf(), longArrayOf(1, 0, bundle.latentDim.toLong())).also { sharedStart += it } }
    private val emptyText: OnnxTensor by lazy { floats(floatArrayOf(), longArrayOf(1, 0, bundle.conditioningDim.toLong())).also { sharedStart += it } }

    private fun floats(data: FloatArray, shape: LongArray): OnnxTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape)

    private fun initialState(manifest: List<StateEntry>): Map<String, OnnxTensor> = manifest.associate { e ->
        val n = e.elements.toInt()
        e.inputName to when (e.dtype) {
            "float32" -> floats(FloatArray(n) { when (e.fill) { "nan" -> Float.NaN; "ones" -> 1f; else -> 0f } }, e.shape)
            "int64" -> OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(n) { if (e.fill == "ones") 1L else 0L }), e.shape)
            "bool" -> OnnxTensor.createTensor(env, ByteBuffer.wrap(ByteArray(n) { if (e.fill == "ones") 1 else 0 }), e.shape, OnnxJavaType.BOOL)
            else -> error("Unsupported state type ${e.dtype}")
        }
    }

    /** Carries a model's state tensors from one run to the next, freeing each run's outputs once they are replaced. */
    private class StateChain(private val manifest: List<StateEntry>, start: Map<String, OnnxTensor>, private var ownedResult: OrtSession.Result? = null) {
        var tensors: Map<String, OnnxTensor> = start
            private set

        fun advance(result: OrtSession.Result, offset: Int) {
            val next = manifest.associate { it.inputName to (result.get(offset + it.index) as OnnxTensor) }
            ownedResult?.close()
            ownedResult = result
            tensors = next
        }

        /** Hands over the memory (and who must free it) after the last run. */
        fun detach(): Pair<Map<String, OnnxTensor>, OrtSession.Result?> = (tensors to ownedResult).also { ownedResult = null }

        fun close() { ownedResult?.close(); ownedResult = null }
    }

    private class OrtVoice(val tensors: Map<String, OnnxTensor>, private val owner: OrtSession.Result?) : PocketVoice {
        override fun close() { owner?.close() }
    }

    override fun encodeVoice(samples24k: FloatArray): FloatArray {
        floats(samples24k, longArrayOf(1, 1, samples24k.size.toLong())).use { audio ->
            mimiEncoder.run(mapOf("audio" to audio)).use { r ->
                val t = r.get(0) as OnnxTensor
                val out = FloatArray(t.floatBuffer.remaining())
                t.floatBuffer.get(out)
                return out
            }
        }
    }

    override fun prepareVoice(embeddings: FloatArray): PocketVoice {
        val frames = embeddings.size / bundle.conditioningDim
        val start = initialState(bundle.flowState)
        try {
            floats(embeddings, longArrayOf(1, frames.toLong(), bundle.conditioningDim.toLong())).use { text ->
                val result = flowMain.run(mapOf("sequence" to emptySequence, "text_embeddings" to text) + start)
                val chain = StateChain(bundle.flowState, start)
                chain.advance(result, 2)
                val (tensors, owner) = chain.detach()
                return OrtVoice(tensors, owner)
            }
        } finally {
            start.values.forEach { it.close() }
        }
    }

    override fun newSentence(voice: PocketVoice, tokenIds: LongArray): PocketSentence {
        voice as OrtVoice
        val ids = OnnxTensor.createTensor(env, LongBuffer.wrap(tokenIds), longArrayOf(1, tokenIds.size.toLong()))
        val chain = StateChain(bundle.flowState, voice.tensors)
        var textEmbeddings: OnnxTensor? = null
        try {
            textConditioner.run(mapOf("token_ids" to ids)).use { r ->
                val t = r.get(0) as OnnxTensor
                val data = FloatArray(t.floatBuffer.remaining()).also { t.floatBuffer.get(it) }
                textEmbeddings = floats(data, longArrayOf(1, (data.size / bundle.conditioningDim).toLong(), bundle.conditioningDim.toLong()))
            }
            chain.advance(flowMain.run(mapOf("sequence" to emptySequence, "text_embeddings" to textEmbeddings!!) + chain.tensors), 2)
        } catch (e: Throwable) {
            chain.close(); throw e
        } finally {
            ids.close(); textEmbeddings?.close()
        }
        return object : PocketSentence {
            override fun step(previousLatent: FloatArray?): PocketSentence.Step {
                val seq = floats(previousLatent ?: FloatArray(bundle.latentDim) { Float.NaN }, longArrayOf(1, 1, bundle.latentDim.toLong()))
                try {
                    val result = flowMain.run(mapOf("sequence" to seq, "text_embeddings" to emptyText) + chain.tensors)
                    val c = result.get(0) as OnnxTensor
                    val conditioning = FloatArray(c.floatBuffer.remaining()).also { c.floatBuffer.get(it) }
                    val e = result.get(1) as OnnxTensor
                    val eos = e.floatBuffer.get(0)
                    chain.advance(result, 2)
                    return PocketSentence.Step(conditioning, eos)
                } finally { seq.close() }
            }

            override fun close() { chain.close() }
        }
    }

    override fun flow(conditioning: FloatArray, s: Float, t: Float, x: FloatArray): FloatArray {
        val c = floats(conditioning, longArrayOf(1, conditioning.size.toLong()))
        val sT = floats(floatArrayOf(s), longArrayOf(1, 1))
        val tT = floats(floatArrayOf(t), longArrayOf(1, 1))
        val xT = floats(x, longArrayOf(1, x.size.toLong()))
        try {
            flowNet.run(mapOf("c" to c, "s" to sT, "t" to tT, "x" to xT)).use { r ->
                val o = r.get(0) as OnnxTensor
                return FloatArray(o.floatBuffer.remaining()).also { o.floatBuffer.get(it) }
            }
        } finally { c.close(); sT.close(); tT.close(); xT.close() }
    }

    override fun newDecoder(): PocketDecoder {
        val chain = StateChain(bundle.mimiState, mimiStart)
        return object : PocketDecoder {
            override fun decode(latents: List<FloatArray>): FloatArray {
                val flat = FloatArray(latents.size * bundle.latentDim)
                latents.forEachIndexed { i, l -> l.copyInto(flat, i * bundle.latentDim) }
                val input = floats(flat, longArrayOf(1, latents.size.toLong(), bundle.latentDim.toLong()))
                try {
                    val result = mimiDecoder.run(mapOf("latent" to input) + chain.tensors)
                    val a = result.get(0) as OnnxTensor
                    val audio = FloatArray(a.floatBuffer.remaining()).also { a.floatBuffer.get(it) }
                    chain.advance(result, 1)
                    return audio
                } finally { input.close() }
            }

            override fun close() { chain.close() }
        }
    }

    override fun close() {
        listOf(mimiEncoder, textConditioner, flowMain, flowNet, mimiDecoder).forEach { runCatching { it.close() } }
        sharedStart.forEach { it.close() }
        runCatching { options.close() }
    }
}
