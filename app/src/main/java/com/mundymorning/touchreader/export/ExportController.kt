package com.mundymorning.touchreader.export

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import com.mundymorning.touchreader.core.export.ExportEngine
import com.mundymorning.touchreader.core.export.ExportEstimator
import com.mundymorning.touchreader.core.export.ExportManifest
import com.mundymorning.touchreader.core.export.ExportPlanner
import com.mundymorning.touchreader.core.export.ExportProgress
import com.mundymorning.touchreader.core.export.ExportSettings
import com.mundymorning.touchreader.core.export.ExportStore
import com.mundymorning.touchreader.core.export.RunResult
import com.mundymorning.touchreader.core.reader.ReaderBook
import com.mundymorning.touchreader.data.DocumentStore
import com.mundymorning.touchreader.data.Settings
import com.mundymorning.touchreader.playback.PlaybackController
import com.mundymorning.touchreader.studio.VoiceLibrary
import com.mundymorning.touchreader.tts.Engines
import com.mundymorning.touchreader.tts.NeuralVoice
import com.mundymorning.touchreader.voices.VoicePackManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

enum class ExportPhase { IDLE, LOADING, RENDERING, ASSEMBLING, STOPPED, DONE, FAILED, TESTING }

data class ExportStatus(
    val docId: Long? = null,
    val title: String = "",
    val phase: ExportPhase = ExportPhase.IDLE,
    val progress: ExportProgress? = null,
    val message: String? = null,
    val file: File? = null,
) {
    val running get() = phase == ExportPhase.LOADING || phase == ExportPhase.RENDERING || phase == ExportPhase.ASSEMBLING || phase == ExportPhase.TESTING
}

/**
 * Owns the audiobook that is being rendered. The work runs in [ExportService] (so it survives the screen
 * turning off); this class holds the state both the service and the Export screen look at.
 */
