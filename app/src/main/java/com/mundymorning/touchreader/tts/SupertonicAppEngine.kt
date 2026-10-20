package com.mundymorning.touchreader.tts

import android.content.Context
import com.mundymorning.touchreader.core.audio.BufferedSpeechEngine
import com.mundymorning.touchreader.core.supertonic.SupertonicLanguages
import com.mundymorning.touchreader.core.voiceconvert.BaseVoice
import com.mundymorning.touchreader.core.voiceconvert.ConverterConfig
import com.mundymorning.touchreader.core.voiceconvert.ConvertingSynthesizer
import com.mundymorning.touchreader.core.voiceconvert.EmbeddingCodec
import com.mundymorning.touchreader.core.voiceconvert.LazyToneConverter
import com.mundymorning.touchreader.core.voiceconvert.OnnxToneConverter
import com.mundymorning.touchreader.core.voicestudio.Dsp
import com.mundymorning.touchreader.core.voicestudio.VoiceProfile
import com.mundymorning.touchreader.core.voicestudio.WavIo
import com.mundymorning.touchreader.studio.VoiceLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    private var synth: ConvertingSynthesizer? = null
    private var converter: LazyToneConverter? = null
    @Volatile private var customId: String? = null
    @Volatile private var released = false
    @Volatile private var speed = 1f
    private val defaultPreset: String = if ("M1" in styleNames) "M1" else styleNames.firstOrNull() ?: "M1"
    @Volatile private var voiceName: String = defaultPreset

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
            val raw = SupertonicSynthesizer(model, loadStyle(defaultPreset), lang, steps)
            val base = object : BaseVoice {
                override val sampleRate = raw.sampleRate
                override fun usePreset(name: String) { raw.style = loadStyle(name) }
                override fun synthesizeFloat(text: String, speed: Float, isCancelled: () -> Boolean) = raw.synthesizeFloat(text, speed, isCancelled)
                override fun release() = raw.release()
            }
            val dir = converterDir
            val conv = dir?.let { d ->
                val cfg = ConverterConfig.parse(File(d, "converter_config.json").readText())
                LazyToneConverter(cfg.sampleRate) { OnnxToneConverter.load(d, threads) }
            }
            val s = ConvertingSynthesizer(base, conv, dir?.let { OnnxToneConverter.loadSourceEmbeddings(it) } ?: emptyMap())
            val pendingCustom = customId
            if (pendingCustom != null) s.useCustom { fingerprintFor(pendingCustom) } else s.usePreset(voiceName)
            val b = BufferedSpeechEngine(s, AudioTrackSink())
            b.listener = forwarder
            b.onSlow = { rtf -> onSlow?.invoke(rtf) }
            b.setSpeed(speed)
            val queued: List<Pair<Int, String>>
            synchronized(lock) {
                if (released) { b.release(); return }
                synth = s; buffered = b; converter = conv
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

    /** Fingerprint of a saved voice: cached with the profile, otherwise computed once from its recording (on the synthesis thread). */
    private fun fingerprintFor(id: String): FloatArray? {
        val lib = library ?: return null
        lib.engineData(id, CLONE_ENGINE, CLONE_VERSION)?.let { bytes ->
            runCatching { EmbeddingCodec.decode(bytes) }.getOrNull()?.takeIf { it.size == FINGERPRINT_SIZE }?.let { return it }
        }
        val file = lib.sampleFile(id) ?: return null
        val conv = converter ?: return null
        val pcm = WavIo.read(file.readBytes())
        val fp = conv.embed(Dsp.resample(pcm, conv.sampleRate).samples)
        lib.saveEngineData(id, CLONE_ENGINE, CLONE_VERSION, EmbeddingCodec.encode(fp))
        return fp
    }

    override fun setVoice(name: String?) {
        val id = name?.takeIf { it.startsWith(Engines.CUSTOM_PREFIX) }?.removePrefix(Engines.CUSTOM_PREFIX)
        if (id != null && converterDir != null && library?.profiles?.value?.any { it.id == id } == true) {
            customId = id
            synth?.useCustom { fingerprintFor(id) }
            return
        }
        // a built-in voice, or a custom voice that no longer exists / cannot be used: fall back to a built-in one
        customId = null
        val target = name?.takeIf { it in styleNames } ?: defaultPreset
        voiceName = target
        synth?.let { runCatching { it.usePreset(target) } }
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
        const val CLONE_ENGINE = "openvoice-v2-converter"
        const val CLONE_VERSION = "1"
        const val FINGERPRINT_SIZE = 256
    }
}
