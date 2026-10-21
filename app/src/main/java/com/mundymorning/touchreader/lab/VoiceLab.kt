package com.mundymorning.touchreader.lab

import com.mundymorning.touchreader.core.pockettts.PocketTts
import com.mundymorning.touchreader.core.voicestudio.Dsp
import com.mundymorning.touchreader.core.voicestudio.Pcm
import com.mundymorning.touchreader.core.voicestudio.WavIo
import com.mundymorning.touchreader.studio.SamplePlayer
import com.mundymorning.touchreader.studio.VoiceLibrary
import com.mundymorning.touchreader.tts.Engines
import com.mundymorning.touchreader.voices.VoicePackManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface LabState {
    data object Idle : LabState
    data class Working(val voiceName: String, val message: String) : LabState
    data class Done(
        val voiceName: String,
        val audioSeconds: Double,
        val speechSeconds: Double,
        val loadSeconds: Double,
        val voiceSeconds: Double?,
        val voiceCached: Boolean,
        /** How many seconds of the recording the voice was copied from. */
        val referenceSeconds: Double,
    ) : LabState {
        /** Seconds of work per second of speech; below 1.0 a book could be read live on this phone. */
        val rtf: Double get() = speechSeconds / audioSeconds
    }
    data class Failed(val voiceName: String, val message: String) : LabState
}

/**
 * A test bench, not a feature: makes a short sample from a saved recording with Pocket TTS so the user can hear how
 * close it gets and see how long it took on this phone. Nothing is uploaded and nothing else in the app uses it.
 */
