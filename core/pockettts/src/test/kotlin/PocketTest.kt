import com.mundymorning.touchreader.core.pockettts.*
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.test.*

/** Builds a SentencePiece .model file (protobuf) in memory. */
private object ProtoBuilder {
    private fun varint(v: Long, out: ByteArrayOutputStream) { var x = v; while (x >= 0x80) { out.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 }; out.write(x.toInt()) }
    private fun field(num: Int, wire: Int, out: ByteArrayOutputStream) = varint(((num shl 3) or wire).toLong(), out)
    private fun bytes(num: Int, data: ByteArray, out: ByteArrayOutputStream) { field(num, 2, out); varint(data.size.toLong(), out); out.write(data) }

    fun model(pieces: List<Triple<String, Float, Int>>, modelType: Int = 1, addDummyPrefix: Boolean = true, charsmap: ByteArray? = null): ByteArray {
        val out = ByteArrayOutputStream()
        for ((p, s, t) in pieces) {
            val b = ByteArrayOutputStream()
            bytes(1, p.toByteArray(), b)
            field(2, 5, b); val bits = java.lang.Float.floatToIntBits(s); for (k in 0..3) b.write((bits shr (8 * k)) and 0xFF)
            field(3, 0, b); varint(t.toLong(), b)
            bytes(1, b.toByteArray(), out)
        }
        val trainer = ByteArrayOutputStream().also { field(3, 0, it); varint(modelType.toLong(), it) }
        bytes(2, trainer.toByteArray(), out)
        val norm = ByteArrayOutputStream()
        if (charsmap != null) bytes(2, charsmap, norm)
        field(3, 0, norm); varint(if (addDummyPrefix) 1 else 0, norm)
        bytes(3, norm.toByteArray(), out)
        return out.toByteArray()
    }
}

private fun pieceList(normal: List<Pair<String, Float>>): List<Triple<String, Float, Int>> =
    listOf(Triple("<unk>", 0f, 2), Triple("<s>", 0f, 3), Triple("</s>", 0f, 3)) +
        (0..255).map { Triple("<0x%02X>".format(it), 0f, 6) } +
        normal.map { Triple(it.first, it.second, 1) }

/** Every printable ASCII character is a piece, plus a few words that score better than their letters. */
private fun charModel(extra: List<Pair<String, Float>> = emptyList()): SentencePieceModel {
    val ws = '▁'
    val chars = (32..126).map { it.toChar() }.map { if (it == ' ') ws.toString() else it.toString() }.distinct().map { it to -5f }
    return SentencePieceModel.parse(ProtoBuilder.model(pieceList(chars + extra)))
}

class SentencePieceTest {
    private val ws = '▁'

    @Test fun prefersTheBetterScoringSegmentation() {
        val m = charModel(listOf("${ws}hello" to -1f, "${ws}he" to -3f, "llo" to -3f))
        val ids = m.encode("hello")
        assertEquals(1, ids.size)
        assertEquals("hello", m.decode(ids.toList()))
    }

    @Test fun addsTheDummyPrefixAndEscapesSpaces() {
        val m = charModel(listOf("${ws}a" to -1f))
        val ids = m.encode("a b")
        assertEquals(listOf(m.idOf("${ws}a")!!, m.idOf("${ws}")!!, m.idOf("b")!!), ids.toList())
        assertEquals("a b", m.decode(ids.toList()))
    }

    @Test fun unknownCharactersFallBackToUtf8Bytes() {
        val m = charModel()
        val ids = m.encode("aé")
        // ▁, a, then the two UTF-8 bytes of é (C3 A9)
        assertEquals(4, ids.size)
        assertEquals("aé", m.decode(ids.toList()))
        val emoji = m.encode("😀")
        assertEquals(1 + 4, emoji.size) // ▁ + four bytes
        assertEquals("😀", m.decode(emoji.toList()))
    }

    @Test fun emptyTextAndControlPiecesDecodeToNothing() {
        val m = charModel()
        assertEquals(0, m.encode("").size)
        assertEquals("", m.decode(listOf(1, 2)))
    }

