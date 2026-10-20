import com.mundymorning.touchreader.core.voicestudio.*
import java.io.File
import java.util.Random
import kotlin.io.path.createTempDirectory
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.*

/** Speech-like test signal: voiced harmonic bursts at ~3 Hz syllable rate with pauses, plus optional noise. */
private fun speech(seconds: Double, rate: Int = 24000, level: Double = 0.3, noise: Double = 0.0, lead: Double = 0.0, trail: Double = 0.0, seed: Long = 1): Pcm {
    val rnd = Random(seed)
    val n = (seconds * rate).toInt(); val l = (lead * rate).toInt(); val t = (trail * rate).toInt()
    val x = FloatArray(l + n + t)
    for (i in 0 until l + n + t) x[i] = (rnd.nextGaussian() * noise).toFloat()
    for (i in 0 until n) {
        val time = i.toDouble() / rate
        val env = (1.5 * sin(2 * PI * 1.5 * time) + 0.75).coerceIn(0.0, 1.0)      // syllable bursts, ~65% voiced like real speech
        var s = 0.0
        for (h in 1..6) s += sin(2 * PI * 140.0 * h * time) / h
        x[l + i] += (level * env * s / 2).toFloat()
    }
    return Pcm(rate, x)
}

class VoiceStudioTest {
    private val tmp = createTempDirectory("vs").toFile()
    @AfterTest fun clean() { tmp.deleteRecursively() }

    // ---- WAV ----
    @Test fun wavRoundTrip() {
        val a = Pcm(16000, FloatArray(1600) { sin(2 * PI * 440 * it / 16000).toFloat() * 0.5f })
        val b = WavIo.read(WavIo.write16(a))
        assertEquals(16000, b.sampleRate); assertEquals(1600, b.samples.size)
        assertTrue(a.samples.indices.all { abs(a.samples[it] - b.samples[it]) < 1e-3 })
    }

    @Test fun wavStereoIsDownmixedAndGarbageIsRejected() {
        val mono = WavIo.write16(Pcm(8000, floatArrayOf(0.5f, 0.5f)))
        // build a stereo 16-bit file by hand: L=0.5, R=-0.5 -> 0
        val data = byteArrayOf(0, 64, 0, -64, 0, 64, 0, -64)
        val head = mono.copyOfRange(0, 44).also {
            java.nio.ByteBuffer.wrap(it).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply { putShort(22, 2); putInt(28, 8000 * 4); putShort(32, 4); putInt(40, data.size) }
        }
        val s = WavIo.read(head + data)
        assertEquals(2, s.samples.size); assertTrue(s.samples.all { abs(it) < 1e-3 })
        assertFailsWith<UnsupportedAudioException> { WavIo.read(ByteArray(100)) }
    }

    // ---- DSP ----
    @Test fun resamplePreservesToneAndLength() {
        val a = Pcm(44100, FloatArray(44100) { sin(2 * PI * 1000 * it / 44100).toFloat() * 0.5f })
        val b = Dsp.resample(a, 24000)
        assertTrue(abs(b.samples.size - 24000) <= 5, "length ${b.samples.size}")
        val mid = b.samples.copyOfRange(2000, 22000)
        assertEquals(0.5f, mid.max(), 0.02f)
        // zero crossings of a 1 kHz tone over 20000 samples at 24 kHz = ~1666 positive-going
        val crossings = (1 until mid.size).count { mid[it - 1] < 0 && mid[it] >= 0 }
        assertTrue(abs(crossings - 833) <= 3, "crossings $crossings")
    }

    @Test fun resampleAttenuatesAboveNewNyquist() {
        val a = Pcm(48000, FloatArray(48000) { sin(2 * PI * 20000 * it / 48000).toFloat() * 0.5f })
        val b = Dsp.resample(a, 16000)
        assertTrue(b.samples.copyOfRange(500, 15000).maxOf { abs(it) } < 0.05f, "aliasing leaked through")
    }

