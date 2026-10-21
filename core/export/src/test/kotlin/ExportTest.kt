import com.mundymorning.touchreader.core.audio.Synthesizer
import com.mundymorning.touchreader.core.document.Block
import com.mundymorning.touchreader.core.document.Chapter
import com.mundymorning.touchreader.core.document.ParsedDocument
import com.mundymorning.touchreader.core.export.*
import com.mundymorning.touchreader.core.reader.ReaderBook
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import kotlin.test.*

private fun tmpDir(): File = Files.createTempDirectory("export-test").toFile().also { it.deleteOnExit() }

private fun sampleBook(chapters: Int = 3, sentencesPer: Int = 10): ReaderBook = ReaderBook.from(
    ParsedDocument("A Test Book", "Some Author", (1..chapters).map { c ->
        Chapter(
            "Chapter $c",
            listOf(Block("Chapter $c", 1)) +
                (1..2).map { p -> Block((1..sentencesPer / 2).joinToString(" ") { "This is sentence $it of paragraph $p in chapter $c." }) },
        )
    })
)

/** 0.05 s of silence per character, at 44.1 kHz. */
private class FakeSynth(val failOn: (String) -> Boolean = { false }) : Synthesizer {
    override val sampleRate = 44100
    val calls = ArrayList<String>()
    override fun synthesize(text: String, speed: Float, isCancelled: () -> Boolean): ShortArray {
        calls += text
        if (failOn(text)) error("boom")
        return ShortArray(text.length * 2205) { 1000 }
    }
}

/** One 12-byte "AAC" frame per 1024 samples. */
private class FakeEncoder : PcmEncoder {
    var calls = 0
    override fun encode(pcm: ShortArray, sampleRate: Int, bitrateKbps: Int): List<ByteArray> {
        calls++
        return List((pcm.size + 1023) / 1024) { i -> ByteArray(12) { (i + it).toByte() } }
    }
}

class AdtsTest {
    @Test fun wrapAndScanRoundTrip() {
        val frames = listOf(ByteArray(5) { 1 }, ByteArray(300) { 2 }, ByteArray(1) { 3 })
        val out = ByteArrayOutputStream()
        Adts.wrap(frames, 44100, 1, out)
        val read = ArrayList<ByteArray>()
        val scan = Adts.read(ByteArrayInputStream(out.toByteArray())) { read += it }
        assertEquals(44100, scan.sampleRate); assertEquals(1, scan.channels)
        assertContentEquals(intArrayOf(5, 300, 1), scan.frameSizes)
        assertEquals(frames.size, read.size)
        frames.zip(read).forEach { (a, b) -> assertContentEquals(a, b) }
    }

    @Test fun concatenatedStreamsStayReadable() {
        val a = ByteArrayOutputStream().also { Adts.wrap(listOf(ByteArray(10)), 44100, 1, it) }.toByteArray()
        val b = ByteArrayOutputStream().also { Adts.wrap(listOf(ByteArray(20), ByteArray(30)), 44100, 1, it) }.toByteArray()
        val scan = Adts.read(ByteArrayInputStream(a + b)) { }
        assertContentEquals(intArrayOf(10, 20, 30), scan.frameSizes)
    }

    @Test fun rejectsGarbageAndTruncation() {
        assertFailsWith<java.io.IOException> { Adts.read(ByteArrayInputStream(ByteArray(20) { 7 })) { } }
        val ok = ByteArrayOutputStream().also { Adts.wrap(listOf(ByteArray(50)), 44100, 1, it) }.toByteArray()
        assertFailsWith<java.io.IOException> { Adts.read(ByteArrayInputStream(ok.copyOf(ok.size - 3))) { } }
        assertFailsWith<IllegalArgumentException> { Adts.header(10, 12345, 1) }
    }
}

/** Minimal MP4 reader used to check the structure of what M4bWriter produces. */
private class Mp4(val bytes: ByteArray) {
    class Box(val type: String, val start: Int, val size: Int, val dataStart: Int)