    @Test fun roundTripsPlainText() {
        val m = charModel(listOf("${ws}the" to -2f, "ing" to -2f))
        for (s in listOf("The quick brown fox, jumping over the lazy dog!", "  two  spaces", "numbers 123 and symbols #%&")) assertEquals(s, m.decode(m.encode(s).toList()))
    }

    @Test fun refusesModelsItCannotHandleExactly() {
        assertFailsWith<IllegalArgumentException> { SentencePieceModel.parse(ProtoBuilder.model(pieceList(listOf("a" to -1f)), modelType = 2)) }
        assertFailsWith<IllegalArgumentException> { SentencePieceModel.parse(ProtoBuilder.model(pieceList(listOf("a" to -1f)), charsmap = ByteArray(8) { 1 })) }
        assertFailsWith<IllegalArgumentException> { SentencePieceModel.parse(ByteArray(0)) }
    }
}

private fun testBundle(maxTokens: Int = 50, recommended: Int? = null, padShort: Boolean = false, removeSemicolons: Boolean = false) = PocketBundle(
    sampleRate = 24000, frameRate = 12.5, samplesPerFrame = 1920, latentDim = 4, conditioningDim = 3, maxTokensPerChunk = maxTokens,
    insertBosBeforeVoice = true, padWithSpacesForShortInputs = padShort, removeSemicolons = removeSemicolons, recommendedFramesAfterEos = recommended,
    tokenizerFile = "tokenizer.model", bosBeforeVoiceFile = "bos.npy", flowState = emptyList(), mimiState = emptyList(),
)

class BundleAndNpyTest {
    @Test fun parsesBundleJson() {
        val json = """{"bos_before_voice_file":"bos.npy","bundle_name":"x","conditioning_dim":1024,"frame_rate":12.5,"insert_bos_before_voice":true,
            "latent_dim":32,"max_token_per_chunk":50,"model_recommended_frames_after_eos":null,"pad_with_spaces_for_short_inputs":false,"remove_semicolons":false,
            "sample_rate":24000,"samples_per_frame":1920,"tokenizer_file":"tokenizer.model",
            "flow_lm_state_manifest":[{"dtype":"float32","fill":"nan","index":0,"input_name":"state_0","key":"cache","module":"m","output_name":"out_state_0","path":"m/cache","shape":[2,1,1000,16,64]},
                                      {"dtype":"float32","fill":"empty","index":1,"input_name":"state_1","key":"current_end","module":"m","output_name":"out_state_1","path":"m/x","shape":[0]}],
            "mimi_state_manifest":[{"dtype":"bool","fill":"ones","index":0,"input_name":"state_0","key":"first","module":"d","output_name":"out_state_0","path":"d/first","shape":[1]}]}"""
        val b = PocketBundle.parse(json)
        assertEquals(24000, b.sampleRate); assertEquals(1920, b.samplesPerFrame); assertEquals(32, b.latentDim); assertNull(b.recommendedFramesAfterEos)
        assertEquals(2 * 1000 * 16 * 64L, b.flowState[0].elements); assertEquals(0L, b.flowState[1].elements)
        assertEquals("bool", b.mimiState[0].dtype); assertTrue(b.insertBosBeforeVoice)
    }

    @Test fun readsNpyFloat32() {
        val header = "{'descr': '<f4', 'fortran_order': False, 'shape': (1, 1, 3), }"
        val padded = header + " ".repeat(((64 - (10 + header.length + 1) % 64) % 64)) + "\n"
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x93.toByte(), 'N'.code.toByte(), 'U'.code.toByte(), 'M'.code.toByte(), 'P'.code.toByte(), 'Y'.code.toByte(), 1, 0))
        out.write(padded.length and 0xFF); out.write(padded.length shr 8); out.write(padded.toByteArray())
        for (v in floatArrayOf(1.5f, -2f, 0.25f)) { val bits = java.lang.Float.floatToIntBits(v); for (k in 0..3) out.write((bits shr (8 * k)) and 0xFF) }
        val a = Npy.readFloat32(out.toByteArray())
        assertContentEquals(intArrayOf(1, 1, 3), a.shape); assertContentEquals(floatArrayOf(1.5f, -2f, 0.25f), a.data)
        assertFailsWith<IllegalArgumentException> { Npy.readFloat32(ByteArray(20)) }
    }
}

