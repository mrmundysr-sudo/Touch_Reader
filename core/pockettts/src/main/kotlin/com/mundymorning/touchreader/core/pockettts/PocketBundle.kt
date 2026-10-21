package com.mundymorning.touchreader.core.pockettts

import org.json.JSONObject

/** One tensor the model carries from one step to the next (the transformer's memory, the decoder's buffers). */
class StateEntry(val inputName: String, val dtype: String, val fill: String, val shape: LongArray, val index: Int) {
    val elements: Long get() = shape.fold(1L) { a, b -> a * b }
}

/** What the exported model says about itself (`bundle.json`). */
class PocketBundle(
    val sampleRate: Int,
    val frameRate: Double,
    val samplesPerFrame: Int,
    val latentDim: Int,
    val conditioningDim: Int,
    val maxTokensPerChunk: Int,
    val insertBosBeforeVoice: Boolean,
    val padWithSpacesForShortInputs: Boolean,
    val removeSemicolons: Boolean,
    val recommendedFramesAfterEos: Int?,
    val tokenizerFile: String,
    val bosBeforeVoiceFile: String?,
    val flowState: List<StateEntry>,
    val mimiState: List<StateEntry>,
) {
    companion object {
        fun parse(json: String): PocketBundle {
            val o = JSONObject(json)
            fun states(name: String): List<StateEntry> {
                val a = o.getJSONArray(name)
                return List(a.length()) { i ->
                    val e = a.getJSONObject(i)
                    val s = e.getJSONArray("shape")
                    StateEntry(e.getString("input_name"), e.getString("dtype"), e.getString("fill"), LongArray(s.length()) { s.getLong(it) }, e.getInt("index"))
                }
            }
            return PocketBundle(
                sampleRate = o.getInt("sample_rate"),
                frameRate = o.getDouble("frame_rate"),
                samplesPerFrame = o.getInt("samples_per_frame"),
                latentDim = o.getInt("latent_dim"),
                conditioningDim = o.getInt("conditioning_dim"),
                maxTokensPerChunk = o.optInt("max_token_per_chunk", 50),
                insertBosBeforeVoice = o.optBoolean("insert_bos_before_voice", false),
                padWithSpacesForShortInputs = o.optBoolean("pad_with_spaces_for_short_inputs", false),
                removeSemicolons = o.optBoolean("remove_semicolons", false),
                recommendedFramesAfterEos = if (o.isNull("model_recommended_frames_after_eos") || !o.has("model_recommended_frames_after_eos")) null else o.getInt("model_recommended_frames_after_eos"),
                tokenizerFile = o.getString("tokenizer_file"),
                bosBeforeVoiceFile = if (o.has("bos_before_voice_file") && !o.isNull("bos_before_voice_file")) o.getString("bos_before_voice_file") else null,
                flowState = states("flow_lm_state_manifest"),
                mimiState = states("mimi_state_manifest"),
            )
        }
    }
}