    fun boxes(from: Int = 0, to: Int = bytes.size): List<Box> {
        val out = ArrayList<Box>()
        var p = from
        val bb = ByteBuffer.wrap(bytes)
        while (p + 8 <= to) {
            val size = bb.getInt(p)
            val type = String(bytes, p + 4, 4, Charsets.ISO_8859_1)
            assertTrue(size >= 8 && p + size <= to, "bad box $type at $p size $size")
            out += Box(type, p, size, p + 8)
            p += size
        }
        assertEquals(to, p, "boxes do not tile the parent")
        return out
    }

    fun child(parent: Box, type: String, skip: Int = 0) =
        boxes(parent.dataStart + skip, parent.start + parent.size).first { it.type == type }

    fun path(vararg names: String): Box {
        var b = boxes().first { it.type == names[0] }
        for (n in names.drop(1)) b = child(b, n, if (b.type == "stsd" || b.type == "dref" || b.type == "meta") (if (b.type == "stsd" || b.type == "dref") 8 else 4) else 0)
        return b
    }
}

class M4bWriterTest {
    private fun writeAdts(dir: File, name: String, sizes: List<Int>): File {
        val f = File(dir, name)
        f.outputStream().use { o -> Adts.wrap(sizes.mapIndexed { i, s -> ByteArray(s) { (i * 7 + it).toByte() } }, 44100, 1, o) }
        return f
    }

    @Test fun layoutIsFastStartAndOffsetsPointAtTheAudio() {
        val dir = tmpDir()
        val sizes1 = (1..50).map { 20 + it }
        val sizes2 = (1..30).map { 100 + it }
        val source = AdtsFilesSource(listOf(writeAdts(dir, "a.aac", sizes1), writeAdts(dir, "b.aac", sizes2)))
        val out = ByteArrayOutputStream()
        M4bWriter.write(out, source, listOf(ChapterMark("Intro", 0), ChapterMark("Two é", source.startMs(1))), "My Book", "Me")
        val mp4 = Mp4(out.toByteArray())
        val top = mp4.boxes()
        assertEquals(listOf("ftyp", "moov", "mdat"), top.map { it.type }, "index must come before the audio")
        val ftyp = top[0]
        assertEquals("M4B ", String(mp4.bytes, ftyp.dataStart, 4))

        val stsz = mp4.path("moov", "trak", "mdia", "minf", "stbl", "stsz")
        val bb = ByteBuffer.wrap(mp4.bytes)
        val count = bb.getInt(stsz.dataStart + 8)
        val all = sizes1 + sizes2
        assertEquals(all.size, count)
        assertEquals(all, List(count) { bb.getInt(stsz.dataStart + 12 + 4 * it) })

        // walk chunks via stsc + stco and compare every frame's bytes with the source
        val stco = mp4.path("moov", "trak", "mdia", "minf", "stbl", "stco")
        val chunks = bb.getInt(stco.dataStart + 4)
        val stsc = mp4.path("moov", "trak", "mdia", "minf", "stbl", "stsc")
        val entries = bb.getInt(stsc.dataStart + 4)
        val perChunk = IntArray(chunks)
        val table = List(entries) { Triple(bb.getInt(stsc.dataStart + 8 + 12 * it), bb.getInt(stsc.dataStart + 12 + 12 * it), 0) }
        for (c in 1..chunks) perChunk[c - 1] = table.last { it.first <= c }.second
        assertEquals(all.size, perChunk.sum())
        var frame = 0
        val expected = ArrayList<ByteArray>()
        listOf(sizes1, sizes2).forEach { s -> s.forEachIndexed { i, size -> expected += ByteArray(size) { (i * 7 + it).toByte() } } }
        for (c in 0 until chunks) {
            var off = bb.getInt(stco.dataStart + 8 + 4 * c)
            repeat(perChunk[c]) {
                assertContentEquals(expected[frame], mp4.bytes.copyOfRange(off, off + all[frame]), "frame $frame")
                off += all[frame]; frame++
            }
        }
        assertEquals(all.size, frame)
        // mdat holds exactly the audio
        val mdat = top[2]
        assertEquals(all.sum() + 8, mdat.size)
    }