class PocketTextTest {
    private val ws = '▁'
    private val tokenizer = charModel()

    @Test fun preparesTextLikeTheReference() {
        val t = PocketText(tokenizer, testBundle())
        val p = t.prepare("  hello there\nfriend  ")
        assertEquals("Hello there friend.", p.text); assertEquals(3, p.framesAfterEosGuess) // three words: four or fewer -> 3
        assertEquals(1, t.prepare("one two three four five six").framesAfterEosGuess)
        assertEquals("Yes!", t.prepare("yes!").text)
        assertEquals(3, t.prepare("Hi there.").framesAfterEosGuess) // four words or fewer
        assertEquals("A, b c.", PocketText(tokenizer, testBundle(removeSemicolons = true)).prepare("a; b c").text)
        assertTrue(PocketText(tokenizer, testBundle(padShort = true)).prepare("Hi.").text.startsWith(" ".repeat(8)))
        assertFailsWith<IllegalArgumentException> { t.prepare("   ") }
    }

    @Test fun shortTextIsOneChunk() {
        assertEquals(listOf("This is one sentence."), PocketText(tokenizer, testBundle()).splitIntoChunks("This is one sentence."))
    }

    @Test fun longTextIsSplitAtSentenceEndsWithinTheTokenLimit() {
        val t = PocketText(tokenizer, testBundle(maxTokens = 60))
        val text = "The first sentence is here. The second sentence follows it. A third one comes next! And is there a fourth?"
        val chunks = t.splitIntoChunks(text)
        assertTrue(chunks.size >= 2, chunks.toString())
        assertEquals(text.replace(" ", ""), chunks.joinToString("").replace(" ", ""))
        assertTrue(chunks.all { tokenizer.encode(it).size <= 60 + 8 }, chunks.toString())
        assertTrue(chunks.dropLast(1).all { it.last() in ".!?" }, chunks.toString())
    }

    @Test fun anOverlongSentenceIsSplitAtCommas() {
        val t = PocketText(tokenizer, testBundle(maxTokens = 40))
        val chunks = t.splitIntoChunks("One thing, then another thing, and also a third thing, finally the last of them all.")
        assertTrue(chunks.size >= 2, chunks.toString())
    }
}

/** A model that is just bookkeeping: frames are numbered, so the loop's behaviour can be checked exactly. */
private class FakeRuntime(val eosAt: Int? = null, val conditioningDim: Int = 3, val latentDim: Int = 4) : PocketRuntime {
    val stepInputs = ArrayList<FloatArray?>()
    val flowCalls = ArrayList<Triple<Float, Float, FloatArray>>()
    val decodeSizes = ArrayList<Int>()
    val sentences = ArrayList<LongArray>()
    var closedSentences = 0
    var closedDecoders = 0
    var canLink = false
    val startFrames = ArrayList<Int>()
    val madeStates = ArrayList<FakeState>()

    override fun encodeVoice(samples24k: FloatArray) = FloatArray(conditioningDim * 5) { it.toFloat() }
    override fun prepareVoice(embeddings: FloatArray) = object : PocketVoice { override fun close() {} }
    class FakeState(override val frames: Int) : PocketVoice { var closed = false; override fun close() { closed = true } }

