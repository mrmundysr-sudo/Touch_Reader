package com.mundymorning.touchreader.core.export

import com.mundymorning.touchreader.core.audio.Synthesizer
import com.mundymorning.touchreader.core.reader.ReaderBook
import java.io.ByteArrayOutputStream

/** Turns PCM into raw AAC-LC frames (the Android MediaCodec encoder; a fake in tests). Must flush everything it was given. */
interface PcmEncoder {
    fun encode(pcm: ShortArray, sampleRate: Int, bitrateKbps: Int): List<ByteArray>
}

data class ExportProgress(
    val chunksDone: Int,
    val chunksTotal: Int,
    val chaptersDone: Int,
    val chaptersTotal: Int,
    /** Seconds of speech produced in this run. */
    val audioSeconds: Double,
    /** Seconds of work spent producing it in this run. */
    val workSeconds: Double,
    /** Estimated seconds left at the current pace, or null until there is a measurement. */
    val etaSeconds: Double?,
    val skippedSentences: Int,
) {
    val rtf: Double get() = if (audioSeconds > 0) workSeconds / audioSeconds else 0.0
}

sealed class RunResult {
    /** Every planned chunk is saved. */
    object Completed : RunResult()
    /** Stopped on request; everything finished so far is kept. */
    object Stopped : RunResult()
    data class Failed(val message: String) : RunResult()
}

/**
 * Renders the pending chunks of a plan, one at a time, saving each as soon as it is finished. Safe to stop at
 * any moment and run again later: it carries on at the first chunk that is not saved.
 */
class ExportEngine(
    private val book: ReaderBook,
    private val plan: List<ChunkSpec>,
    private val settings: ExportSettings,
    private val store: ExportStore,
    private val synthesizer: Synthesizer,
    private val encoder: PcmEncoder,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    fun run(isStopped: () -> Boolean, onProgress: (ExportProgress) -> Unit = {}): RunResult {
        val chapters = plan.map { it.chapter }.distinct()
        val pending = store.pending(plan)
        val charsTotalPending = pending.sumOf { ExportPlanner.chars(book, it).toLong() }
        var charsDone = 0L
        var audioSeconds = 0.0
        var workSeconds = 0.0
        var skipped = 0
        var consecutiveFailures = 0

        fun progress(): ExportProgress {
            val done = plan.count { store.isChunkDone(it) }
            val eta = if (audioSeconds > 0 && charsDone > 0) {
                val secondsPerChar = workSeconds / charsDone
                (charsTotalPending - charsDone) * secondsPerChar
            } else null
            return ExportProgress(done, plan.size, store.doneChapters(plan).size, chapters.size, audioSeconds, workSeconds, eta, skipped)
        }

        onProgress(progress())
        for (spec in pending) {
            if (isStopped()) return RunResult.Stopped
            val t0 = nanoTime()
            val starts = LinkedHashMap<Int, Long>()
            var samples = 0L
            val rate = synthesizer.sampleRate
            val out = ArrayList<ShortArray>()
            for (piece in ExportPlanner.pieces(book, spec)) {
                if (isStopped()) return RunResult.Stopped
                starts[piece.sentence] = samples * 1000 / rate
                val audio = try {
                    synthesizer.synthesize(piece.text, settings.speed) { isStopped() }.also { if (it.isNotEmpty()) consecutiveFailures = 0 }
                } catch (e: Exception) {
                    if (isStopped()) return RunResult.Stopped
                    consecutiveFailures++
                    if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                        return RunResult.Failed("The voice stopped working (${e.message ?: e.javaClass.simpleName}). Everything finished so far is kept.")
                    }
                    skipped++
                    ShortArray(rate / 2) // half a second of quiet in place of the sentence that failed
                }
                if (isStopped()) return RunResult.Stopped
                out += audio; samples += audio.size
                val gap = ShortArray(piece.pauseAfterMs * rate / 1000)
                out += gap; samples += gap.size
            }
            val all = ShortArray(samples.toInt())
            var o = 0
            for (a in out) { a.copyInto(all, o); o += a.size }
            val frames = try { encoder.encode(all, rate, settings.bitrateKbps) } catch (e: Exception) {
                return RunResult.Failed("Could not encode the audio (${e.message ?: e.javaClass.simpleName}).")
            }
            if (isStopped()) return RunResult.Stopped
            val adts = ByteArrayOutputStream()
            Adts.wrap(frames, rate, 1, adts)
            store.commitChunk(spec, adts.toByteArray(), starts)
            store.assembleChapter(plan, spec.chapter)

            workSeconds += (nanoTime() - t0) / 1e9
            audioSeconds += samples.toDouble() / rate
            charsDone += ExportPlanner.chars(book, spec)
            onProgress(progress())
        }
        return RunResult.Completed
    }

    /** Joins the finished chapters into one audiobook file. */
    fun assemble(): java.io.File {
        require(plan.all { store.isChunkDone(it) }) { "Not every chapter is finished yet" }
        val chapters = plan.map { it.chapter }.distinct().sorted()
        chapters.forEach { check(store.assembleChapter(plan, it)) { "Chapter ${it + 1} is incomplete" } }
        val source = AdtsFilesSource(chapters.map { store.chapterAudio(it) })
        val marks = chapters.mapIndexed { i, c -> ChapterMark(book.chapters[c].title.ifBlank { "Chapter ${c + 1}" }, source.startMs(i)) }
        val tmp = java.io.File(store.root, "audiobook.m4b.tmp")
        tmp.outputStream().buffered(1 shl 16).use { M4bWriter.write(it, source, marks, book.title, book.author) }
        store.finalFile.delete()
        check(tmp.renameTo(store.finalFile)) { "Could not save the audiobook file" }
        return store.finalFile
    }

    private companion object { const val MAX_CONSECUTIVE_FAILURES = 3 }
}