    @Test fun chaptersAreWritten() {
        val dir = tmpDir()
        val source = AdtsFilesSource(listOf(writeAdts(dir, "a.aac", List(100) { 30 }), writeAdts(dir, "b.aac", List(50) { 30 })))
        val out = ByteArrayOutputStream()
        M4bWriter.write(out, source, listOf(ChapterMark("One", 0), ChapterMark("Zwei", source.startMs(1))), "T", null)
        val mp4 = Mp4(out.toByteArray())
        val chpl = mp4.path("moov", "udta", "chpl")
        val bb = ByteBuffer.wrap(mp4.bytes)
        var p = chpl.dataStart + 8 // version+flags, reserved
        assertEquals(2, bb.get(p).toInt()); p++
        assertEquals(0L, bb.getLong(p)); p += 8
        val l1 = bb.get(p).toInt(); p++
        assertEquals("One", String(mp4.bytes, p, l1)); p += l1
        val start2 = bb.getLong(p) / 10_000; p += 8
        assertEquals(source.startMs(1), start2)
        assertEquals(100L * 1024 * 1000 / 44100, start2)
        val l2 = bb.get(p).toInt(); p++
        assertEquals("Zwei", String(mp4.bytes, p, l2))
    }

    @Test fun refusesToWriteNothing() {
        val dir = tmpDir()
        val empty = File(dir, "e.aac").also { it.writeBytes(ByteArray(0)) }
        assertFailsWith<IllegalArgumentException> { M4bWriter.write(ByteArrayOutputStream(), AdtsFilesSource(listOf(empty)), emptyList(), "T", null) }
    }
}

class PlannerTest {
    @Test fun splitsChaptersIntoBoundedChunksAndAnnouncesTitles() {
        val book = sampleBook(chapters = 2, sentencesPer = 20)
        val s = ExportSettings("M1", 4, 64, listOf(0, 1))
        val plan = ExportPlanner.plan(book, s)
        assertTrue(plan.all { it.last - it.first < ExportPlanner.MAX_SENTENCES })
        for (c in 0..1) {
            val mine = plan.filter { it.chapter == c }
            assertEquals(0, mine.first().first)
            assertEquals(book.chapters[c].sentences.size - 1, mine.last().last)
            mine.zipWithNext().forEach { (a, b) -> assertEquals(a.last + 1, b.first); assertEquals(a.index + 1, b.index) }
            assertTrue(mine.first().withTitle); assertTrue(mine.drop(1).none { it.withTitle })
        }
        assertEquals(plan.map { it.key }.size, plan.map { it.key }.toSet().size)
    }

    @Test fun respectsChapterSelectionAndTitleSwitch() {
        val book = sampleBook(chapters = 3)
        val plan = ExportPlanner.plan(book, ExportSettings("M1", 4, 64, listOf(2), announceChapters = false))
        assertTrue(plan.all { it.chapter == 2 } && plan.none { it.withTitle })
        assertTrue(ExportPlanner.plan(book, ExportSettings("M1", 4, 64, listOf(9))).isEmpty())
    }

    @Test fun paragraphsGetLongerPausesThanSentences() {
        val book = sampleBook(chapters = 1, sentencesPer = 10)
        val spec = ExportPlanner.plan(book, ExportSettings("M1", 4, 64, listOf(0), announceChapters = true)).first()
        val pieces = ExportPlanner.pieces(book, spec)
        assertEquals(-1, pieces.first().sentence); assertEquals(ExportPlanner.TITLE_PAUSE_MS, pieces.first().pauseAfterMs)
        val pauses = pieces.drop(1).map { it.pauseAfterMs }
        assertTrue(ExportPlanner.PARAGRAPH_PAUSE_MS in pauses && ExportPlanner.SENTENCE_PAUSE_MS in pauses)
        assertEquals(book.chapters[0].text(0), pieces[1].text)
    }
}

class EngineTest {
    private fun setup(synth: FakeSynth = FakeSynth(), enc: FakeEncoder = FakeEncoder(), chapters: List<Int> = listOf(0, 1, 2)): Triple<ExportEngine, ExportStore, List<ChunkSpec>> {
        val book = sampleBook()
        val settings = ExportSettings("M1", 4, 64, chapters)
        val plan = ExportPlanner.plan(book, settings)
        val store = ExportStore(tmpDir())
        return Triple(ExportEngine(book, plan, settings, store, synth, enc), store, plan)
    }