    override fun newSentence(voice: PocketVoice, tokenIds: LongArray): PocketSentence {
        sentences += tokenIds
        startFrames += voice.frames
        var n = 0
        return object : PocketSentence {
            override fun detachAsVoice(framesUsed: Int): PocketVoice? = if (!canLink) null else FakeState(framesUsed).also { madeStates += it }
            override fun step(previousLatent: FloatArray?): PocketSentence.Step {
                stepInputs += previousLatent
                val i = n++
                return PocketSentence.Step(FloatArray(conditioningDim) { i.toFloat() }, if (eosAt != null && i >= eosAt) 5f else -10f)
            }
            override fun close() { closedSentences++ }
        }
    }
    override fun flow(conditioning: FloatArray, s: Float, t: Float, x: FloatArray): FloatArray {
        flowCalls += Triple(s, t, x.copyOf()); return FloatArray(x.size) { 1f }
    }
    override fun newDecoder() = object : PocketDecoder {
        override fun decode(latents: List<FloatArray>): FloatArray { decodeSizes += latents.size; return FloatArray(latents.size * 1920) { 0.1f } }
        override fun close() { closedDecoders++ }
    }
    override fun close() {}
}

class PocketLinkingTest {
    private val tokenizer = charModel()
    private val base = object : PocketVoice { override val frames = 100; override fun close() {} }
    private fun gen(rt: FakeRuntime, maxTokens: Int = 40): PocketGenerator {
        val b = testBundle(maxTokens = maxTokens)
        return PocketGenerator(b, PocketText(tokenizer, b), rt, 0f, 1, seed = 1L)
    }
    private val twoSentences = "The first sentence is right here. The second sentence is a bit different."

    @Test fun withoutLinkingEveryChunkStartsFromTheBareVoice() {
        val rt = FakeRuntime(eosAt = 2).also { it.canLink = true }
        val r = gen(rt).generateLinked(twoSentences, base, null, false)!!
        assertNull(r.state)
        assertTrue(rt.sentences.size >= 2)
        assertTrue(rt.startFrames.all { it == 100 }, rt.startFrames.toString())
        assertTrue(rt.madeStates.isEmpty())
    }

    @Test fun withLinkingEachChunkStartsWhereTheLastLeftOffAndTheCallerGetsTheFinalState() {
        val rt = FakeRuntime(eosAt = 2).also { it.canLink = true }
        val r = gen(rt).generateLinked(twoSentences, base, null, true)!!
        assertEquals(100, rt.startFrames[0])
        assertTrue(rt.startFrames[1] > 100, "second chunk continues: ${rt.startFrames}")
        assertTrue(rt.startFrames[1] == 100 + rt.sentences[0].size + 5, "frames = voice + text tokens + spoken frames: ${rt.startFrames}")
        assertNotNull(r.state)
        assertSame(rt.madeStates.last(), r.state)
        // intermediate states were closed, the one handed over was not
        assertTrue(rt.madeStates.dropLast(1).all { it.closed })
        assertFalse((r.state as FakeRuntime.FakeState).closed)
    }

    @Test fun aContinuationFromAnEarlierCallIsUsedAndNotClosedByTheGenerator() {
        val rt = FakeRuntime(eosAt = 2).also { it.canLink = true }
        val earlier = FakeRuntime.FakeState(300)
        gen(rt).generateLinked("Short one.", base, earlier, true)!!
        assertEquals(300, rt.startFrames[0])
        assertFalse(earlier.closed, "the caller owns it")
    }

    @Test fun whenTheMemoryWouldRunOutItStartsAgainFromTheBareVoice() {
        val rt = FakeRuntime(eosAt = 2).also { it.canLink = true }
        val nearlyFull = FakeRuntime.FakeState(890)
        gen(rt).generateLinked("Short one.", base, nearlyFull, true)!!
        assertEquals(100, rt.startFrames[0], rt.startFrames.toString())
    }

    @Test fun aRuntimeThatCannotLinkStillWorks() {
        val rt = FakeRuntime(eosAt = 2)
        val r = gen(rt).generateLinked(twoSentences, base, null, true)!!
        assertNull(r.state)
        assertTrue(r.audio.isNotEmpty())
        assertTrue(rt.startFrames.all { it == 100 })
    }

