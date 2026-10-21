package com.mundymorning.touchreader.tts

import android.content.Context
import com.mundymorning.touchreader.core.audio.BufferedSpeechEngine
import com.mundymorning.touchreader.core.voicestudio.VoiceProfile
import com.mundymorning.touchreader.studio.VoiceLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import com.mundymorning.touchreader.core.tts.SpeechEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Offline neural narrator (Supertonic 3, ONNX Runtime). The model loads on a background thread; anything
 * that goes wrong (missing files, out of memory) becomes [EngineStatus.Failed] instead of a crash, and the
 * controller then falls back to the system voice.
 */
class SupertonicAppEngine(
    @Suppress("UNUSED_PARAMETER") context: Context,
    private val packDir: File,
    private val steps: Int,
    /** Folder of the installed voice-cloning add-on, or null if it is not installed. */
    private val converterDir: File? = null,
    private val library: VoiceLibrary? = null,
) : AppEngine {
    private val _status = MutableStateFlow<EngineStatus>(EngineStatus.Initializing)
    override val status: StateFlow<EngineStatus> = _status.asStateFlow()

    private val styleNames: List<String> =
        File(packDir, "voice_styles").listFiles { f -> f.extension == "json" }.orEmpty().map { it.nameWithoutExtension }.sorted()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val presetVoices = styleNames.map { VoiceInfo(it, label(it), "und", "", needsNetwork = false, installed = true) }

    private fun customVoices(ps: List<VoiceProfile>) =
        ps.map { VoiceInfo(Engines.CUSTOM_PREFIX + it.id, "★ ${it.name} (your voice)", "und", "", needsNetwork = false, installed = it.hasSample || it.engineData.isNotEmpty()) }

    /** Built-in voices, plus the user's own voices when the cloning add-on is installed. */
    override val voices: StateFlow<List<VoiceInfo>> =
        if (converterDir != null && library != null) library.profiles.map { presetVoices + customVoices(it) }
            .stateIn(scope, SharingStarted.Eagerly, presetVoices + customVoices(library.profiles.value))
        else MutableStateFlow(presetVoices).asStateFlow()
    override val engines: StateFlow<List<TtsEngineInfo>> = MutableStateFlow(emptyList<TtsEngineInfo>()).asStateFlow()
    override val defaultEnginePackage: String? = null

    private val _activity = MutableStateFlow("Loading the neural voice...")
    override val activity: StateFlow<String> = _activity.asStateFlow()

    /** True if [name] is a voice this engine can use (a built-in preset, or a saved voice that still exists). */
    fun hasVoice(name: String): Boolean = voices.value.any { it.name == name }

    override var listener: SpeechEngine.Listener? = null

    /** Called (from a worker thread) when synthesis cannot keep up with real time. */
    @Volatile var onSlow: ((Double) -> Unit)? = null

    /** Called once (from a worker thread) with how many seconds of work one second of speech takes on this phone. */
    @Volatile var onSpeedKnown: ((Double) -> Unit)? = null

    private val forwarder = object : SpeechEngine.Listener {
        override fun onStart(id: Int) { listener?.onStart(id) }
        override fun onDone(id: Int) { listener?.onDone(id) }
        override fun onError(id: Int, message: String) { listener?.onError(id, message) }
    }

    private val lock = Any()
    private val pending = ArrayList<Pair<Int, String>>()
    private var buffered: BufferedSpeechEngine? = null
    private var voice: NeuralVoice? = null
    @Volatile private var requestedVoice: String? = null
    @Volatile private var released = false
    @Volatile private var speed = 1f

    init {
        Thread({ load() }, "tr-supertonic-load").apply { isDaemon = true; start() }
    }

    private fun label(name: String): String {
        val n = name.drop(1)
        return when (name.firstOrNull()) { 'M' -> "Male voice $n"; 'F' -> "Female voice $n"; else -> name }
    }

    private fun load() {
        try {
            val loadStart = System.nanoTime()
            val nv = NeuralVoice.load(packDir, converterDir, library, steps, requestedVoice) { m -> if (!released) _activity.value = m }
            val b = BufferedSpeechEngine(nv.synthesizer, AudioTrackSink())
            b.listener = forwarder
            b.onSlow = { rtf -> onSlow?.invoke(rtf) }
            b.onSpeedKnown = { rtf -> onSpeedKnown?.invoke(rtf) }
            b.setSpeed(speed)
            val queued: List<Pair<Int, String>>
            synchronized(lock) {
                if (released) { b.release(); return }
                voice = nv; buffered = b
                queued = pending.toList(); pending.clear()
                _status.value = EngineStatus.Ready
                _activity.value = "Neural voice loaded in ${"%.1f".format((System.nanoTime() - loadStart) / 1e9)} s" +
                    (if (converterDir != null) " (voice cloning add-on installed)" else "")
            }
            nv.setVoice(requestedVoice) // the narrator may have been changed while the model was loading
            queued.forEach { (id, text) -> b.enqueue(id, text) }
        } catch (t: Throwable) {
            val msg = if (t is OutOfMemoryError) "Not enough memory to load the neural voice." else "The neural voice could not start (${t.javaClass.simpleName})."
            _status.value = EngineStatus.Failed(msg)
            _activity.value = msg
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
        requestedVoice = name
        voice?.setVoice(name)
    }

    /** Times a few sentences with the current narrator; blocks, so call it from a worker thread. */
    override fun measureSpeed(): Double? {
        val b = synchronized(lock) { buffered } ?: return null
        _activity.value = "Checking how fast this phone can make speech..."
        val rtf = b.measure(SPEED_TEST_SENTENCES) { released }
        _activity.value = if (rtf != null) "This phone takes ${"%.1f".format(rtf)} s to make 1 s of speech with this voice" else ""
        return rtf
    }

    override fun preview(name: String, sample: String) {
        setVoice(name)
        stop()
        enqueue(Engines.PREVIEW_ID, sample)
    }

    override fun release() {
        synchronized(lock) { released = true; pending.clear() }
        buffered?.release()
        scope.cancel()
    }

    private companion object {
        /** Typical sentence lengths; the first one only warms the model up. */
        val SPEED_TEST_SENTENCES = listOf(
            "The old lighthouse stood quietly above the harbour while the evening tide came in.",
            "She opened the heavy door and found a letter waiting on the small wooden table.",
            "Nobody in the village could remember when the clock had last told the right time.",
        )
    }
}
