package com.mundymorning.touchreader.tts

import android.content.Context
import com.mundymorning.touchreader.core.audio.BufferedSpeechEngine
import com.mundymorning.touchreader.core.supertonic.SupertonicLanguages
import com.mundymorning.touchreader.core.supertonic.SupertonicModel
import com.mundymorning.touchreader.core.supertonic.SupertonicSynthesizer
import com.mundymorning.touchreader.core.supertonic.VoiceStyle
import com.mundymorning.touchreader.core.tts.SpeechEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Locale

/**
 * Offline neural narrator (Supertonic 3, ONNX Runtime). The model loads on a background thread; anything
 * that goes wrong (missing files, out of memory) becomes [EngineStatus.Failed] instead of a crash, and the
 * controller then falls back to the system voice.
 */
class SupertonicAppEngine(
    @Suppress("UNUSED_PARAMETER") context: Context,
    private val packDir: File,
    private val steps: Int,
) : AppEngine {
    private val _status = MutableStateFlow<EngineStatus>(EngineStatus.Initializing)
    override val status: StateFlow<EngineStatus> = _status.asStateFlow()

    private val styleNames: List<String> =
        File(packDir, "voice_styles").listFiles { f -> f.extension == "json" }.orEmpty().map { it.nameWithoutExtension }.sorted()

    private val _voices = MutableStateFlow(styleNames.map { VoiceInfo(it, label(it), "und", "", needsNetwork = false, installed = true) })
    override val voices: StateFlow<List<VoiceInfo>> = _voices.asStateFlow()
    override val engines: StateFlow<List<TtsEngineInfo>> = MutableStateFlow(emptyList<TtsEngineInfo>()).asStateFlow()
    override val defaultEnginePackage: String? = null

    override var listener: SpeechEngine.Listener? = null

    /** Called (from a worker thread) when synthesis cannot keep up with real time. */
    @Volatile var onSlow: ((Double) -> Unit)? = null

    private val forwarder = object : SpeechEngine.Listener {
        override fun onStart(id: Int) { listener?.onStart(id) }
        override fun onDone(id: Int) { listener?.onDone(id) }
        override fun onError(id: Int, message: String) { listener?.onError(id, message) }
    }

    private val lock = Any()
    private val pending = ArrayList<Pair<Int, String>>()
    private var buffered: BufferedSpeechEngine? = null
    private var synth: SupertonicSynthesizer? = null
    @Volatile private var released = false
    @Volatile private var speed = 1f
    @Volatile private var voiceName: String = if ("M1" in styleNames) "M1" else styleNames.firstOrNull() ?: "M1"

    init {
        Thread({ load() }, "tr-supertonic-load").apply { isDaemon = true; start() }
    }

    private fun label(name: String): String {
        val n = name.drop(1)
        return when (name.firstOrNull()) { 'M' -> "Male voice $n"; 'F' -> "Female voice $n"; else -> name }
    }

    private fun loadStyle(name: String) = VoiceStyle.parse(File(packDir, "voice_styles/$name.json").readText())

    private fun load() {
        try {
            val lang = Locale.getDefault().language.takeIf { SupertonicLanguages.isValid(it) } ?: "na"
            val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
            val model = SupertonicModel.load(File(packDir, "onnx"), threads)
            val s = SupertonicSynthesizer(model, loadStyle(voiceName), lang, steps)
            val b = BufferedSpeechEngine(s, AudioTrackSink())
            b.listener = forwarder
            b.onSlow = { rtf -> onSlow?.invoke(rtf) }
            b.setSpeed(speed)
            val queued: List<Pair<Int, String>>
            synchronized(lock) {
                if (released) { b.release(); return }
                synth = s; buffered = b
                queued = pending.toList(); pending.clear()
                _status.value = EngineStatus.Ready
            }
            queued.forEach { (id, text) -> b.enqueue(id, text) }
        } catch (t: Throwable) {
            val msg = if (t is OutOfMemoryError) "Not enough memory to load the neural voice." else "The neural voice could not start (${t.javaClass.simpleName})."
            _status.value = EngineStatus.Failed(msg)
            val queued = synchronized(lock) { pending.toList().also { pending.clear() } }
            queued.forEach { (id, _) -> listener?.onError(id, msg) }
        }
    }

    override fun enqueue(id: Int, text: String) {
        val b: BufferedSpeechEngine?
        synchronized(lock) {
            if (_status.value is EngineStatus.Failed) { listener?.onError(id, (_status.value as EngineStatus.Failed).message); return }
            b = buffered
            if (b == null) { pending += id to text; return }
        }
        b?.enqueue(id, text)
    }

    override fun stop() {
        synchronized(lock) { pending.clear() }
        buffered?.stop()
    }

    override fun setSpeed(speed: Float) { this.speed = speed; buffered?.setSpeed(speed) }

    override fun setVoice(name: String?) {
        val target = name?.takeIf { it in styleNames } ?: (if ("M1" in styleNames) "M1" else styleNames.firstOrNull()) ?: return
        voiceName = target
        synth?.let { runCatching { it.style = loadStyle(target) } }
    }

    override fun preview(name: String, sample: String) {
        setVoice(name)
        stop()
        enqueue(Engines.PREVIEW_ID, sample)
    }

    override fun release() {
        synchronized(lock) { released = true; pending.clear() }
        buffered?.release()
    }
}