    @Test fun cancellingFreesTheStatesItMade() {
        val rt = FakeRuntime(eosAt = 2).also { it.canLink = true }
        var polls = 0
        val r = gen(rt).generateLinked(twoSentences, base, null, true) { ++polls > 12 }
        assertNull(r)
        assertTrue(rt.madeStates.all { it.closed }, "nothing left open")
    }
}

class PocketGeneratorTest {
    private val tokenizer = charModel()
    private val voice = object : PocketVoice { override fun close() {} }

    private fun gen(rt: FakeRuntime, bundle: PocketBundle = testBundle(), temperature: Float = 0f, lsd: Int = 1) =
        PocketGenerator(bundle, PocketText(tokenizer, bundle), rt, temperature, lsd, seed = 1L)

    @Test fun stopsAFewFramesAfterTheEndOfSpeechScoreAppears() {
        val rt = FakeRuntime(eosAt = 10)
        val audio = gen(rt).generate("Hello there my good friend.", voice)!!   // 5 words -> guess 1 -> 3 frames after
        // frames 0..(10+3-1) are generated: 13 frames
        assertEquals(13 * 1920, audio.size)
        assertEquals(listOf(12, 1), rt.decodeSizes, "decoded in chunks of 12 frames")
        assertEquals(1, rt.closedSentences); assertEquals(1, rt.closedDecoders)
    }

    @Test fun shortTextsGetMoreFramesAfterTheEnd() {
        val rt = FakeRuntime(eosAt = 4)
        assertEquals((4 + 5) * 1920, gen(rt).generate("Hi there.", voice)!!.size)   // two words -> guess 3 -> 5 frames
    }

    @Test fun aRecommendedValueFromTheModelWins() {
        val rt = FakeRuntime(eosAt = 4)
        assertEquals((4 + 7) * 1920, gen(rt, testBundle(recommended = 7)).generate("Hi there.", voice)!!.size)
    }

    @Test fun withoutAnEndScoreItStopsAtTheLengthEstimate() {
        val rt = FakeRuntime(eosAt = null)
        val text = "Hello there my good friend."
        val tokens = PocketText(tokenizer, testBundle()).tokenize(text).size
        val expected = Math.ceil((tokens / 3.0 + 2.0) * 12.5).toInt()
        assertEquals(expected * 1920, gen(rt).generate(text, voice)!!.size)
    }

    @Test fun eachFrameIsFedTheOneBeforeIt() {
        val rt = FakeRuntime(eosAt = 3)
        gen(rt).generate("Hello there my good friend.", voice)
        assertNull(rt.stepInputs[0], "the first frame starts from nothing")
        for (i in 1 until rt.stepInputs.size) assertContentEquals(FloatArray(4) { 1f }, rt.stepInputs[i], "frame $i gets the previous latent")
    }

    @Test fun theFlowIsIntegratedInEulerSteps() {
        val rt = FakeRuntime(eosAt = 0)
        val audio = gen(rt, lsd = 4).generate("Hello there my good friend.", voice)!!
        assertTrue(audio.isNotEmpty())
        val firstFrame = rt.flowCalls.take(4)
        assertEquals(listOf(0f to 0.25f, 0.25f to 0.5f, 0.5f to 0.75f, 0.75f to 1f), firstFrame.map { it.first to it.second })
        assertContentEquals(floatArrayOf(0f, 0f, 0f, 0f), firstFrame[0].third)           // temperature 0 -> no noise
        assertContentEquals(floatArrayOf(0.25f, 0.25f, 0.25f, 0.25f), firstFrame[1].third) // x + flow * dt
    }

    @Test fun noiseFollowsTheTemperatureAndTheSeed() {
        val a = FakeRuntime(eosAt = 0); gen(a, temperature = 0.7f).generate("Hello there my good friend.", voice)
        val b = FakeRuntime(eosAt = 0); gen(b, temperature = 0.7f).generate("Hello there my good friend.", voice)
        assertContentEquals(a.flowCalls[0].third, b.flowCalls[0].third, "same seed, same noise")
        assertTrue(a.flowCalls[0].third.any { it != 0f })
    }