    @Test fun rendersEverythingAndJoinsChapters() {
        val synth = FakeSynth(); val enc = FakeEncoder()
        val (engine, store, plan) = setup(synth, enc)
        val last = ArrayList<ExportProgress>()
        assertEquals(RunResult.Completed, engine.run({ false }) { last += it })
        assertTrue(plan.all { store.isChunkDone(it) })
        assertEquals(listOf(0, 1, 2), store.doneChapters(plan))
        assertTrue(File(store.root, "chunks").listFiles().orEmpty().isEmpty(), "chunk files are replaced by chapter files")
        assertEquals(3, File(store.root, "chapters").listFiles { f -> f.name.endsWith(".aac") }!!.size)
        assertEquals(plan.size, last.last().chunksDone); assertEquals(3, last.last().chaptersDone)
        assertTrue(last.last().audioSeconds > 0)
        assertEquals(0L, store.sentenceStartMs(0, -1)) // spoken title starts the chapter
        val s1 = store.sentenceStartMs(0, 0)!!
        assertTrue(s1 > 0 && store.sentenceStartMs(0, 5)!! > s1)
        val dur = store.chapterDurationMs(1)!!
        assertEquals(dur, Adts.scan(store.chapterAudio(1)).durationMs, "recorded duration matches the audio")
    }

    @Test fun stoppingKeepsFinishedChaptersAndResumingDoesNotRepeatWork() {
        val synth = FakeSynth(); val enc = FakeEncoder()
        val (engine, store, plan) = setup(synth, enc)
        var stop = false
        var chunksSeen = 0
        val r1 = engine.run({ stop }) { if (it.chunksDone > chunksSeen) { chunksSeen = it.chunksDone; if (chunksSeen == 3) stop = true } }
        assertEquals(RunResult.Stopped, r1)
        val doneNow = plan.filter { store.isChunkDone(it) }
        assertEquals(3, doneNow.size, "exactly the chunks finished before stopping are kept")
        val encodedFirst = enc.calls

        stop = false
        val synth2 = FakeSynth(); val enc2 = FakeEncoder()
        val book = sampleBook(); val settings = ExportSettings("M1", 4, 64, listOf(0, 1, 2))
        val resumed = ExportEngine(book, plan, settings, ExportStore(store.root), synth2, enc2)
        assertEquals(RunResult.Completed, resumed.run({ false }))
        assertEquals(plan.size - 3, enc2.calls, "only the unfinished chunks are rendered again")
        assertEquals(encodedFirst, 3)
        val spoken = synth2.calls.toSet()
        val alreadyDone = doneNow.flatMap { ExportPlanner.pieces(book, it) }.map { it.text }.toSet()
        assertTrue(alreadyDone.none { it in spoken }, "text of finished chunks is not synthesized again")
        assertEquals(listOf(0, 1, 2), ExportStore(store.root).doneChapters(plan))
    }

    @Test fun stoppingMidChunkDiscardsOnlyThatChunk() {
        val (engine, store, plan) = setup(FakeSynth())
        var polls = 0
        val r = engine.run({ ++polls > 45 }) { }   // the stop flag is checked before and after every sentence
        assertEquals(RunResult.Stopped, r)
        val done = plan.count { store.isChunkDone(it) }
        assertTrue(done in 1 until plan.size, "done=$done")
        assertTrue(File(store.root, "chunks").walkTopDown().none { it.name.endsWith(".tmp") })
        assertEquals(plan[done], store.pending(plan).first(), "the interrupted chunk is the next one to render")
    }