    @Test fun highPassRemovesRumbleKeepsVoice() {
        val rumble = Pcm(24000, FloatArray(24000) { sin(2 * PI * 20 * it / 24000).toFloat() * 0.5f })
        val voice = Pcm(24000, FloatArray(24000) { sin(2 * PI * 500 * it / 24000).toFloat() * 0.5f })
        assertTrue(Dsp.highPass(rumble).samples.copyOfRange(6000, 24000).maxOf { abs(it) } < 0.2f)
        assertTrue(Dsp.highPass(voice).samples.copyOfRange(6000, 24000).maxOf { abs(it) } > 0.45f)
    }

    @Test fun trimSilenceKeepsSpeechAndRemovesEdges() {
        val s = speech(8.0, lead = 3.0, trail = 3.0, noise = 0.002)
        val t = Dsp.trimSilence(s)
        assertTrue(t.seconds in 7.5..9.0, "trimmed to ${t.seconds}s from ${s.seconds}s")
    }

    @Test fun trimSilenceOnPureSilenceReturnsEmptyOrTiny() {
        val silent = Pcm(24000, FloatArray(24000 * 3))
        assertTrue(Dsp.trimSilence(silent).samples.size <= silent.samples.size)
    }

    @Test fun normalizeReachesTargetLevelWithoutClipping() {
        val quiet = speech(6.0, level = 0.02)
        val n = Dsp.normalize(quiet)
        val rep = QualityAnalyzer.analyze(n)
        assertEquals(-23.0, rep.speechRmsDbfs, 2.5)
        assertTrue(n.samples.maxOf { abs(it) } <= 0.9f)
    }

    @Test fun normalizeLimitsPeaks() {
        val peaky = speech(6.0, level = 0.05)
        peaky.samples[30000] = 0.9f // a spike
        assertTrue(Dsp.normalize(peaky).samples.maxOf { abs(it) } <= 0.9f)
    }

    // ---- quality ----
    @Test fun goodRecordingIsGood() {
        val r = QualityAnalyzer.analyze(speech(20.0, noise = 0.001))
        assertEquals(Verdict.GOOD, r.verdict, r.issues.toString())
        assertFalse(r.blocking)
    }

    @Test fun tooShortIsBlocking() {
        val r = QualityAnalyzer.analyze(speech(3.0, noise = 0.001))
        assertTrue(r.issues.any { it.code == "too_short" && it.severity == Severity.BLOCKING })
        assertEquals(Verdict.POOR, r.verdict)
    }

    @Test fun silenceIsBlocking() {
        val r = QualityAnalyzer.analyze(Pcm(24000, FloatArray(24000 * 10)))
        assertTrue(r.blocking)
    }

    @Test fun shortButUsableGetsWarning() {
        val r = QualityAnalyzer.analyze(speech(9.0, noise = 0.001))
        assertTrue(r.issues.any { it.code == "short" }, r.issues.toString())
        assertFalse(r.blocking)
    }

    @Test fun noiseIsDetected() {
        val noisy = QualityAnalyzer.analyze(speech(20.0, level = 0.15, noise = 0.08))
        assertTrue(noisy.issues.any { it.code == "noisy" || it.code == "some_noise" }, "snr=${noisy.snrDb} ${noisy.issues}")
        assertTrue(noisy.snrDb < QualityAnalyzer.analyze(speech(20.0, level = 0.15, noise = 0.001)).snrDb)
    }

    @Test fun clippingIsDetected() {
        val s = speech(20.0, level = 4.0, noise = 0.001)
        for (i in s.samples.indices) s.samples[i] = s.samples[i].coerceIn(-1f, 1f)
        val r = QualityAnalyzer.analyze(s)
        assertTrue(r.issues.any { it.code == "clipping" }, "clipped=${r.clippedPercent}")
    }

    @Test fun lowSampleRateIsFlaggedAndAlwaysAdmitsMultiSpeakerBlindSpot() {
        val r = QualityAnalyzer.analyze(speech(20.0, rate = 8000, noise = 0.001))
        assertTrue(r.issues.any { it.code == "low_bandwidth" })
        assertTrue(r.issues.any { it.code == "single_voice" && it.severity == Severity.INFO })
    }