    @Test fun severalSentencesAreSpokenOneAfterAnother() {
        val rt = FakeRuntime(eosAt = 2)
        val b = testBundle(maxTokens = 40)
        val audio = PocketGenerator(b, PocketText(tokenizer, b), rt, 0f, 1, seed = 1L)
            .generate("The first sentence is right here. The second sentence is a bit different. And a third.", voice)!!
        assertTrue(rt.sentences.size >= 2)
        assertEquals(rt.sentences.size, rt.closedSentences)
        assertTrue(audio.size > 2 * 4 * 1920)
    }

    @Test fun cancellingStopsEarlyAndFreesWhatItUsed() {
        val rt = FakeRuntime(eosAt = null)
        var polls = 0
        val result = gen(rt).generate("Hello there my good friend.", voice) { ++polls > 5 }
        assertNull(result)
        assertEquals(rt.sentences.size, rt.closedSentences)
        assertTrue(rt.decodeSizes.isEmpty())
    }
}

class PocketTtsFacadeTest {
    private val tokenizer = charModel()
    private fun tts(rt: PocketRuntime = FakeRuntime(), bos: FloatArray? = floatArrayOf(9f, 9f, 9f)) = PocketTts.forTesting(testBundle(), tokenizer, rt, bos)

    @Test fun encodesAtMostFifteenSecondsAndRejectsTooShortClips() {
        val seen = ArrayList<Int>()
        val rt = object : PocketRuntime by FakeRuntime() { override fun encodeVoice(samples24k: FloatArray): FloatArray { seen += samples24k.size; return FloatArray(3 * 6) } }
        val e = tts(rt).encodeVoice(FloatArray(24000 * 40))
        assertEquals(listOf(24000 * 15), seen); assertEquals(6, e.frames)
        assertFailsWith<IllegalArgumentException> { tts().encodeVoice(FloatArray(1000)) }
    }

    @Test fun longRecordingsAreEncodedInPiecesAndATinyTailJoinsThePreviousPiece() {
        val seen = ArrayList<Int>()
        val rt = object : PocketRuntime by FakeRuntime() { override fun encodeVoice(samples24k: FloatArray): FloatArray { seen += samples24k.size; return FloatArray(3 * 2) } }
        tts(rt).encodeVoice(FloatArray(24000 * 45), 45f)
        assertEquals(listOf(359040, 359040, 361920), seen) // 15 s pieces of whole frames (1920 samples); the last takes the sliver left over
        seen.clear(); tts(rt).encodeVoice(FloatArray(24000 * 16), 30f)
        assertEquals(listOf(16 * 24000), seen)
        seen.clear(); tts(rt).encodeVoice(FloatArray(24000 * 20), 30f)
        assertEquals(listOf(359040, 20 * 24000 - 359040), seen)
    }

    @Test fun theBeginOfVoiceMarkerIsPlacedBeforeTheRecordingsEmbeddings() {
        var got: FloatArray? = null
        val rt = object : PocketRuntime by FakeRuntime() { override fun prepareVoice(embeddings: FloatArray): PocketVoice { got = embeddings; return object : PocketVoice { override fun close() {} } } }
        tts(rt).prepareVoice(VoiceEmbedding(floatArrayOf(1f, 2f, 3f), 1))
        assertContentEquals(floatArrayOf(9f, 9f, 9f, 1f, 2f, 3f), got)
    }

    @Test fun embeddingsRoundTripThroughBytes() {
        val e = VoiceEmbedding(FloatArray(3 * 4) { it * 0.5f }, 4)
        val d = PocketTts.decodeEmbedding(PocketTts.encodeEmbedding(e), 3)!!
        assertEquals(4, d.frames); assertContentEquals(e.data, d.data)
        assertNull(PocketTts.decodeEmbedding(PocketTts.encodeEmbedding(e), 5), "wrong dimension is rejected")
        assertNull(PocketTts.decodeEmbedding(ByteArray(3), 3))
    }