    @Test fun oneBadSentenceBecomesSilenceButARunOfThemStops() {
        val (engine, store, plan) = setup(FakeSynth { it.contains("sentence 3 of paragraph 1 in chapter 1") })
        val prog = ArrayList<ExportProgress>()
        assertEquals(RunResult.Completed, engine.run({ false }) { prog += it })
        assertEquals(1, prog.last().skippedSentences)
        assertTrue(plan.all { store.isChunkDone(it) })

        val (engine2, store2, plan2) = setup(FakeSynth { true })
        val r = engine2.run({ false })
        assertTrue(r is RunResult.Failed)
        assertTrue(plan2.none { store2.isChunkDone(it) })
    }

    @Test fun etaShrinksAsWorkProceeds() {
        var t = 0L
        val book = sampleBook(); val settings = ExportSettings("M1", 4, 64, listOf(0, 1, 2)); val plan = ExportPlanner.plan(book, settings)
        val store = ExportStore(tmpDir())
        val synth = object : Synthesizer {
            override val sampleRate = 44100
            override fun synthesize(text: String, speed: Float, isCancelled: () -> Boolean): ShortArray { t += 1_000_000_000L; return ShortArray(text.length * 2205) }
        }
        val etas = ArrayList<Double>()
        ExportEngine(book, plan, settings, store, synth, FakeEncoder()) { t }.run({ false }) { p -> p.etaSeconds?.let { etas += it } }
        assertTrue(etas.size >= 2)
        assertTrue(etas.zipWithNext().all { (a, b) -> b <= a + 1e-9 }, etas.toString())
        assertTrue(etas.last() < 1e-6)
    }

    @Test fun assemblesAnM4bWithChaptersFromTheFinishedChapters() {
        val (engine, store, plan) = setup()
        engine.run({ false })
        val file = engine.assemble()
        assertTrue(file.isFile && file.length() > 1000)
        val mp4 = Mp4(file.readBytes())
        assertEquals(listOf("ftyp", "moov", "mdat"), mp4.boxes().map { it.type })
        val chpl = mp4.path("moov", "udta", "chpl")
        assertEquals(3, ByteBuffer.wrap(mp4.bytes).get(chpl.dataStart + 8).toInt())
    }

    @Test fun assemblyRefusesAnUnfinishedBook() {
        val (engine, store, _) = setup()
        var stop = false
        engine.run({ stop }) { if (it.chunksDone >= 1) stop = true }
        assertFailsWith<IllegalArgumentException> { engine.assemble() }
    }
}

class StoreTest {
    @Test fun manifestRoundTripAndFingerprintChangesWithText() {
        val store = ExportStore(tmpDir())
        val book = sampleBook()
        val m = ExportManifest(7, "T", null, ExportStore.fingerprint(book), ExportSettings("custom:abc", 8, 48, listOf(0, 2), false, 1.2f), 123L)
        store.writeManifest(m)
        assertEquals(m, store.readManifest())
        assertNotEquals(ExportStore.fingerprint(book), ExportStore.fingerprint(sampleBook(chapters = 2)))
        assertEquals(ExportStore.fingerprint(book), ExportStore.fingerprint(sampleBook()))
    }

    @Test fun pocketSettingsRoundTripAndOldManifestsReadAsOpenvoice() {
        val store = ExportStore(tmpDir())
        val book = sampleBook()
        val m = ExportManifest(7, "T", null, ExportStore.fingerprint(book), ExportSettings("custom:abc", 8, 48, listOf(0), true, 1f, ExportSettings.CLONE_POCKET, 30, 85), 5L)
        store.writeManifest(m)
        assertEquals(m, store.readManifest())
        // a manifest written before these fields existed
        val old = java.io.File(store.root, "manifest.json")
        old.writeText(old.readText().replace(Regex(",\\s*\"cloneEngine\"\\s*:\\s*\"pocket\"|,\\s*\"refStartSeconds\"\\s*:\\s*\\d+|,\\s*\"pacePercent\"\\s*:\\s*\\d+"), ""))
        val back = store.readManifest()!!.settings
        assertEquals(ExportSettings.CLONE_OPENVOICE, back.cloneEngine); assertEquals(0, back.refStartSeconds); assertEquals(100, back.pacePercent)
    }