class ExportController(
    private val app: Application,
    private val docs: DocumentStore,
    private val settings: Settings,
    private val packs: VoicePackManager,
    private val library: VoiceLibrary,
    private val playback: PlaybackController,
) {
    private val _status = MutableStateFlow(ExportStatus())
    val status: StateFlow<ExportStatus> = _status.asStateFlow()

    @Volatile private var stopRequested = false
    @Volatile private var stopReason: String? = null
    @Volatile private var pending: Pair<Long, ExportSettings>? = null
    private val books = HashMap<Long, ReaderBook>()

    private fun root(docId: Long) = File(app.filesDir, "audiobooks/$docId")
    /** Read-only view that is safe to open while a render runs; [forWriting] (the renderer itself) also clears crash leftovers. */
    fun storeFor(docId: Long, forWriting: Boolean = false) = ExportStore(root(docId), cleanTemp = forWriting)

    val neuralInstalled: Boolean get() = packs.installedDir(Engines.NEURAL_PACK_ID) != null
    val converterInstalled: Boolean get() = packs.installedDir(Engines.CONVERTER_PACK_ID) != null

    suspend fun loadBook(docId: Long): ReaderBook? = withContext(Dispatchers.IO) {
        synchronized(books) { books[docId] }?.let { return@withContext it }
        runCatching { ReaderBook.from(docs.load(docId)) }.getOrNull()?.also { synchronized(books) { books[docId] = it } }
    }

    /** Remembers what to render and starts the background service. [restart] throws away audio rendered earlier. */
    suspend fun begin(docId: Long, title: String, s: ExportSettings, restart: Boolean): String? {
        if (_status.value.running) return "An audiobook is already being made. Stop it first."
        val book = loadBook(docId) ?: return "This document could not be opened."
        val message = withContext(Dispatchers.IO) {
            val fresh = storeFor(docId, forWriting = true)
            if (restart) fresh.deleteAll()
            val old = fresh.readManifest()
            val fingerprint = ExportStore.fingerprint(book)
            if (old != null && old.fingerprint != fingerprint) return@withContext "This document changed since the audio was made. Start over to make it again."
            val use = old?.settings ?: s
            if (old == null) fresh.writeManifest(ExportManifest(docId, title, book.author, fingerprint, use, System.currentTimeMillis()))
            pending = docId to use
            null
        }
        if (message != null) return message
        _status.value = ExportStatus(docId, title, ExportPhase.LOADING, message = "Starting...")
        ContextCompat.startForegroundService(app, Intent(app, ExportService::class.java).setAction(ExportService.ACTION_RUN))
        return null
    }

    fun requestStop() { stopReason = "Stopped. Everything finished so far is kept."; stopRequested = true }

    fun deleteAudio(docId: Long) {
        if (_status.value.running && _status.value.docId == docId) return
        storeFor(docId).deleteAll()
        if (_status.value.docId == docId) _status.value = ExportStatus()
    }

    /** Called by the service on a worker thread. Blocks until the run ends. */
    fun runPending(onUpdate: (ExportStatus) -> Unit) {
        val (docId, s) = pending ?: return
        pending = null
        stopRequested = false
        stopReason = null
        val title = _status.value.title
        fun publish(st: ExportStatus) { _status.value = st; onUpdate(st) }
        publish(ExportStatus(docId, title, ExportPhase.LOADING, message = "Loading the voice. This can take a minute..."))
        var voice: NeuralVoice? = null
        playback.neuralBlocked = true
        try {
            val book = runBlocking { loadBook(docId) } ?: return publish(ExportStatus(docId, title, ExportPhase.FAILED, message = "This document could not be opened."))
            val store = storeFor(docId, forWriting = true)
            val plan = ExportPlanner.plan(book, s)
            if (plan.isEmpty()) return publish(ExportStatus(docId, title, ExportPhase.FAILED, message = "Nothing to render: the chosen chapters have no text."))
            val packDir = packs.installedDir(Engines.NEURAL_PACK_ID)
                ?: return publish(ExportStatus(docId, title, ExportPhase.FAILED, message = "The neural voice is not installed. Install it in the Voices tab."))
            val custom = s.voice.startsWith(Engines.CUSTOM_PREFIX)
            val engine: ExportEngine
            if (store.pending(plan).isEmpty()) {
                engine = ExportEngine(book, plan, s, store, object : com.mundymorning.touchreader.core.audio.Synthesizer {
                    override val sampleRate = 44100
                    override fun synthesize(text: String, speed: Float, isCancelled: () -> Boolean) = ShortArray(0)
                }, AacEncoder())
            } else {
                runBlocking(Dispatchers.Main.immediate) { playback.releaseForExport() }
                voice = NeuralVoice.load(packDir, if (custom) packs.installedDir(Engines.CONVERTER_PACK_ID) else null, library, s.steps, s.voice)
                engine = ExportEngine(book, plan, s, store, voice.synthesizer, AacEncoder())
            }
            val guard = BatteryGuard(app, settings) { stopReason = it }
            var lastRtf: Double? = null
            val result = engine.run({ stopRequested || guard.shouldStop() }) { p ->
                if (p.audioSeconds > 0) lastRtf = p.rtf
                publish(ExportStatus(docId, title, ExportPhase.RENDERING, progress = p))
            }
            lastRtf?.let { settings.recordSpeed(ExportEstimator.Measurement(s.steps, custom, it)) }
            when (result) {
                is RunResult.Completed -> {
                    publish(ExportStatus(docId, title, ExportPhase.ASSEMBLING, message = "Putting the audiobook file together..."))
                    val file = engine.assemble()
                    publish(ExportStatus(docId, title, ExportPhase.DONE, message = "The audiobook is ready.", file = file))
                }
                is RunResult.Stopped -> publish(ExportStatus(docId, title, ExportPhase.STOPPED, message = stopReason ?: "Stopped. Everything finished so far is kept."))
                is RunResult.Failed -> publish(ExportStatus(docId, title, ExportPhase.FAILED, message = result.message))
            }
        } catch (t: Throwable) {
            val msg = if (t is OutOfMemoryError) "Not enough memory to render with this voice. Close other apps and try again."
            else "Something went wrong (${t.javaClass.simpleName}: ${t.message ?: ""}). Everything finished so far is kept."
            publish(ExportStatus(docId, title, ExportPhase.FAILED, message = msg))
        } finally {
            runCatching { voice?.release() }
            runCatching { runBlocking(Dispatchers.Main.immediate) { playback.unblockNeural() } }
        }
    }

    /** Measures how closely a saved voice matches its recording. Returns a readable report (or the reason it could not be done). */
    suspend fun checkVoiceMatch(voiceName: String): String {
        if (_status.value.running) return "Wait for the audiobook that is being made."
        val packDir = packs.installedDir(Engines.NEURAL_PACK_ID) ?: return "Install the neural voice first."
        val convDir = packs.installedDir(Engines.CONVERTER_PACK_ID) ?: return "Install the Voice cloning add-on first."
        _status.value = ExportStatus(phase = ExportPhase.TESTING, message = "Comparing the voice with the recording. This takes a minute or two...")
        playback.neuralBlocked = true
        return try {
            withContext(Dispatchers.Default) {
                withContext(Dispatchers.Main.immediate) { playback.releaseForExport() }
                val v = NeuralVoice.load(packDir, convDir, library, 4, voiceName)
                try {
                    v.synthesizer.synthesize(TEST_SENTENCES[0], 1f) { false } // warm-up; also prepares the voice fingerprint
                    val r = v.checkMatch(voiceName, TEST_SENTENCES[1])
                    if (r == null) "The check could not run for this voice."
                    else buildString {
                        appendLine("Starting from built-in voice ${r.basePreset}.")
                        appendLine("Recording vs that built-in voice: %.2f".format(r.recordingVsBase))
                        appendLine("Built-in voice speaking: %.2f".format(r.baseOutput))
                        r.converted.forEach { (a, c) -> appendLine("Your voice, strength %.1f: %.2f".format(a, c)) }
                        append("1.00 means identical. The same method reached about 0.80 on clean test voices.")
                    }
                } finally { v.release() }
            }
        } catch (t: Throwable) {
            if (t is OutOfMemoryError) "Not enough memory for this check." else "The check failed (${t.javaClass.simpleName})."
        } finally {
            withContext(Dispatchers.Main.immediate) { playback.unblockNeural() }
            _status.value = ExportStatus()
        }
    }

    /** Times this phone with a voice and remembers the result (used for the time estimates). */
    suspend fun testSpeed(voiceName: String, steps: Int): String? {
        if (_status.value.running) return "Wait for the audiobook that is being made."
        val packDir = packs.installedDir(Engines.NEURAL_PACK_ID) ?: return "Install the neural voice first."
        val custom = voiceName.startsWith(Engines.CUSTOM_PREFIX)
        _status.value = ExportStatus(phase = ExportPhase.TESTING, message = "Testing this phone's speed. This takes about a minute...")
        playback.neuralBlocked = true
        return try {
            withContext(Dispatchers.Default) {
                withContext(Dispatchers.Main.immediate) { playback.releaseForExport() }
                val v = NeuralVoice.load(packDir, if (custom) packs.installedDir(Engines.CONVERTER_PACK_ID) else null, library, steps, voiceName)
                try {
                    var rtf: Double? = null
                    val synth = v.synthesizer
                    var work = 0.0; var audio = 0.0
                    for ((i, t) in TEST_SENTENCES.withIndex()) {
                        val t0 = System.nanoTime()
                        val pcm = synth.synthesize(t, 1f) { false }
                        val sec = (System.nanoTime() - t0) / 1e9
                        if (i == 0) continue // warm-up (also prepares a saved voice's fingerprint)
                        work += sec; audio += pcm.size.toDouble() / synth.sampleRate
                    }
                    if (audio > 0) rtf = work / audio
                    rtf?.let { settings.recordSpeed(ExportEstimator.Measurement(steps, custom, it)) }
                } finally { v.release() }
            }
            null
        } catch (t: Throwable) {
            if (t is OutOfMemoryError) "Not enough memory to test with this voice." else "The test failed (${t.javaClass.simpleName})."
        } finally {
            withContext(Dispatchers.Main.immediate) { playback.unblockNeural() }
            _status.value = ExportStatus()
        }
    }

    private companion object {
        val TEST_SENTENCES = listOf(
            "The old lighthouse stood quietly above the harbour while the evening tide came in.",
            "She opened the heavy door and found a letter waiting on the small wooden table.",
            "Nobody in the village could remember when the clock had last told the right time.",
        )
    }
}

/** Stops rendering when the phone is unplugged (if the user asked for that) or the battery is nearly flat. */
class BatteryGuard(private val context: Context, private val settings: Settings, private val onReason: (String) -> Unit) {
    private var checkedAt = 0L
    private var stop = false

    fun shouldStop(): Boolean {
        val now = System.currentTimeMillis()
        if (stop) return true
        if (now - checkedAt < 10_000) return false
        checkedAt = now
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        if (!plugged && settings.exportOnlyCharging.value) { onReason("Paused because the phone was unplugged. Plug it in and press Continue."); stop = true }
        else if (!plugged && level in 0..9) { onReason("Paused because the battery is low. Plug the phone in and press Continue."); stop = true }
        return stop
    }
}
