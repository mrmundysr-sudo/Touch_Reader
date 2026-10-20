package com.mundymorning.touchreader.tts

import com.mundymorning.touchreader.core.tts.SpeechEngine
import kotlinx.coroutines.flow.StateFlow

/** What the app needs from any narrator backend (Android system TTS, Supertonic, future engines). */
interface AppEngine : SpeechEngine {
    val status: StateFlow<EngineStatus>
    val voices: StateFlow<List<VoiceInfo>>
    /** Installed Android speech engines; empty for engines that are not Android TTS services. */
    val engines: StateFlow<List<TtsEngineInfo>>
    val defaultEnginePackage: String?
    fun setVoice(name: String?)
    fun preview(name: String, sample: String)
}

/** Identifiers for the non-system engine choice stored in settings. */
object Engines {
    const val NEURAL = "supertonic"
    const val NEURAL_PACK_ID = "supertonic-3"
    const val PREVIEW_ID = -1
}