    @Test fun leftoverTempFilesAreRemovedOnOpen() {
        val dir = tmpDir()
        File(dir, "chunks").mkdirs()
        File(dir, "chunks/c0000_0000.aac.tmp").writeBytes(byteArrayOf(1))
        File(dir, "manifest.json.tmp").writeBytes(byteArrayOf(1))
        ExportStore(dir)
        assertTrue(dir.walkTopDown().none { it.name.endsWith(".tmp") })
    }

    @Test fun readOnlyOpenLeavesRunningFilesAlone() {
        val dir = tmpDir()
        ExportStore(dir).writeManifest(ExportManifest(1, "T", null, "f", ExportSettings("M1", 4, 64, listOf(0)), 0))
        val inProgress = File(dir, "chunks/c0000_0000.aac.tmp").also { it.parentFile.mkdirs(); it.writeBytes(byteArrayOf(1)) }
        ExportStore(dir, cleanTemp = false)
        assertTrue(inProgress.exists())
        val absent = File(tmpDir(), "nothing-here")
        ExportStore(absent, cleanTemp = false); ExportStore(absent)
        assertFalse(absent.exists(), "looking at a book with no audio creates nothing")
    }

    @Test fun unreadableManifestIsNull() {
        val dir = tmpDir()
        val s = ExportStore(dir)
        File(dir, "manifest.json").writeText("{not json")
        assertNull(s.readManifest())
    }

    @Test fun deleteAllRemovesEverything() {
        val dir = File(tmpDir(), "x")
        val s = ExportStore(dir)
        s.writeManifest(ExportManifest(1, "T", null, "f", ExportSettings("M1", 4, 64, listOf(0)), 0))
        assertTrue(s.bytesUsed() > 0)
        s.deleteAll()
        assertFalse(dir.exists())
    }
}

class EstimatesTest {
    @Test fun classifiesSpeed() {
        assertEquals(LiveSpeed.FAST_ENOUGH, SpeedClass.classify(0.5))
        assertEquals(LiveSpeed.MARGINAL, SpeedClass.classify(0.9))
        assertEquals(LiveSpeed.TOO_SLOW, SpeedClass.classify(1.3))
    }

    @Test fun sizeFollowsBitrate() {
        val nineHours = 9 * 3600.0
        val mb = { kbps: Int -> ExportEstimator.sizeBytes(nineHours, kbps) / 1_000_000.0 }
        assertEquals(259.2, mb(64), 0.5); assertEquals(194.4, mb(48), 0.5); assertEquals(388.8, mb(96), 0.5)
    }

    @Test fun renderTimeScalesWithRtf() {
        assertEquals(2 * 3600.0, ExportEstimator.renderSeconds(3600.0, 2.0), 1e-9)
    }

    @Test fun audioSecondsGrowsWithText() {
        val small = ExportEstimator.audioSeconds(sampleBook(1), listOf(0))
        val big = ExportEstimator.audioSeconds(sampleBook(3), listOf(0, 1, 2))
        assertTrue(small > 0 && big > 2.5 * small)
    }

    @Test fun pocketPredictionsAreSeparateFromTheOtherVoiceTimings() {
        val sup = listOf(ExportEstimator.Measurement(8, false, 2.0))
        assertNull(ExportEstimator.predictRtf(sup, 4, true, ExportEstimator.ENGINE_POCKET))
        val k = sup + ExportEstimator.Measurement(4, true, 0.9, ExportEstimator.ENGINE_POCKET)
        assertEquals(0.9, ExportEstimator.predictRtf(k, 4, true, ExportEstimator.ENGINE_POCKET)!!, 1e-9)
        assertEquals(0.9 * ExportEstimator.POCKET_BETTER_OVER_FASTER, ExportEstimator.predictRtf(k, 8, true, ExportEstimator.ENGINE_POCKET)!!, 1e-9)
        // Pocket timings must not leak into the built-in / converted predictions
        assertEquals(2.0, ExportEstimator.predictRtf(k, 8, false)!!, 1e-9)
        assertEquals(2.0 * ExportEstimator.CUSTOM_OVER_BUILTIN, ExportEstimator.predictRtf(k, 8, true)!!, 1e-9)
    }

