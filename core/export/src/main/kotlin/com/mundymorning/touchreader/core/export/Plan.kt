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
    /** How a saved voice was made: [CLONE_POCKET] (copies the voice) or [CLONE_OPENVOICE] (the older method, since removed: only read from audiobooks started before). Fixed for the whole book. */
    val cloneEngine: String = CLONE_OPENVOICE,
    /** Pocket voices: which 15 seconds of the saved recording the voice is copied from (start, in seconds). */
    val refStartSeconds: Int = 0,
    /** Pocket voices: 100 = as spoken, lower = slowed down afterwards without changing pitch. */
    val pacePercent: Int = 100,
    /**
     * Speak neighbouring sentences of a paragraph together, with natural pauses, so a story flows instead of sounding like
     * separate sentences. Used for saved voices; books started before this existed keep reading sentence by sentence.
     */
    val flow: Boolean = false,
    /** Saved voices: how much random variation the model adds (0 steady, 1 normal, 2 lively). */
    val delivery: Int = 1,
    /** Saved voices: let each piece start from where the one before it ended (experimental). */
    val link: Boolean = false,
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
class Piece(
    val text: String,
    val pauseAfterMs: Int,
    /** Sentence index in the chapter of the first sentence in [text], or -1 for the spoken title. */
    val sentence: Int,
    /** Every sentence index [text] covers (one unless sentences were joined), and the length of each, to find where each starts in the audio. */
    val members: List<Int> = listOf(sentence),
    val memberChars: List<Int> = listOf(text.length),
    /** The last piece of a paragraph (or the title): what follows is not a continuation of it. */
    val paragraphEnd: Boolean = true,
)

object ExportPlanner {
    const val SENTENCE_PAUSE_MS = 300
    const val PARAGRAPH_PAUSE_MS = 700
    const val TITLE_PAUSE_MS = 900
    const val MAX_SENTENCES = 8
    const val MAX_CHARS = 1200

    /** Flowing narration: sentences of one paragraph are joined into pieces of up to this many characters (the voice model's own limit is about 50 words). */
    const val FLOW_MAX_CHARS = 220
    const val FLOW_SENTENCE_PAUSE_MS = 200
    const val FLOW_PARAGRAPH_PAUSE_MS = 600
    const val FLOW_TITLE_PAUSE_MS = 800

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

    fun pieces(book: ReaderBook, spec: ChunkSpec, flow: Boolean = false): List<Piece> {
        val chapter = book.chapters[spec.chapter]
        val out = ArrayList<Piece>()
        if (spec.withTitle && chapter.title.isNotBlank()) out += Piece(chapter.title.trim(), if (flow) FLOW_TITLE_PAUSE_MS else TITLE_PAUSE_MS, -1)
        fun paragraphEndsAfter(i: Int): Boolean {
            val next = chapter.sentences.getOrNull(i + 1) ?: return false
            return next.block != chapter.sentences[i].block
        }
        var i = spec.first
        while (i <= spec.last) {
            var j = i
            if (flow) {
                var chars = chapter.text(i).length
                while (j < spec.last && chapter.sentences[j + 1].block == chapter.sentences[i].block && chars + 1 + chapter.text(j + 1).length <= FLOW_MAX_CHARS) {
                    j++; chars += 1 + chapter.text(j).length
                }
            }
            val paragraphEnd = paragraphEndsAfter(j)
            val pause = if (flow) (if (paragraphEnd) FLOW_PARAGRAPH_PAUSE_MS else FLOW_SENTENCE_PAUSE_MS) else (if (paragraphEnd) PARAGRAPH_PAUSE_MS else SENTENCE_PAUSE_MS)
            val members = (i..j).toList()
            out += Piece(members.joinToString(" ") { chapter.text(it) }, pause, i, members, members.map { chapter.text(it).length }, paragraphEnd || j == chapter.sentences.lastIndex)
            i = j + 1
        }
        return out
    }

    /** Characters that will be spoken, used to estimate remaining time from how fast the book is going. */
    fun chars(book: ReaderBook, spec: ChunkSpec): Int = pieces(book, spec).sumOf { it.text.length }
}
