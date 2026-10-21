package com.mundymorning.touchreader.core.export

import com.mundymorning.touchreader.core.reader.ReaderBook

/** What the audiobook is made with. Fixed once rendering starts, so a book never mixes voices. */
data class ExportSettings(
    /** Voice name as the app knows it (a built-in preset such as "M1", or "custom:<id>"). */
    val voice: String,
    /** Denoising steps of the neural voice: 4 is faster, 8 is better quality. */
    val steps: Int,
    val bitrateKbps: Int,
    /** Chapter indexes to render, ascending. */
    val chapters: List<Int>,
    /** Speak each chapter's title before its text. */
    val announceChapters: Boolean = true,
    val speed: Float = 1f,
    /** How a saved voice is made: [CLONE_OPENVOICE] (built-in voice with its tone changed) or [CLONE_POCKET] (copies the voice). Fixed for the whole book. */
    val cloneEngine: String = CLONE_OPENVOICE,
    /** Pocket voices: which 15 seconds of the saved recording the voice is copied from (start, in seconds). */
    val refStartSeconds: Int = 0,
    /** Pocket voices: 100 = as spoken, lower = slowed down afterwards without changing pitch. */
    val pacePercent: Int = 100,
) {
    companion object {
        const val CLONE_OPENVOICE = "openvoice"
        const val CLONE_POCKET = "pocket"
    }
}

/** One independently saved piece of the audiobook: a few sentences of one chapter. */
data class ChunkSpec(val chapter: Int, val index: Int, val first: Int, val last: Int, val withTitle: Boolean) {
    val key: String get() = "c%04d_%04d".format(chapter, index)
}

/** One thing to say, and how long to stay quiet after it. */
class Piece(val text: String, val pauseAfterMs: Int, /** Sentence index in the chapter, or -1 for the spoken title. */ val sentence: Int)

object ExportPlanner {
    const val SENTENCE_PAUSE_MS = 300
    const val PARAGRAPH_PAUSE_MS = 700
    const val TITLE_PAUSE_MS = 900
    const val MAX_SENTENCES = 8
    const val MAX_CHARS = 1200

    fun plan(book: ReaderBook, settings: ExportSettings): List<ChunkSpec> {
        val specs = ArrayList<ChunkSpec>()
        for (c in settings.chapters) {
            val chapter = book.chapters.getOrNull(c) ?: continue
            val n = chapter.sentences.size
            if (n == 0) continue
            var first = 0
            var index = 0
            while (first < n) {
                var last = first
                var chars = chapter.text(first).length
                while (last + 1 < n && last + 1 - first < MAX_SENTENCES && chars + chapter.text(last + 1).length <= MAX_CHARS) {
                    last++; chars += chapter.text(last).length
                }
                specs += ChunkSpec(c, index++, first, last, withTitle = settings.announceChapters && first == 0)
                first = last + 1
            }
        }
        return specs
    }

    fun pieces(book: ReaderBook, spec: ChunkSpec): List<Piece> {
        val chapter = book.chapters[spec.chapter]
        val out = ArrayList<Piece>()
        if (spec.withTitle && chapter.title.isNotBlank()) out += Piece(chapter.title.trim(), TITLE_PAUSE_MS, -1)
        for (i in spec.first..spec.last) {
            val nextBlock = chapter.sentences.getOrNull(i + 1)?.block
            val pause = if (nextBlock != null && nextBlock != chapter.sentences[i].block) PARAGRAPH_PAUSE_MS else SENTENCE_PAUSE_MS
            out += Piece(chapter.text(i), pause, i)
        }
        return out
    }

    /** Characters that will be spoken, used to estimate remaining time from how fast the book is going. */
    fun chars(book: ReaderBook, spec: ChunkSpec): Int = pieces(book, spec).sumOf { it.text.length }
}