    @Test fun predictionsUseExactTimingsThenRatios() {
        val k = listOf(ExportEstimator.Measurement(8, false, 2.0))
        assertEquals(2.0, ExportEstimator.predictRtf(k, 8, false)!!, 1e-9)
        assertEquals(2.0 * ExportEstimator.STEPS_4_OVER_8, ExportEstimator.predictRtf(k, 4, false)!!, 1e-9)
        assertEquals(2.0 * ExportEstimator.CUSTOM_OVER_BUILTIN, ExportEstimator.predictRtf(k, 8, true)!!, 1e-9)
        val both = k + ExportEstimator.Measurement(8, true, 3.0)
        assertEquals(3.0, ExportEstimator.predictRtf(both, 8, true)!!, 1e-9)
        assertEquals(3.0 * ExportEstimator.STEPS_4_OVER_8, ExportEstimator.predictRtf(both, 4, true)!!, 1e-9)
        assertNull(ExportEstimator.predictRtf(emptyList(), 4, false))
    }
}

/** Checks the audiobook with a real decoder when ffmpeg is installed (CI runners and most dev machines have it). */
class RealDecoderTest {
    private fun have(cmd: String) = runCatching { ProcessBuilder(cmd, "-version").redirectErrorStream(true).start().also { it.inputStream.readBytes() }.waitFor() == 0 }.getOrDefault(false)

    private fun run(vararg cmd: String): Pair<Int, String> {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        return p.waitFor() to out
    }

    @Test fun ffmpegReadsTheAudiobookItWrote() {
        org.junit.jupiter.api.Assumptions.assumeTrue(have("ffmpeg") && have("ffprobe"), "ffmpeg not installed")
        val dir = tmpDir()
        val a = File(dir, "a.aac"); val b = File(dir, "b.aac")
        for ((f, secs, hz) in listOf(Triple(a, 3, 440), Triple(b, 2, 660))) {
            val (code, log) = run("ffmpeg", "-v", "error", "-f", "lavfi", "-i", "sine=frequency=$hz:duration=$secs:sample_rate=44100", "-ac", "1", "-c:a", "aac", "-b:a", "64k", "-f", "adts", "-y", f.path)
            assertEquals(0, code, log)
        }
        val source = AdtsFilesSource(listOf(a, b))
        val out = File(dir, "book.m4b")
        out.outputStream().use { M4bWriter.write(it, source, listOf(ChapterMark("Opening", 0), ChapterMark("Second part", source.startMs(1))), "Real Test", "A. Writer") }

        val (pc, probe) = run("ffprobe", "-v", "error", "-show_chapters", "-show_format", "-show_streams", "-of", "json", out.path)
        assertEquals(0, pc, probe)
        val j = org.json.JSONObject(probe)
        val stream = j.getJSONArray("streams").getJSONObject(0)
        assertEquals("aac", stream.getString("codec_name")); assertEquals("44100", stream.getString("sample_rate")); assertEquals(1, stream.getInt("channels"))
        val chapters = j.getJSONArray("chapters")
        assertEquals(2, chapters.length())
        assertEquals("Opening", chapters.getJSONObject(0).getJSONObject("tags").getString("title"))
        assertEquals("Second part", chapters.getJSONObject(1).getJSONObject("tags").getString("title"))
        assertEquals(source.startMs(1) / 1000.0, chapters.getJSONObject(1).getString("start_time").toDouble(), 0.01)
        val tags = j.getJSONObject("format").getJSONObject("tags")
        assertEquals("Real Test", tags.getString("title")); assertEquals("A. Writer", tags.getString("artist"))
        assertEquals(5.0, j.getJSONObject("format").getString("duration").toDouble(), 0.2)

        // decode everything: no errors, and the output length matches
        val (dc, dlog) = run("ffmpeg", "-v", "error", "-i", out.path, "-f", "null", "-")
        assertEquals(0, dc, dlog); assertEquals("", dlog.trim(), "decoder reported problems")
        // the ADTS chapter files themselves are valid AAC too
        val (ac, alog) = run("ffmpeg", "-v", "error", "-i", a.path, "-f", "null", "-")
        assertEquals(0, ac, alog); assertEquals("", alog.trim())
    }
}