    @Test fun prepareProducesCleanStoredSample() {
        val raw = speech(25.0, rate = 44100, level = 0.05, noise = 0.002, lead = 2.0, trail = 2.0)
        val p = VoiceSampleProcessor.prepare(raw)
        assertEquals(VoiceSampleProcessor.STORED_SAMPLE_RATE, p.audio.sampleRate)
        assertTrue(p.audio.seconds in 24.0..27.0, "len=${p.audio.seconds}")
        assertEquals(-23.0, QualityAnalyzer.analyze(p.audio).speechRmsDbfs, 3.0)
        assertTrue(p.report.durationSeconds > 28) // report describes the raw input
    }

    @Test fun prepareCapsVeryLongRecordings() {
        val p = VoiceSampleProcessor.prepare(speech(120.0, rate = 16000, noise = 0.001))
        assertTrue(p.audio.seconds <= QualityAnalyzer.MAX_SECONDS + 0.5)
        assertTrue(p.report.issues.any { it.code == "long" })
    }

    // ---- profile store ----
    private fun sample() = VoiceSampleProcessor.prepare(speech(15.0, noise = 0.001))

    @Test fun storeCreatesListsRenamesAndDeletes() {
        var now = 1000L
        val store = VoiceProfileStore(File(tmp, "voices")) { now++ }
        val p = store.create("  Grandma  ", "stories", sample(), consentAcknowledged = true)
        assertEquals("Grandma", p.name)
        assertEquals(1, store.list().size)
        assertTrue(store.sampleFile(p.id)!!.length() > 1000)
        assertTrue(store.rename(p.id, "Nana"))
        assertEquals("Nana", store.get(p.id)!!.name)
        assertTrue(store.delete(p.id))
        assertTrue(store.list().isEmpty())
        assertFalse(File(tmp, "voices/${p.id}").exists())
    }

    @Test fun consentIsRequired() {
        val store = VoiceProfileStore(File(tmp, "v"))
        assertFailsWith<ConsentRequiredException> { store.create("x", "", sample(), consentAcknowledged = false) }
        assertTrue(store.list().isEmpty())
    }

    @Test fun poorRecordingCannotBecomeAVoice() {
        val store = VoiceProfileStore(File(tmp, "v"))
        val bad = VoiceSampleProcessor.prepare(speech(2.0, noise = 0.001))
        assertFailsWith<IllegalArgumentException> { store.create("x", "", bad, true) }
        assertTrue(store.list().isEmpty())
    }

    @Test fun profilesSurviveReopeningAndKeepOrder() {
        var now = 1L
        val dir = File(tmp, "v")
        val a = VoiceProfileStore(dir) { now++ }.create("first", "", sample(), true)
        val b = VoiceProfileStore(dir) { now++ }.create("second", "", sample(), true)
        assertEquals(listOf(b.id, a.id), VoiceProfileStore(dir).list().map { it.id })
    }

    @Test fun engineDataIsStoredPerEngineAndReplaced() {
        val store = VoiceProfileStore(File(tmp, "v"))
        val p = store.create("v", "", sample(), true)
        assertTrue(store.saveEngineData(p.id, "openvoice", "2", byteArrayOf(1, 2, 3)))
        assertContentEquals(byteArrayOf(1, 2, 3), store.engineData(p.id, "openvoice", "2"))
        assertNull(store.engineData(p.id, "openvoice", "3"), "a different model version must not reuse old data")
        store.saveEngineData(p.id, "openvoice", "3", byteArrayOf(9))
        assertEquals(1, store.get(p.id)!!.engineData.size)
    }

    @Test fun deletingTheRecordingKeepsDerivedData() {
        val store = VoiceProfileStore(File(tmp, "v"))
        val p = store.create("v", "", sample(), true)
        store.saveEngineData(p.id, "openvoice", "2", byteArrayOf(5))
        assertTrue(store.deleteSample(p.id))
        assertNull(store.sampleFile(p.id)); assertFalse(store.get(p.id)!!.hasSample)
        assertContentEquals(byteArrayOf(5), store.engineData(p.id, "openvoice", "2"))
    }

    @Test fun deleteRefusesPathsOutsideTheStore() {
        val store = VoiceProfileStore(File(tmp, "v"))
        File(tmp, "keep").mkdirs()
        assertFalse(store.delete("../keep"))
        assertTrue(File(tmp, "keep").exists())
    }
}