class VoiceLab(
    private val context: android.content.Context,
    private val packs: VoicePackManager,
    private val library: VoiceLibrary,
    private val player: SamplePlayer,
) {
    private val _state = MutableStateFlow<LabState>(LabState.Idle)
    val state: StateFlow<LabState> = _state.asStateFlow()

    @Volatile private var sample: Pcm? = null
    @Volatile private var cancelled = false
    private var worker: Thread? = null

    private val traceFile = java.io.File(context.filesDir, "voicelab-trace.txt")
    private val _report = MutableStateFlow(buildReport())

    /** If the last voice lab run did not finish: where it stopped and why Android says the app ended. Null otherwise. */
    val report: StateFlow<String?> = _report.asStateFlow()

    fun dismissReport() { runCatching { traceFile.delete() }; _report.value = null }

    /** One line per step, written straight away, so a crash that kills the app still leaves a record of where it was. */
    private fun trace(step: String) {
        val mb = android.os.Debug.getNativeHeapAllocatedSize() / 1_000_000
        runCatching { traceFile.appendText("${System.currentTimeMillis()} | native memory $mb MB | $step\n") }
    }

    private fun buildReport(): String? {
        val lines = runCatching { traceFile.readLines() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        if (lines.last().endsWith("| DONE")) return null
        val last = lines.last().split(" | ").drop(1).joinToString(", ")
        val why = if (android.os.Build.VERSION.SDK_INT < 30) null else runCatching {
            val am = context.getSystemService(android.app.ActivityManager::class.java)
            am.getHistoricalProcessExitReasons(null, 0, 5)
                .firstOrNull { it.reason != android.app.ApplicationExitInfo.REASON_USER_REQUESTED && it.reason != android.app.ApplicationExitInfo.REASON_EXIT_SELF }
                ?.let { "Android says the app ended because of: ${exitName(it.reason)}${it.description?.let { d -> " ($d)" } ?: ""}; it was using about ${it.rss / 1_000_000} MB." }
        }.getOrNull()
        return "The last voice lab run did not finish. It stopped at: $last. " + (why ?: "Android gave no reason.")
    }

    @android.annotation.SuppressLint("InlinedApi")
    private fun exitName(r: Int) = when (r) {
        android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory"
        android.app.ApplicationExitInfo.REASON_CRASH -> "a crash"
        android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "a native crash"
        android.app.ApplicationExitInfo.REASON_SIGNALED -> "being killed by a signal"
        android.app.ApplicationExitInfo.REASON_ANR -> "not responding"
        android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "using too many resources"
        else -> "code $r"
    }

    val installed: Boolean get() = packs.installedDir(Engines.POCKET_PACK_ID) != null

    fun play() { sample?.let { player.playPcm(PLAY_KEY, it) } }
    fun stopPlaying() = player.stop()

    fun close() {
        cancelled = true
        player.stop()
        sample = null
        _state.value = LabState.Idle
    }

    fun make(profileId: String, voiceName: String, referenceSeconds: Int = 15) {
        if (worker?.isAlive == true) return
        val dir = packs.installedDir(Engines.POCKET_PACK_ID) ?: run { _state.value = LabState.Failed(voiceName, "Install the voice lab download first."); return }
        val file = library.sampleFile(profileId) ?: run { _state.value = LabState.Failed(voiceName, "This voice has no saved recording."); return }
        cancelled = false
        sample = null
        player.stop()
        runCatching { traceFile.writeText("") }
        _report.value = null
        trace("start: reference ${referenceSeconds} s, ${Runtime.getRuntime().availableProcessors()} cores")
        _state.value = LabState.Working(voiceName, "Loading the model...")
        worker = Thread({ execute(dir, file, profileId, voiceName, referenceSeconds) }, "tr-voice-lab").apply { isDaemon = true; start() }
    }

    private fun execute(dir: java.io.File, file: java.io.File, profileId: String, voiceName: String, referenceSeconds: Int) {
        try {
            val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
            val t0 = System.nanoTime()
            trace("loading the model")
            PocketTts.load(dir, threads).use { tts ->
                trace("model loaded")
                val loadSeconds = (System.nanoTime() - t0) / 1e9
                if (cancelled) return

                var voiceSeconds: Double? = null
                var used = 0.0
                val version = "$VERSION-${referenceSeconds}s" // a different length is a different voice fingerprint
                val cached = library.engineData(profileId, ENGINE, version)?.let { PocketTts.decodeEmbedding(it, tts.bundle.conditioningDim) }
                val embedding = cached ?: run {
                    _state.value = LabState.Working(voiceName, "Studying the recording...")
                    val v0 = System.nanoTime()
                    val pcm = Dsp.resample(WavIo.read(file.readBytes()), tts.bundle.sampleRate)
                    tts.encodeVoice(pcm.samples, referenceSeconds.toFloat()).also {
                        voiceSeconds = (System.nanoTime() - v0) / 1e9
                        library.saveEngineData(profileId, ENGINE, version, PocketTts.encodeEmbedding(it))
                    }
                }
                trace("recording studied")
                used = embedding.frames * tts.bundle.samplesPerFrame.toDouble() / tts.bundle.sampleRate
                if (cancelled) return

                trace("preparing the voice (${embedding.frames} frames)")
                tts.prepareVoice(embedding).use { voice ->
                    trace("voice prepared")
                    val gen = tts.generator()
                    val out = ArrayList<FloatArray>()
                    var work = 0.0
                    for ((i, line) in LINES.withIndex()) {
                        if (cancelled) return
                        trace("making sentence ${i + 1}")
                        _state.value = LabState.Working(voiceName, "Making sentence ${i + 1} of ${LINES.size}...")
                        val s0 = System.nanoTime()
                        val audio = gen.generate(line, voice) { cancelled }
                        if (audio == null) {
                            trace("the model made no sound for sentence ${i + 1}")
                            if (!cancelled) _state.value = LabState.Failed(voiceName, "The model made no sound for a sentence.")
                            return
                        }
                        work += (System.nanoTime() - s0) / 1e9
                        out += audio
                    }
                    val all = FloatArray(out.sumOf { it.size }).also { a -> var o = 0; out.forEach { p -> p.copyInto(a, o); o += p.size } }
                    val pcm = Pcm(tts.bundle.sampleRate, all)
                    sample = pcm
                    trace("DONE")
                    _state.value = LabState.Done(voiceName, pcm.seconds, work, loadSeconds, voiceSeconds, cached != null, used)
                    player.playPcm(PLAY_KEY, pcm)
                }
            }
        } catch (t: Throwable) {
            trace("failed: ${t.javaClass.simpleName} ${t.message ?: ""}")
            if (!cancelled) _state.value = LabState.Failed(voiceName,
                if (t is OutOfMemoryError) "Not enough memory for this model on this phone." else "The voice lab failed: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private companion object {
        const val ENGINE = "pocket-tts-onnx"
        const val VERSION = "1"
        const val PLAY_KEY = "voice-lab"
        val LINES = listOf(
            "The old lighthouse stood quietly above the harbour while the evening tide came in.",
            "She opened the heavy door and found a letter waiting on the small wooden table.",
            "Nobody in the village could remember when the clock had last told the right time.",
        )
    }
}
