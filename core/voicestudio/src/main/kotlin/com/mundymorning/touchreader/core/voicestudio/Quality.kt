package com.mundymorning.touchreader.core.voicestudio

import kotlin.math.abs

enum class Severity { INFO, WARNING, BLOCKING }

data class QualityIssue(val severity: Severity, val code: String, val message: String)

enum class Verdict { GOOD, USABLE, POOR }

data class QualityReport(
    val durationSeconds: Double,
    val speechSeconds: Double,
    val sampleRate: Int,
    val peakDbfs: Double,
    val speechRmsDbfs: Double,
    val noiseFloorDbfs: Double,
    val snrDb: Double,
    val clippedPercent: Double,
    val issues: List<QualityIssue>,
) {
    val blocking get() = issues.any { it.severity == Severity.BLOCKING }
    val verdict: Verdict
        get() = when {
            blocking -> Verdict.POOR
            issues.any { it.severity == Severity.WARNING } -> Verdict.USABLE
            else -> Verdict.GOOD
        }
}

/**
 * Checks a recording against what voice-cloning models need. It can only measure level, noise, clipping and length;
 * it cannot tell whether more than one person is talking or whether there is music, and says so rather than guessing.
 */
object QualityAnalyzer {
    const val MIN_SPEECH_SECONDS = 5.0
    const val RECOMMENDED_SPEECH_SECONDS = 10.0
    const val MAX_SECONDS = 90.0

    fun analyze(pcm: Pcm): QualityReport {
        val db = Dsp.frameDb(pcm)
        val thr = if (db.isEmpty()) -120.0 else Dsp.speechThresholdDb(db)
        val voiced = db.filter { it >= thr }
        val speechSeconds = voiced.size * Dsp.FRAME_SECONDS
        val floor = Dsp.percentile(db, 0.10)
        val loud = Dsp.percentile(db, 0.95)
        // If nothing rises clearly above the noise floor, the voice cannot be told apart from the noise.
        val inseparable = voiced.isEmpty() && loud >= -50
        val speechLevel = when {
            voiced.isNotEmpty() -> 10.0 * kotlin.math.log10(voiced.map { Math.pow(10.0, it / 10.0) }.average() + 1e-12)
            else -> loud
        }
        val snr = speechLevel - floor
        val peak = if (pcm.samples.isEmpty()) 0.0 else pcm.samples.maxOf { abs(it) }.toDouble()
        val clipped = if (pcm.samples.isEmpty()) 0.0 else pcm.samples.count { abs(it) >= 0.99f } * 100.0 / pcm.samples.size

        val issues = mutableListOf<QualityIssue>()
        fun add(s: Severity, code: String, msg: String) { issues += QualityIssue(s, code, msg) }
        if (inseparable) add(Severity.BLOCKING, "noisy", "Background noise is as loud as the voice, so the voice cannot be separated. Record somewhere much quieter.")
        else if (speechLevel < -50) add(Severity.BLOCKING, "silent", "The recording is almost silent. Check that the microphone was not covered or muted.")
        else if (speechSeconds < MIN_SPEECH_SECONDS) add(Severity.BLOCKING, "too_short", "Only %.1f seconds of speech were found. At least %.0f seconds are needed.".format(speechSeconds, MIN_SPEECH_SECONDS))
        else if (speechSeconds < RECOMMENDED_SPEECH_SECONDS) add(Severity.WARNING, "short", "About %.0f seconds of speech were found. %.0f seconds or more gives better results.".format(speechSeconds, RECOMMENDED_SPEECH_SECONDS))
        if (speechLevel >= -50 && !inseparable) {
            if (snr < 8) add(Severity.BLOCKING, "noisy", "Background noise is too loud compared with the voice. Record somewhere quieter.")
            else if (snr < 15) add(Severity.WARNING, "some_noise", "There is noticeable background noise. A quieter room will sound better.")
            if (speechLevel < -35) add(Severity.WARNING, "quiet", "The voice is quiet. Speak closer to the microphone.")
        }
        if (clipped > 0.5) add(Severity.WARNING, "clipping", "The recording is distorted (%.1f%% of it is clipped). Speak a little further from the microphone.".format(clipped))
        if (pcm.sampleRate < 16000) add(Severity.WARNING, "low_bandwidth", "This audio has a low sample rate (${pcm.sampleRate} Hz) and will sound dull.")
        if (pcm.seconds > MAX_SECONDS) add(Severity.INFO, "long", "Only the first %.0f seconds will be used.".format(MAX_SECONDS))
        add(Severity.INFO, "single_voice", "Touch Reader cannot tell whether more than one person is speaking or whether music is playing. Use a recording of one voice only.")
        return QualityReport(pcm.seconds, speechSeconds, pcm.sampleRate, Dsp.db(peak), speechLevel, floor, snr, clipped, issues)
    }
}

/** Result of preparing a recording for voice creation. */
data class PreparedSample(val audio: Pcm, val report: QualityReport)

object VoiceSampleProcessor {
    const val STORED_SAMPLE_RATE = 24_000

    /** Cleans a raw recording: high-pass, trim silence, limit length, normalize level, resample. The report describes the *raw* input. */
    fun prepare(raw: Pcm): PreparedSample {
        val filtered = Dsp.highPass(raw)
        val report = QualityAnalyzer.analyze(filtered)
        var a = Dsp.trimSilence(filtered)
        val max = (QualityAnalyzer.MAX_SECONDS * a.sampleRate).toInt()
        if (a.samples.size > max) a = Pcm(a.sampleRate, a.samples.copyOf(max))
        a = Dsp.normalize(a)
        return PreparedSample(Dsp.resample(a, STORED_SAMPLE_RATE), report)
    }
}
