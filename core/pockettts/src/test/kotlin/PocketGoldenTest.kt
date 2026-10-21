import com.mundymorning.touchreader.core.pockettts.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.*

/**
 * Compares the Kotlin port with the reference Python implementation on the real model files. Runs only in CI
 * (POCKET_DIR = the English bundle folder, POCKET_GOLDEN = what `scripts/pocket_golden.py` recorded).
 */
class PocketGoldenTest {
    private val modelDir: File? = System.getenv("POCKET_DIR")?.takeIf { it.isNotEmpty() }?.let { File(it) }
    private val goldenDir: File? = System.getenv("POCKET_GOLDEN")?.takeIf { it.isNotEmpty() }?.let { File(it) }

    private fun needModel(): Pair<File, File> {
        assumeTrue(modelDir != null && goldenDir != null && File(modelDir, "bundle.json").isFile, "POCKET_DIR / POCKET_GOLDEN not set: skipping the real-model comparison")
        return modelDir!! to goldenDir!!
    }

    private fun floats(a: JSONArray) = FloatArray(a.length()) { a.getDouble(it).toFloat() }
    private fun ints(a: JSONArray) = IntArray(a.length()) { a.getInt(it) }
    private fun readFloats(f: File): FloatArray {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(b.remaining()).also { b.get(it) }
    }
    private fun maxAbs(a: FloatArray, b: FloatArray): Double {
        assertEquals(a.size, b.size, "lengths differ")
        var m = 0.0
        for (i in a.indices) m = maxOf(m, abs((a[i] - b[i]).toDouble()))
        return m
    }
    private fun readWav16(f: File): FloatArray {
        val b = f.readBytes()
        var p = 12
        while (p + 8 <= b.size) {
            val id = String(b, p, 4, Charsets.US_ASCII)
            val n = ByteBuffer.wrap(b, p + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (id == "data") {
                val s = ByteBuffer.wrap(b, p + 8, n).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                return FloatArray(s.remaining()) { s.get(it) / 32768f }
            }
            p += 8 + n + (n and 1)
        }
        error("no data chunk")
    }

    private fun load(): Triple<PocketTts, File, File> {
        val (m, g) = needModel()
        return Triple(PocketTts.load(m, 2), m, g)
    }

    @Test fun tokenizerMatchesSentencePiece() {
        val (tts, _, g) = load()
        tts.use {
            val j = JSONObject(File(g, "text.json").readText())
            val bad = ArrayList<String>()
            val toks = j.getJSONArray("tokens")
            for (i in 0 until toks.length()) {
                val e = toks.getJSONObject(i)
                val text = e.getString("text")
                val want = ints(e.getJSONArray("ids"))
                val got = it.tokenizer.encode(text)
                if (!got.contentEquals(want)) bad += "ids for «$text»: want ${want.toList()} got ${got.toList()}"
                if (it.tokenizer.decode(want.toList()) != e.getString("decoded")) bad += "decode of «$text»: want «${e.getString("decoded")}» got «${it.tokenizer.decode(want.toList())}»"
                val half = it.tokenizer.decode(want.take(want.size / 2))
                if (half != e.getString("half_decoded")) bad += "half decode of «$text»: want «${e.getString("half_decoded")}» got «$half»"
            }
            val b = j.getJSONObject("boundaries")
            assertContentEquals(ints(b.getJSONArray("eos")), it.tokenizer.encode(".!...?"))
            assertContentEquals(ints(b.getJSONArray("fallback")), it.tokenizer.encode(",;:"))
            println("TOKENIZER checked ${toks.length()} texts, ${bad.size} mismatches")
            assertTrue(bad.isEmpty(), bad.joinToString("\n"))
        }
    }

    @Test fun textPreparationAndChunkingMatch() {
        val (tts, _, g) = load()
        tts.use {
            val arr = JSONObject(File(g, "text.json").readText()).getJSONArray("prepared")
            val bad = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                val text = e.getString("text")
                val p = it.text.prepare(text)
                if (p.text != e.getString("prepared") || p.framesAfterEosGuess != e.getInt("guess")) bad += "prepare «$text»: want «${e.getString("prepared")}»/${e.getInt("guess")} got «${p.text}»/${p.framesAfterEosGuess}"
                val chunks = e.getJSONArray("chunks").let { c -> List(c.length()) { k -> c.getString(k) } }
                if (it.text.splitIntoChunks(text) != chunks) bad += "chunks «$text»: want $chunks got ${it.text.splitIntoChunks(text)}"
                if (!it.text.tokenize(text).contentEquals(LongArray(e.getJSONArray("tokenized").length()) { k -> e.getJSONArray("tokenized").getLong(k) })) bad += "tokenize «$text»"
            }
            println("TEXT checked ${arr.length()} texts, ${bad.size} mismatches")
            assertTrue(bad.isEmpty(), bad.joinToString("\n"))
        }
    }

