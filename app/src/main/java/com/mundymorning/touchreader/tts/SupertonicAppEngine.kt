package com.mundymorning.touchreader.tts

import android.content.Context
import com.mundymorning.touchreader.core.audio.BufferedSpeechEngine
import com.mundymorning.touchreader.core.audio.Synthesizer
import com.mundymorning.touchreader.core.export.ExportPlanner
import com.mundymorning.touchreader.core.tts.SpeechEngine
import com.mundymorning.touchreader.core.voicestudio.VoiceProfile
import com.mundymorning.touchreader.studio.VoiceLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.File

/**
 * Offline neural narrator (ONNX Runtime). Built-in voices are spoken by Supertonic 3; the user's own saved voices by
 * Pocket TTS (which copies the voice from the saved recording). Only one of the two models is in memory at a time:
 * changing between a built-in and a saved voice loads the other (a few seconds). Anything that goes wrong (missing
 * files, out of memory) becomes [EngineStatus.Failed] instead of a crash, and the controller then falls back to the
 * system voice.
 */
class SupertonicAppEngine(
    @Suppress("UNUSED_PARAMETER") context: Context,
    /** Folder of the built-in neural voice, or null if it is not installed. */
    private val packDir: File?,
    /** Folder of the voice-cloning download (Pocket TTS), or null if it is not installed. */
    private val pocketDir: File?,
    private val steps: Int,
    private val library: VoiceLibrary? = null,
    initialVoice: String? = null,
) : AppEngine {
    private enum class Kind { BUILT_IN, SAVED }

    /** What the loaded model needs from the engine. */
    private interface Backend {
        val synthesizer: Synthesizer
        fun setVoice(name: String?)
    }

    private val _status = MutableStateFlow<EngineStatus>(EngineStatus.Initializing)
    override val status: StateFlow<EngineStatus> = _status.asStateFlow()

    private val styleNames: List<String> = packDir?.let { NeuralVoice.presetNames(it) }.orEmpty()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val presetVoices = styleNames.map { VoiceInfo(it, label(it), "und", "", needsNetwork = false, installed = true) }

    private fun customVoices(ps: List<VoiceProfile>) =
        ps.map { VoiceInfo(Engines.CUSTOM_PREFIX + it.id, "★ ${it.name} (your voice)", "und", "", needsNetwork = false, installed = it.hasSample || it.engineData.isNotEmpty()) }

    /** Built-in voices, plus the user's own voices when the voice-cloning download is installed. */
    override val voices: StateFlow<List<VoiceInfo>> =
        if (pocketDir != null && library != null) library.profiles.map { presetVoices + customVoices(it) }
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
    private val pending = ArrayList<Triple<Int, String, Boolean>>()
    private var buffered: BufferedSpeechEngine? = null
    private var backend: Backend? = null
    private var loadedKind: Kind? = null
    private var loadingKind: Kind? = null
    @Volatile private var requestedVoice: String? = initialVoice
    @Volatile private var released = false
    @Volatile private var speed = 1f

    init {
        val first = kindFor(initialVoice)
        loadingKind = first
        startLoad(first)
    }

    private fun label(name: String): String {
        val n = name.drop(1)
        return when (name.firstOrNull()) { 'M' -> "Male voice $n"; 'F' -> "Female voice $n"; else -> name }
    }

    /** A saved voice needs the voice-cloning download; everything else (and any case where only one model is installed) uses what is there. */
    private fun kindFor(name: String?): Kind {
        val saved = name?.startsWith(Engines.CUSTOM_PREFIX) == true
        return when {
            saved && pocketDir != null -> Kind.SAVED
            packDir != null -> Kind.BUILT_IN
            else -> Kind.SAVED
        }
    }

    private fun startLoad(kind: Kind) {
        Thread({ load(kind) }, "tr-neural-load").apply { isDaemon = true; start() }
    }

    private fun load(kind: Kind) {
        try {
            val loadStart = System.nanoTime()
            _activity.value = if (kind == Kind.SAVED) "Loading your voice..." else "Loading the neural voice..."
            val voiceNow = requestedVoice
            val be: Backend = when (kind) {
                Kind.BUILT_IN -> {
                    val nv = NeuralVoice.load(packDir!!, steps, voiceNow)
                    object : Backend {
                        override val synthesizer: Synthesizer = nv.synthesizer
                        override fun setVoice(name: String?) = nv.setVoice(name)
                    }
                }
                Kind.SAVED -> {
                    val live = PocketLive.load(pocketDir!!, library ?: error("No voice library"), voiceNow)
                    object : Backend {
                        override val synthesizer: Synthesizer = live.synthesizer
                        override fun setVoice(name: String?) = live.setVoice(name)
                    }
                }
            }
            // A saved voice speaks the sentences of a paragraph together, so the story flows instead of every sentence
            // ending on a full stop, with short pauses of its own between them. Built-in voices stay sentence by sentence.
            val b = if (kind == Kind.SAVED) BufferedSpeechEngine(
                be.synthesizer, AudioTrackSink(), maxReady = 4, mergeMaxChars = ExportPlanner.FLOW_MAX_CHARS,
                sentencePauseMs = ExportPlanner.FLOW_SENTENCE_PAUSE_MS, paragraphPauseMs = ExportPlanner.FLOW_PARAGRAPH_PAUSE_MS,
            ) else BufferedSpeechEngine(be.synthesizer, AudioTrackSink())
            b.listener = forwarder
            b.onSlow = { rtf -> onSlow?.invoke(rtf) }
            b.onSpeedKnown = { rtf -> onSpeedKnown?.invoke(rtf) }
            b.setSpeed(speed)
            val queued: List<Triple<Int, String, Boolean>>
            var reload: Kind? = null
            synchronized(lock) {
                if (released) { b.release(); return }
                val wanted = kindFor(requestedVoice)
                if (wanted != kind) {
                    // the narrator was changed to the other kind while this one was loading
                    reload = wanted; loadingKind = wanted
                    queued = emptyList()
                } else {
                    backend = be; buffered = b; loadedKind = kind; loadingKind = null
                    queued = pending.toList(); pending.clear()
                    _status.value = EngineStatus.Ready
                    _activity.value = (if (kind == Kind.SAVED) "Your voice" else "Neural voice") + " loaded in ${"%.1f".format((System.nanoTime() - loadStart) / 1e9)} s"
                }
            }
            reload?.let { b.release(); startLoad(it); return }
            be.setVoice(requestedVoice) // the narrator may have been changed while the model was loading
            queued.forEach { (id, text, end) -> b.enqueue(id, text, end) }
        } catch (t: Throwable) {
            val msg = if (t is OutOfMemoryError) "Not enough memory to load the neural voice." else "The neural voice could not start (${t.javaClass.simpleName})."
            synchronized(lock) { loadingKind = null }
            _status.value = EngineStatus.Failed(msg)
            _activity.value = msg
            val queued = synchronized(lock) { pending.toList().also { pending.clear() } }
            queued.forEach { (id, _, _) -> listener?.onError(id, msg) }
        }
    }

    override fun enqueue(id: Int, text: String) = enqueue(id, text, true)

    override fun enqueue(id: Int, text: String, paragraphEnd: Boolean) {
        val b: BufferedSpeechEngine?
        synchronized(lock) {
            if (_status.value is EngineStatus.Failed) { listener?.onError(id, (_status.value as EngineStatus.Failed).message); return }
            b = buffered
            if (b == null) { pending += Triple(id, text, paragraphEnd); return }
        }
        b?.enqueue(id, text, paragraphEnd)
    }

    override fun stop() {
        synchronized(lock) { pending.clear() }
        buffered?.stop()
    }

    override fun setSpeed(speed: Float) { this.speed = speed; buffered?.setSpeed(speed) }

    override fun setVoice(name: String?) {
        requestedVoice = name
        val want = kindFor(name)
        var drop: BufferedSpeechEngine? = null
        var load = false
        synchronized(lock) {
            if (released) return
            if (loadedKind != null && loadedKind != want) {
                // the other model is needed: free this one first (two do not fit in memory on small phones)
                drop = buffered; buffered = null; backend = null; loadedKind = null
                loadingKind = want; load = true
                _status.value = EngineStatus.Initializing
                _activity.value = if (want == Kind.SAVED) "Loading your voice..." else "Loading the neural voice..."
            }
        }
        drop?.release()
        if (load) startLoad(want) else backend?.setVoice(name)
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
        val b: BufferedSpeechEngine?
        synchronized(lock) { released = true; pending.clear(); b = buffered; buffered = null }
        b?.release()
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