    @Test fun theSynthesizerGivesSixteenBitAudioAtTwentyFourKilohertz() {
        val t = tts(FakeRuntime(eosAt = 2))
        val synth = t.synthesizer(object : PocketVoice { override fun close() {} }, temperature = { 0f })
        assertEquals(24000, synth.sampleRate)
        val pcm = synth.synthesize("Hello there my good friend.", 1f) { false }
        assertTrue(pcm.isNotEmpty())
        assertTrue(abs(pcm[0].toInt() - (0.1f * 32767).toInt()) <= 1)
        assertEquals(0, synth.synthesize("Hello there my good friend.", 1f) { true }.size)
    }
}

class SynthesizerLinkTest {
    private val tokenizer = charModel()
    private fun tts(rt: PocketRuntime) = PocketTts.forTesting(testBundle(), tokenizer, rt)
    private val voice = object : PocketVoice { override val frames = 50; override fun close() {} }

    @Test fun linkingCarriesOneCallsEndingIntoTheNextUntilReset() {
        val rt = FakeRuntime(eosAt = 2).also { it.canLink = true }
        val synth = tts(rt).synthesizer(voice, link = { true })
        synth.synthesize("First piece here.", 1f) { false }
        synth.synthesize("Second piece here.", 1f) { false }
        assertEquals(50, rt.startFrames[0]); assertTrue(rt.startFrames[1] > 50, rt.startFrames.toString())
        synth.resetContext()
        synth.synthesize("A new paragraph.", 1f) { false }
        assertEquals(50, rt.startFrames[2], "after a reset it starts from the bare voice")
        synth.release()
        assertTrue(rt.madeStates.all { it.closed }, "nothing left open")
    }

    @Test fun withoutLinkingNothingIsCarried() {
        val rt = FakeRuntime(eosAt = 2).also { it.canLink = true }
        val synth = tts(rt).synthesizer(voice)
        synth.synthesize("First piece here.", 1f) { false }
        synth.synthesize("Second piece here.", 1f) { false }
        assertTrue(rt.startFrames.all { it == 50 })
        assertTrue(rt.madeStates.isEmpty())
    }

    @Test fun switchingLinkingOffDropsWhatWasCarried() {
        val rt = FakeRuntime(eosAt = 2).also { it.canLink = true }
        var link = true
        val synth = tts(rt).synthesizer(voice, link = { link })
        synth.synthesize("First piece here.", 1f) { false }
        link = false
        synth.synthesize("Second piece here.", 1f) { false }
        assertTrue(rt.madeStates.all { it.closed })
        assertEquals(50, rt.startFrames[1])
    }

    @Test fun deliveryChoicesMapToIncreasingTemperatures() {
        val t = listOf(PocketTts.Delivery.STEADY, PocketTts.Delivery.NORMAL, PocketTts.Delivery.LIVELY).map { PocketTts.Delivery.temperature(it) }
        assertTrue(t[0] < t[1] && t[1] < t[2], t.toString())
        assertEquals(t[1], PocketTts.Delivery.temperature(99))
    }
}

class SpeechNormalizerTest {
    @Test fun titlesAreWrittenOutSoTheModelDoesNotPauseAtTheFullStop() {
        assertEquals("Mister Holmes and Missus Hudson thanked Doctor Watson.", SpeechNormalizer.normalize("Mr. Holmes and Mrs. Hudson thanked Dr. Watson."))
        assertEquals("Professor Moriarty versus Captain Nemo.", SpeechNormalizer.normalize("Prof. Moriarty vs. Capt. Nemo."))
    }

    @Test fun ordinaryTextIsUntouched() {
        assertEquals("He said hello. Then Dr left. It ended here.", SpeechNormalizer.normalize("He said hello. Then Dr left. It ended here."))
        assertEquals("Mister", SpeechNormalizer.normalize("Mr. Mister").substringBefore(" "))
        assertEquals("Mr.", SpeechNormalizer.normalize("Mr."), "a title with nothing after it stays")
    }
}