    @Test fun modelsMatchTheReferenceAndSpeechComesOut() {
        val (tts, _, g) = load()
        tts.use {
            val rt = it.runtime
            val model = JSONObject(File(g, "model.json").readText())
            val ref = readWav16(File(g, "ref.wav"))

            // voice encoder
            var t0 = System.nanoTime()
            val enc = rt.encodeVoice(ref)
            val encS = (System.nanoTime() - t0) / 1e9
            val wantEnc = readFloats(File(g, "enc.bin"))
            val dEnc = maxAbs(enc, wantEnc)
            println("ENCODER frames=${enc.size / it.bundle.conditioningDim} maxdiff=$dEnc seconds=${"%.2f".format(encS)}")
            assertTrue(dEnc < 2e-3, "voice embeddings differ by $dEnc")

            // transformer conditioning over five steps (state threading)
            val embedding = VoiceEmbedding(wantEnc, wantEnc.size / it.bundle.conditioningDim)
            t0 = System.nanoTime()
            val voice = it.prepareVoice(embedding)
            println("PREPARE_VOICE seconds=${"%.2f".format((System.nanoTime() - t0) / 1e9)}")
            voice.use { v ->
                val ids = LongArray(model.getJSONArray("token_ids").length()) { k -> model.getJSONArray("token_ids").getLong(k) }
                val steps = model.getJSONArray("steps")
                rt.newSentence(v, ids).use { s ->
                    var worst = 0.0
                    var previous: FloatArray? = null
                    for (i in 0 until steps.length()) {
                        val want = steps.getJSONObject(i)
                        val got = s.step(previous)
                        worst = maxOf(worst, maxAbs(got.conditioning, floats(want.getJSONArray("conditioning"))), abs(got.eosLogit - want.getDouble("eos")))
                        previous = FloatArray(it.bundle.latentDim) { 0.1f }
                    }
                    println("CONDITIONING steps=${steps.length()} maxdiff=$worst")
                    assertTrue(worst < 5e-3, "conditioning differs by $worst")
                }

                // one flow-network step
                val c0 = floats(steps.getJSONObject(0).getJSONArray("conditioning"))
                val flow = rt.flow(c0, 0f, 1f, floats(model.getJSONArray("flow_x")))
                val dFlow = maxAbs(flow, floats(model.getJSONArray("flow_out")))
                println("FLOW maxdiff=$dFlow")
                assertTrue(dFlow < 2e-3, "flow output differs by $dFlow")

                // audio decoder, in chunks of 12 frames, state carried over
                val lat = floats(model.getJSONArray("latents"))
                val frames = lat.size / it.bundle.latentDim
                val parts = ArrayList<FloatArray>()
                rt.newDecoder().use { d ->
                    var i = 0
                    while (i < frames) { val end = minOf(i + 12, frames); parts += d.decode((i until end).map { f -> lat.copyOfRange(f * it.bundle.latentDim, (f + 1) * it.bundle.latentDim) }); i = end }
                }
                val audio = FloatArray(parts.sumOf { p -> p.size }).also { a -> var o = 0; parts.forEach { p -> p.copyInto(a, o); o += p.size } }
                val dDec = maxAbs(audio, readFloats(File(g, "decoded.bin")))
                println("DECODER samples=${audio.size} maxdiff=$dDec")
                assertTrue(dDec < 5e-3, "decoded audio differs by $dDec")

                // the whole thing: speech comes out, at a measured speed
                val text = "The quick brown fox jumps over the lazy dog, and then it rests beside the river."
                val gen = it.generator(seed = 1L)
                gen.generate("Warm up.", v)
                t0 = System.nanoTime()
                val out = gen.generate(text, v)!!
                val secs = (System.nanoTime() - t0) / 1e9
                val dur = out.size.toDouble() / it.bundle.sampleRate
                val rms = sqrt(out.sumOf { x -> (x * x).toDouble() } / out.size)
                println("GENERATE audio=${"%.2f".format(dur)}s took=${"%.2f".format(secs)}s rtf=${"%.2f".format(secs / dur)} rms=${"%.4f".format(rms)} heapMB=${(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1_000_000}")
                assertTrue(out.all { x -> x.isFinite() }, "audio has NaN or infinity")
                assertTrue(dur in 2.0..12.0, "implausible duration $dur")
                assertTrue(rms > 0.003, "audio is silent (rms=$rms)")
            }
        }
    }
}
