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

    private fun model(g: File) = JSONObject(File(g, "model.json").readText())

    @Test fun voiceEncoderMatches() {
        val (tts, _, g) = load()
        tts.use {
            val t0 = System.nanoTime()
            val enc = it.runtime.encodeVoice(readWav16(File(g, "ref.wav")))
            val d = maxAbs(enc, readFloats(File(g, "enc.bin")))
            println("ENCODER frames=${enc.size / it.bundle.conditioningDim} maxdiff=$d seconds=${"%.2f".format((System.nanoTime() - t0) / 1e9)}")
            assertTrue(d < 2e-3, "voice embeddings differ by $d")
        }
    }

    @Test fun encodingInPiecesMatchesEncodingAtOnce() {
        val (tts, _, g) = load()
        tts.use {
            val ref = readWav16(File(g, "ref.wav"))
            val n = 24000 * 25
            val long = FloatArray(n) { i -> ref[i % ref.size] }
            val whole = it.runtime.encodeVoice(long)
            val pieces = it.encodeVoice(long, 25f).data
            assertEquals(whole.size, pieces.size, "frame counts differ")
            var dot = 0.0; var a = 0.0; var b = 0.0
            for (i in whole.indices) { dot += whole[i] * pieces[i]; a += whole[i] * whole[i]; b += pieces[i] * pieces[i] }
            val cos = dot / Math.sqrt(a * b)
            println("ENCODER_PIECES frames=${whole.size / it.bundle.conditioningDim} cosine=$cos maxdiff=${maxAbs(whole, pieces)}")
            assertTrue(cos > 0.98, "pieces differ from the whole: cosine $cos")
        }
    }

    @Test fun textConditionerMatches() {
        val (tts, _, g) = load()
        tts.use {
            val m = model(g)
            val ids = LongArray(m.getJSONArray("token_ids").length()) { k -> m.getJSONArray("token_ids").getLong(k) }
            val d = maxAbs((it.runtime as OrtPocketRuntime).textEmbeddings(ids), floats(m.getJSONArray("text_emb")))
            println("TEXT_EMBEDDINGS maxdiff=$d")
            assertTrue(d < 2e-3, "text embeddings differ by $d")
        }
    }

    @Test fun transformerConditioningMatches() {
        val (tts, _, g) = load()
        tts.use {
            val m = model(g)
            val wantEnc = readFloats(File(g, "enc.bin"))
            val voice = it.prepareVoice(VoiceEmbedding(wantEnc, wantEnc.size / it.bundle.conditioningDim))
            voice.use { v ->
                // straight after the voice, before any text
                val ids = LongArray(m.getJSONArray("token_ids").length()) { k -> m.getJSONArray("token_ids").getLong(k) }
                val steps = m.getJSONArray("steps")
                var worst = 0.0
                var refMag = 1e-9
                it.runtime.newSentence(v, ids).use { s ->
                    var previous: FloatArray? = null
                    for (i in 0 until steps.length()) {
                        val want = steps.getJSONObject(i)
                        val got = s.step(previous)
                        val wc = floats(want.getJSONArray("conditioning"))
                        val dc = maxAbs(got.conditioning, wc)
                        val de = abs(got.eosLogit - want.getDouble("eos"))
                        val mag = wc.maxOf { x -> abs(x) }
                        println("COND_STEP $i condDiff=$dc eosDiff=$de eos=${got.eosLogit} (want ${want.getDouble("eos")}) condMaxAbs=$mag")
                        worst = maxOf(worst, dc, de); refMag = maxOf(refMag, mag.toDouble())
                        previous = FloatArray(it.bundle.latentDim) { 0.1f }
                    }
                }
                println("CONDITIONING steps=${steps.length()} maxdiff=$worst")
                println("CONDITIONING_REL worst=$worst relative=${worst / refMag}")
                assertTrue(worst < 0.08 * refMag, "conditioning differs by $worst (reference magnitude $refMag)")
            }
        }
    }

    @Test fun flowNetworkMatches() {
        val (tts, _, g) = load()
        tts.use {
            val m = model(g)
            val c0 = floats(m.getJSONArray("steps").getJSONObject(0).getJSONArray("conditioning"))
            val d = maxAbs(it.runtime.flow(c0, 0f, 1f, floats(m.getJSONArray("flow_x"))), floats(m.getJSONArray("flow_out")))
            println("FLOW maxdiff=$d")
            assertTrue(d < 2e-3, "flow output differs by $d")
        }
    }

    @Test fun audioDecoderMatches() {
        val (tts, _, g) = load()
        tts.use {
            val lat = floats(model(g).getJSONArray("latents"))
            val dim = it.bundle.latentDim
            val frames = lat.size / dim
            val parts = ArrayList<FloatArray>()
            it.runtime.newDecoder().use { d ->
                var i = 0
                while (i < frames) { val end = minOf(i + 12, frames); parts += d.decode((i until end).map { f -> lat.copyOfRange(f * dim, (f + 1) * dim) }); i = end }
            }
            val audio = FloatArray(parts.sumOf { p -> p.size }).also { a -> var o = 0; parts.forEach { p -> p.copyInto(a, o); o += p.size } }
            val d = maxAbs(audio, readFloats(File(g, "decoded.bin")))
            // random latents decode to near-silence, so this only guards against gross errors; the real check is the CI transcription of generated speech
            val want = readFloats(File(g, "decoded.bin"))
            val rms = sqrt(want.sumOf { x -> (x * x).toDouble() } / want.size)
            println("DECODER samples=${audio.size} maxdiff=$d referenceRms=$rms relativeToRms=${d / rms}")
            assertTrue(d < 1.0 * rms,  "decoded audio differs by $d (reference rms $rms)")
        }
    }

    @Test fun speechComesOut() {
        val (tts, _, g) = load()
        tts.use {
            val wantEnc = readFloats(File(g, "enc.bin"))
            val voice = it.prepareVoice(VoiceEmbedding(wantEnc, wantEnc.size / it.bundle.conditioningDim))
            voice.use { v ->
                val gen = it.generator(seed = 1L)
                gen.generate("Warm up.", v)
                val text = "The quick brown fox jumps over the lazy dog, and then it rests beside the river."
                val t0 = System.nanoTime()
                val out = gen.generate(text, v)!!
                val secs = (System.nanoTime() - t0) / 1e9
                val dur = out.size.toDouble() / it.bundle.sampleRate
                val rms = sqrt(out.sumOf { x -> (x * x).toDouble() } / out.size)
                File(g, "kotlin_out.wav").writeBytes(wav16(out, it.bundle.sampleRate))
                println("GENERATE audio=${"%.2f".format(dur)}s took=${"%.2f".format(secs)}s rtf=${"%.2f".format(secs / dur)} rms=${"%.4f".format(rms)} heapMB=${(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1_000_000}")
                assertTrue(out.all { x -> x.isFinite() }, "audio has NaN or infinity")
                assertTrue(dur in 2.0..12.0, "implausible duration $dur")
                assertTrue(rms > 0.003, "audio is silent (rms=$rms)")
            }
        }
    }

    private fun wav16(samples: FloatArray, sr: Int): ByteArray {
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { x -> data.putShort((x * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()) }
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + data.capacity()).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(sr).putInt(sr * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(data.capacity())
        return h.array() + data.array()
    }
}
