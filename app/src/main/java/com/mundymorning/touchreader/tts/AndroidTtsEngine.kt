package com.mundymorning.touchreader.tts

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.mundymorning.touchreader.core.tts.SpeechEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

data class VoiceInfo(
    val name: String,
    val label: String,
    val localeTag: String,
    val language: String,
    val needsNetwork: Boolean,
    val installed: Boolean,
)

sealed interface EngineStatus {
    data object Initializing : EngineStatus
    data object Ready : EngineStatus
    data class Failed(val message: String) : EngineStatus
}

/** Baseline/fallback engine using whatever speech service is installed on the phone. */
class AndroidTtsEngine(context: Context) : SpeechEngine {
    override var listener: SpeechEngine.Listener? = null

    private val _status = MutableStateFlow<EngineStatus>(EngineStatus.Initializing)
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    private val _voices = MutableStateFlow<List<VoiceInfo>>(emptyList())
    val voices: StateFlow<List<VoiceInfo>> = _voices.asStateFlow()

    private val lock = Any()
    private val pending = ArrayList<Pair<Int, String>>()
    private var tts: TextToSpeech? = null
    private var ready = false
    private var speed = 1f
    private var voiceName: String? = null

    init {
        tts = TextToSpeech(context.applicationContext) { code -> onInit(code) }
    }

    private fun onInit(code: Int) {
        val engine = tts
        if (code != TextToSpeech.SUCCESS || engine == null) {
            _status.value = EngineStatus.Failed("Android text-to-speech isn't available on this device. Install a speech engine in system settings.")
            failPending()
            return
        }
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        )
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { utteranceId?.toIntOrNull()?.let { listener?.onStart(it) } }
            override fun onDone(utteranceId: String?) { utteranceId?.toIntOrNull()?.let { listener?.onDone(it) } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { utteranceId?.toIntOrNull()?.let { listener?.onError(it, "The speech engine reported an error.") } }
            override fun onError(utteranceId: String?, errorCode: Int) {
                utteranceId?.toIntOrNull()?.let { listener?.onError(it, "The speech engine reported error $errorCode.") }
            }
        })
        refreshVoices(engine)
        synchronized(lock) {
            ready = true
            engine.setSpeechRate(speed)
            applyVoice(engine)
            _status.value = EngineStatus.Ready
            val queued = pending.toList(); pending.clear()
            queued.forEach { (id, text) -> speakNow(engine, id, text) }
        }
    }

    private fun failPending() {
        val queued = synchronized(lock) { pending.toList().also { pending.clear() } }
        queued.forEach { (id, _) -> listener?.onError(id, "Text-to-speech is unavailable.") }
    }

    private fun refreshVoices(engine: TextToSpeech) {
        val list = try { engine.voices.orEmpty() } catch (e: Exception) { emptySet() }
        _voices.value = list.map { v ->
            val installed = !v.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
            VoiceInfo(
                name = v.name,
                label = prettyLabel(v),
                localeTag = v.locale.toLanguageTag(),
                language = v.locale.language,
                needsNetwork = v.isNetworkConnectionRequired,
                installed = installed,
            )
        }.sortedWith(compareBy({ !it.installed }, { it.needsNetwork }, { it.label }))
    }

    private fun prettyLabel(v: Voice): String {
        val loc = v.locale.getDisplayName(Locale.getDefault())
        return "$loc · ${v.name.substringAfterLast('#').substringAfterLast('-').ifEmpty { v.name }}".take(60)
    }

    private fun applyVoice(engine: TextToSpeech) {
        val name = voiceName
        val voice = if (name != null) try { engine.voices?.firstOrNull { it.name == name } } catch (e: Exception) { null } else null
        if (voice != null) engine.voice = voice else if (name == null) {
            val def = Locale.getDefault()
            if (engine.isLanguageAvailable(def) >= TextToSpeech.LANG_AVAILABLE) engine.language = def
        }
    }

    /** Select a voice by name; null restores the system default for the device language. */
    fun setVoice(name: String?) {
        synchronized(lock) {
            voiceName = name
            val engine = tts
            if (ready && engine != null) applyVoice(engine)
        }
    }

    /** Speak a short sample with [name] (stops anything currently queued). */
    fun preview(name: String, sample: String) {
        synchronized(lock) {
            val engine = tts ?: return
            if (!ready) return
            engine.stop()
            voiceName = name
            applyVoice(engine)
            speakNow(engine, PREVIEW_ID, sample)
        }
    }

    override fun enqueue(id: Int, text: String) {
        synchronized(lock) {
            val engine = tts
            if (_status.value is EngineStatus.Failed || engine == null) {
                listener?.onError(id, "Text-to-speech is unavailable."); return
            }
            if (!ready) { pending += id to text; return }
            speakNow(engine, id, text)
        }
    }

    private fun speakNow(engine: TextToSpeech, id: Int, text: String) {
        val r = engine.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), id.toString())
        if (r == TextToSpeech.ERROR) listener?.onError(id, "The speech engine rejected the text.")
    }

    override fun stop() {
        synchronized(lock) {
            pending.clear()
            tts?.stop()
        }
    }

    override fun setSpeed(speed: Float) {
        synchronized(lock) {
            this.speed = speed
            if (ready) tts?.setSpeechRate(speed)
        }
    }

    override fun release() {
        synchronized(lock) { tts?.stop(); tts?.shutdown(); tts = null; ready = false }
    }

    companion object { const val PREVIEW_ID = -1 }
}
