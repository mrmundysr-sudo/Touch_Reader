import com.mundymorning.touchreader.core.supertonic.SupertonicModel
import com.mundymorning.touchreader.core.supertonic.SupertonicSynthesizer
import com.mundymorning.touchreader.core.supertonic.VoiceStyle
import com.mundymorning.touchreader.core.voiceconvert.*
import com.mundymorning.touchreader.core.voicestudio.Dsp
import com.mundymorning.touchreader.core.voicestudio.Pcm
import com.mundymorning.touchreader.core.voicestudio.WavIo
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.test.*

/**
 * Real-model checks, run only in CI when CONVERTER_DIR (the built converter pack incl. parity.json), SUPERTONIC_DIR and
 * REF_DIR are set: the Kotlin front-end must reproduce PyTorch's numbers, and the whole chain must move timbre.
 */
class ConverterIntegrationTest {
    private val dir = System.getenv("CONVERTER_DIR")?.let(::File)
    private val sup = System.getenv("SUPERTONIC_DIR")?.let(::File)
    private val refs = System.getenv("REF_DIR")?.let(::File)

    @Test fun kotlinFrontEndMatchesPyTorch() {
        val d = dir ?: run { println("SKIP converter parity (CONVERTER_DIR not set)"); return }
        val p = JSONObject(File(d, "parity.json").readText())
        val audio = p.getJSONArray("audio").let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } }
        val cfg = ConverterConfig.parse(File(d, "converter_config.json").readText())
        val spec = SpectrogramExtractor(cfg.spectrogram).compute(audio)
        val shape = p.getJSONArray("spec_shape")
        assertEquals(shape.getInt(1), spec.freqBins); assertEquals(shape.getInt(2), spec.frames)
        val ref = p.getJSONArray("spec")
        var maxAbs = 0.0; var sumAbs = 0.0
        for (i in 0 until ref.length()) { val e = abs(ref.getDouble(i) - spec.data[i]); maxAbs = maxOf(maxAbs, e); sumAbs += e }
        println("RESULT parity spectrogram frames=${spec.frames} maxAbsDiff=%.6f meanAbsDiff=%.8f".format(maxAbs, sumAbs / ref.length()))
        assertTrue(maxAbs < 2e-3, "spectrogram differs from PyTorch by $maxAbs")

        OnnxToneConverter.load(d).use { conv ->
            val fp = conv.embed(audio)
            val rf = p.getJSONArray("fingerprint")
            val worst = (0 until rf.length()).maxOf { abs(rf.getDouble(it) - fp[it]) }
            println("RESULT parity fingerprint maxAbsDiff=%.6f cosine=%.6f".format(worst, EmbeddingCodec.cosine(fp, FloatArray(rf.length()) { rf.getDouble(it).toFloat() })))
            assertTrue(worst < 2e-3, "fingerprint differs from PyTorch by $worst")
        }
    }

    @Test fun fullChainMovesTimbreTowardTheTarget() {
        val d = dir ?: run { println("SKIP converter chain (CONVERTER_DIR not set)"); return }
        val s = sup ?: return; val r = refs ?: return
        val text = "The quick brown fox jumps over the lazy dog. This sentence is spoken in a converted voice."
        val model = SupertonicModel.load(File(s, "onnx"), threads = 2)
        val base = SupertonicSynthesizer(model, VoiceStyle.parse(File(s, "voice_styles/M1.json").readText()), "en", totalSteps = 4)
        val baseVoice = object : BaseVoice {
            override val sampleRate = base.sampleRate
            override fun usePreset(name: String) { base.style = VoiceStyle.parse(File(s, "voice_styles/$name.json").readText()) }
            override fun synthesizeFloat(text: String, speed: Float, isCancelled: () -> Boolean) = base.synthesizeFloat(text, speed, isCancelled)
        }
        val conv = OnnxToneConverter.load(d)
        val fps = OnnxToneConverter.loadSourceEmbeddings(d)
        val ref = WavIo.read(File(r, "ref_F1.wav").readBytes())
        val target = conv.embed(Dsp.resample(ref, conv.sampleRate).samples)
        val synth = ConvertingSynthesizer(baseVoice, conv, fps)
        synth.useCustom(forcePreset = "M1") { target }   // male base voice -> female target: a hard case on purpose
        synth.synthesize("Warm up.", 1.05f) { false }
        val t0 = System.nanoTime()
        val pcm = synth.synthesize(text, 1.05f) { false }
        val secs = (System.nanoTime() - t0) / 1e9
        val audioSec = pcm.size.toDouble() / synth.sampleRate
        val out = FloatArray(pcm.size) { pcm[it] / 32768f }
        val outFp = conv.embed(Dsp.resample(Pcm(synth.sampleRate, out), conv.sampleRate).samples)
        val toTarget = EmbeddingCodec.cosine(outFp, target); val toSource = EmbeddingCodec.cosine(outFp, fps.getValue("M1"))
        println("RESULT chain audioSec=%.2f totalSec=%.2f RTF=%.3f toTarget=%.3f toSourceM1=%.3f".format(audioSec, secs, secs / audioSec, toTarget, toSource))
        File(System.getenv("PROBE_OUT") ?: ".", "chain_M1_to_F1.wav").also { it.parentFile.mkdirs() }.writeBytes(WavIo.write16(Pcm(synth.sampleRate, out)))
        assertTrue(audioSec in 3.0..20.0, "implausible duration $audioSec")
        assertTrue(toTarget > toSource, "converted voice is not closer to the target ($toTarget vs $toSource)")
        synth.release()
    }
}
