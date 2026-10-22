package com.mundymorning.touchreader.lab

import com.mundymorning.touchreader.core.export.ExportPlanner
import com.mundymorning.touchreader.core.pockettts.PocketTts
import com.mundymorning.touchreader.core.voicestudio.Dsp
import com.mundymorning.touchreader.core.voicestudio.Pcm
import com.mundymorning.touchreader.core.voicestudio.WavIo
import com.mundymorning.touchreader.studio.SamplePlayer
import com.mundymorning.touchreader.studio.VoiceLibrary
import com.mundymorning.touchreader.tts.Engines
import com.mundymorning.touchreader.tts.PocketNarrator
import com.mundymorning.touchreader.tts.VoiceChoice
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
        /** Which part of the recording the voice was copied from, in seconds from its start. */
        val fromSeconds: Int,
        val referenceSeconds: Double,
        /** 100 = as the model spoke; lower = slowed down afterwards without changing pitch. */
        val pacePercent: Int,
        /** 0 steady, 1 normal, 2 lively. */
        val delivery: Int,
        /** Whether each piece started from where the one before it ended (experimental). */
        val link: Boolean,
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
    @Volatile private var sampleProfile: String? = null
    @Volatile private var sampleChoice: VoiceChoice? = null
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

    /** The part of the recording and the pace last used for this voice (start seconds, pace percent), if any. */
    fun lastChoice(profileId: String): VoiceChoice? = PocketNarrator.choiceOrNull(library, profileId)

    /** Length of a saved recording in seconds, or null if it cannot be read. */
    fun recordingSeconds(profileId: String): Double? =
        runCatching { WavIo.read(library.sampleFile(profileId)!!.readBytes()).seconds }.getOrNull()

    /** Plays the part of the recording a voice would be copied from, so the user can choose a clean one. */
    fun listen(profileId: String, startSeconds: Int) {
        val pcm = runCatching { WavIo.read(library.sampleFile(profileId)!!.readBytes()) }.getOrNull() ?: return
        val from = (startSeconds * pcm.sampleRate).coerceAtMost(pcm.samples.size)
        val to = minOf(pcm.samples.size, from + REFERENCE_SECONDS * pcm.sampleRate)
        player.playPcm("lab-segment", Pcm(pcm.sampleRate, pcm.samples.copyOfRange(from, to)))
    }


    // ---- Compare A / B / C: up to three finished samples per voice, kept as files so they play back instantly ----

    /** One kept sample: the settings it was made with and the finished audio. */
    data class Candidate(val slot: Int, val choice: VoiceChoice, val file: java.io.File)

    private val candDir = java.io.File(context.filesDir, "lab-candidates")
    private val _candidatesVersion = MutableStateFlow(0)

    /** Changes whenever a kept sample is added or removed, so screens can refresh. */
    val candidatesVersion: StateFlow<Int> = _candidatesVersion.asStateFlow()

    private fun candWav(profileId: String, slot: Int) = java.io.File(candDir, "$profileId-${"ABC"[slot]}.wav")
    private fun candMeta(profileId: String, slot: Int) = java.io.File(candDir, "$profileId-${"ABC"[slot]}.txt")

    /** Which voice the sample just made belongs to. */
    fun sampleVoiceId(): String? = if (sample != null) sampleProfile else null

    /** Whether the sample just made can be kept (it belongs to this voice). */
    fun canKeep(profileId: String): Boolean = sample != null && sampleProfile == profileId && sampleChoice != null

    /** Keeps the sample just made as A, B or C, replacing what was there. */
    fun keep(profileId: String, slot: Int): Boolean {
        val pcm = sample ?: return false
        val c = sampleChoice ?: return false
        if (sampleProfile != profileId || slot !in 0..2) return false
        return runCatching {
            candDir.mkdirs()
            candWav(profileId, slot).writeBytes(WavIo.write16(pcm))
            candMeta(profileId, slot).writeText("${c.start},${c.pace},${c.delivery}")
            _candidatesVersion.value++
        }.isSuccess
    }

    /** The three slots for a voice; null where nothing is kept. */
    fun candidates(profileId: String): List<Candidate?> = List(3) { slot ->
        runCatching {
            val wav = candWav(profileId, slot).takeIf { it.isFile } ?: return@runCatching null
            val f = candMeta(profileId, slot).readText().split(",").map { it.trim().toInt() }
            Candidate(slot, VoiceChoice(f[0], f[1].coerceIn(80, 100), f[2].coerceIn(0, 2), false), wav)
        }.getOrNull()
    }

    fun playCandidate(profileId: String, slot: Int) {
        val c = candidates(profileId).getOrNull(slot) ?: return
        player.play("cand-$slot", c.file)
    }

    /** Makes a kept sample's settings the ones reading and audiobooks use for this voice. */
    fun useCandidate(profileId: String, slot: Int): Boolean {
        val c = candidates(profileId).getOrNull(slot) ?: return false
        PocketNarrator.saveChoice(library, profileId, c.choice)
        _candidatesVersion.value++
        return true
    }

    fun clearCandidate(profileId: String, slot: Int) {
        runCatching { candWav(profileId, slot).delete(); candMeta(profileId, slot).delete() }
        _candidatesVersion.value++
    }

    /** Removes every kept sample of a voice (when the voice is deleted, its audio goes with it). */
    fun deleteCandidates(profileId: String) { for (slot in 0..2) runCatching { candWav(profileId, slot).delete(); candMeta(profileId, slot).delete() }; _candidatesVersion.value++ }

    fun play() { sample?.let { player.playPcm(PLAY_KEY, it) } }
    fun stopPlaying() = player.stop()

    fun close() {
        cancelled = true
        player.stop()
        sample = null
        _state.value = LabState.Idle
    }

    fun make(profileId: String, voiceName: String, choice: VoiceChoice) {
        if (worker?.isAlive == true) return
        val dir = packs.installedDir(Engines.POCKET_PACK_ID) ?: run { _state.value = LabState.Failed(voiceName, "Install the voice lab download first."); return }
        val file = library.sampleFile(profileId) ?: run { _state.value = LabState.Failed(voiceName, "This voice has no saved recording."); return }
        cancelled = false
        sample = null
        sampleProfile = null
        sampleChoice = null
        player.stop()
        runCatching { traceFile.writeText("") }
        _report.value = null
        PocketNarrator.saveChoice(library, profileId, choice) // what is heard here is what reading and audiobooks use
        trace("start: pace ${choice.pace}%, delivery ${choice.delivery}, link ${choice.link}, seconds ${choice.start} to ${choice.start + REFERENCE_SECONDS}, ${Runtime.getRuntime().availableProcessors()} cores")
        _state.value = LabState.Working(voiceName, "Loading the model...")
        worker = Thread({ execute(dir, profileId, voiceName, choice) }, "tr-voice-lab").apply { isDaemon = true; start() }
    }

    private fun execute(dir: java.io.File, profileId: String, voiceName: String, choice: VoiceChoice) {
        try {
            val threads = PocketNarrator.threads()
            val t0 = System.nanoTime()
            trace("loading the model")
            PocketTts.load(dir, threads).use { tts ->
                trace("model loaded")
                val loadSeconds = (System.nanoTime() - t0) / 1e9
                if (cancelled) return

                trace("studying the recording (seconds ${choice.start} to ${choice.start + REFERENCE_SECONDS})")
                val (embedding, studiedIn) = PocketNarrator.embeddingFor(tts, library, profileId, choice.start)
                val voiceSeconds = studiedIn
                val cachedAlready = studiedIn == null
                trace("recording studied")
                val used = embedding.frames * tts.bundle.samplesPerFrame.toDouble() / tts.bundle.sampleRate
                if (cancelled) return

                trace("preparing the voice (${embedding.frames} frames)")
                tts.prepareVoice(embedding).use { voice ->
                    trace("voice prepared")
                    // exactly what reading and audiobooks do: sentences of a paragraph joined into pieces, trimmed, paced, with pauses between pieces
                    val synth = tts.synthesizer(voice, { PocketTts.Delivery.temperature(choice.delivery) }, 1, { choice.pace / 100.0 }, { choice.link })
                    val pieces = groupSentences(PARAGRAPH, ExportPlanner.FLOW_MAX_CHARS)
                    val out = ArrayList<ShortArray>()
                    var work = 0.0
                    var spokenSamples = 0L
                    for ((i, piece) in pieces.withIndex()) {
                        if (cancelled) return
                        trace("making piece ${i + 1}")
                        _state.value = LabState.Working(voiceName, "Making part ${i + 1} of ${pieces.size}...")
                        val s0 = System.nanoTime()
                        val audio = synth.synthesize(piece, 1f) { cancelled }
                        if (audio.isEmpty()) {
                            if (!cancelled) _state.value = LabState.Failed(voiceName, "The model made no sound for part ${i + 1}.")
                            return
                        }
                        work += (System.nanoTime() - s0) / 1e9
                        spokenSamples += audio.size
                        out += audio
                        if (i < pieces.lastIndex) out += ShortArray(ExportPlanner.FLOW_SENTENCE_PAUSE_MS * tts.bundle.sampleRate / 1000)
                    }
                    synth.release()
                    val all = FloatArray(out.sumOf { it.size })
                    var at = 0
                    for (p in out) { for (k in p.indices) all[at + k] = p[k] / 32768f; at += p.size }
                    val pcm = Pcm(tts.bundle.sampleRate, all)
                    sample = pcm
                    sampleProfile = profileId
                    sampleChoice = choice
                    trace("DONE")
                    // seconds of speech before any slowing, so the speed figure means the same at every pace
                    val unslowed = spokenSamples.toDouble() / tts.bundle.sampleRate * (choice.pace / 100.0)
                    _state.value = LabState.Done(voiceName, unslowed, work, loadSeconds, voiceSeconds, cachedAlready, choice.start, used, choice.pace, choice.delivery, choice.link)
                    player.playPcm(PLAY_KEY, pcm)
                }
            }
        } catch (t: Throwable) {
            trace("failed: ${t.javaClass.simpleName} ${t.message ?: ""}")
            if (!cancelled) _state.value = LabState.Failed(voiceName,
                if (t is OutOfMemoryError) "Not enough memory for this model on this phone." else "The voice lab failed: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    /** Joins whole sentences into pieces of up to [maxChars], like narration does. */
    private fun groupSentences(text: String, maxChars: Int): List<String> {
        val sentences = text.split(Regex("(?<=[.!?\"”])\\s+")).filter { it.isNotBlank() }
        val out = ArrayList<String>()
        var cur = ""
        for (s in sentences) {
            if (cur.isNotEmpty() && cur.length + 1 + s.length > maxChars) { out += cur; cur = s } else cur = if (cur.isEmpty()) s else "$cur $s"
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    private companion object {
        const val PLAY_KEY = "voice-lab"
        private const val REFERENCE_SECONDS = PocketNarrator.REFERENCE_SECONDS
        /** A short paragraph with dialogue, to judge how it flows rather than single sentences. */
        const val PARAGRAPH = "The old lighthouse stood quietly above the harbour while the evening tide came in. " +
            "She opened the heavy door and found a letter waiting on the small wooden table. " +
            "\"Is anyone here?\" she asked, but nobody answered. " +
            "Outside, the wind began to rise, and the sea grew restless."
    }
}
