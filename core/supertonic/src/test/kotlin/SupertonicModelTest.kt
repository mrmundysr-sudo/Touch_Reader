import com.mundymorning.touchreader.core.supertonic.*
import java.io.File
import java.util.Random
import kotlin.test.*

class SupertonicModelTest {
    private val cfg = SupertonicConfig(sampleRate = 44100, baseChunkSize = 512, chunkCompressFactor = 6, latentDim = 24)

    @Test fun parsesConfig() {
        val c = SupertonicConfig.parse("""{"ae":{"sample_rate":44100,"base_chunk_size":512},"ttl":{"chunk_compress_factor":6,"latent_dim":24}}""")
        assertEquals(cfg, c)
    }

    @Test fun parsesVoiceStyle() {
        val json = """{"style_ttl":{"dims":[1,2,3],"data":[[[1,2,3],[4,5,6]]],"type":"float32"},
                        "style_dp":{"dims":[1,1,2],"data":[[[7,8]]],"type":"float32"}}"""
        val s = VoiceStyle.parse(json)
        assertContentEquals(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f), s.ttl)
        assertContentEquals(longArrayOf(1, 2, 3), s.ttlShape)
        assertContentEquals(floatArrayOf(7f, 8f), s.dp)
    }

    @Test fun mismatchedStyleDataIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            VoiceStyle.parse("""{"style_ttl":{"dims":[1,2,3],"data":[[[1,2,3]]]},"style_dp":{"dims":[1,1,1],"data":[[[1]]]}}""")
        }
    }

    /**
     * Real-model check. Runs only when SUPERTONIC_DIR points at a downloaded pack
     * (CI "probe" workflow). Verifies the Kotlin port produces plausible speech and reports speed.
     */
    @Test fun realModelProducesSpeech() {
        val dir = System.getenv("SUPERTONIC_DIR")?.let(::File) ?: run { println("SKIP realModelProducesSpeech (SUPERTONIC_DIR not set)"); return }
        val text = "The quick brown fox jumps over the lazy dog. This is a test of the speech engine."
        val t0 = System.nanoTime()
        val model = SupertonicModel.load(File(dir, "onnx"), threads = 2)
        val loadMs = (System.nanoTime() - t0) / 1_000_000
        val style = VoiceStyle.parse(File(dir, "voice_styles/M1.json").readText())
        fun peakRssMb(): Long = runCatching {
            File("/proc/self/status").readLines().first { it.startsWith("VmHWM:") }.filter(Char::isDigit).toLong() / 1024
        }.getOrDefault(-1)
        val shared = SupertonicSynthesizer(model, style, "en", totalSteps = 8)
        for (steps in listOf(8, 4)) {
            val synth = SupertonicSynthesizer(model, style, "en", totalSteps = steps)
            repeat(2) { run ->
                val s0 = System.nanoTime()
                val pcm = synth.synthesize(text, 1.05f) { false }
                val synthSec = (System.nanoTime() - s0) / 1e9
                val audioSec = pcm.size.toDouble() / synth.sampleRate
                val rms = Math.sqrt(pcm.sumOf { (it / 32768.0) * (it / 32768.0) } / pcm.size)
                println("RESULT steps=$steps run=$run loadMs=$loadMs sampleRate=${synth.sampleRate} audioSec=%.2f synthSec=%.2f RTF=%.3f rms=%.4f peakRssMb=%d".format(audioSec, synthSec, synthSec / audioSec, rms, peakRssMb()))
                assertTrue(audioSec in 2.0..20.0, "implausible duration $audioSec s")
                assertTrue(rms > 0.01, "audio looks silent (rms=$rms)")
            }
        }
        shared.release()
    }

    /** CI helper: writes synthetic reference voices (no real person involved) for the Chatterbox cloning probe. */
    @Test fun writeSyntheticReferenceClips() {
        val dir = System.getenv("SUPERTONIC_DIR")?.let(::File) ?: return
        val out = System.getenv("SUPERTONIC_REF_OUT")?.let(::File) ?: return
        out.mkdirs()
        val text = "On a quiet morning the village woke slowly, and the baker lit his oven while the children ran down to the river. " +
            "By noon the market was full of voices, bright fruit and the smell of warm bread drifting across the square."
        val model = SupertonicModel.load(File(dir, "onnx"), threads = 2)
        for (v in (System.getenv("SUPERTONIC_REF_VOICES")?.split(",") ?: listOf("M1", "F1"))) {
            val synth = SupertonicSynthesizer(model, VoiceStyle.parse(File(dir, "voice_styles/$v.json").readText()), "en", totalSteps = 8)
            val pcm = synth.synthesize(text, 1.0f) { false }
            val bytes = java.io.ByteArrayOutputStream()
            fun le(x: Int, n: Int) { for (i in 0 until n) bytes.write((x shr (8 * i)) and 0xFF) }
            bytes.write("RIFF".toByteArray()); le(36 + pcm.size * 2, 4); bytes.write("WAVEfmt ".toByteArray())
            le(16, 4); le(1, 2); le(1, 2); le(synth.sampleRate, 4); le(synth.sampleRate * 2, 4); le(2, 2); le(16, 2)
            bytes.write("data".toByteArray()); le(pcm.size * 2, 4)
            for (s in pcm) le(s.toInt() and 0xFFFF, 2)
            File(out, "ref_$v.wav").writeBytes(bytes.toByteArray())
            println("WROTE ref_$v.wav seconds=%.1f".format(pcm.size.toDouble() / synth.sampleRate))
        }
        model.close()
    }
}
