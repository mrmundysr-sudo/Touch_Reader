package com.mundymorning.touchreader.core.reader

import com.mundymorning.touchreader.core.document.Block
import com.mundymorning.touchreader.core.document.ParsedDocument
import java.util.Locale

/** Persistable reading location. */
data class Position(val chapter: Int, val sentence: Int) : Comparable<Position> {
    override fun compareTo(other: Position) =
        compareValuesBy(this, other, { it.chapter }, { it.sentence })

    companion object { val START = Position(0, 0) }
}

class ReaderChapter(val title: String, val blocks: List<Block>, val sentences: List<Sentence>) {
    fun text(index: Int): String {
        val s = sentences[index]
        return blocks[s.block].text.substring(s.start, s.end)
    }

    /** Index of the sentence containing [offset] in [block], or the nearest one. */
    fun sentenceAt(block: Int, offset: Int): Int {
        var best = -1
        for ((i, s) in sentences.withIndex()) {
            if (s.block != block) continue
            if (best == -1) best = i
            if (offset >= s.start) best = i else break
        }
        return if (best == -1) 0 else best
    }
}

/** The document prepared for reading and narration: chapters split into sentences, with navigation. */
class ReaderBook(val title: String, val author: String?, val chapters: List<ReaderChapter>) {
    val totalSentences: Int = chapters.sumOf { it.sentences.size }
    private val offsets: IntArray = IntArray(chapters.size).also { arr ->
        var acc = 0
        chapters.forEachIndexed { i, c -> arr[i] = acc; acc += c.sentences.size }
    }

    fun isValid(p: Position) = p.chapter in chapters.indices && p.sentence in chapters[p.chapter].sentences.indices

    fun clamp(p: Position): Position {
        if (chapters.isEmpty()) return Position.START
        val c = p.chapter.coerceIn(chapters.indices)
        val maxS = (chapters[c].sentences.size - 1).coerceAtLeast(0)
        return Position(c, p.sentence.coerceIn(0, maxS))
    }

    fun sentenceText(p: Position): String = chapters[p.chapter].text(p.sentence)

    /** True if the sentence at [p] is the last one of its paragraph (block) or of its chapter. */
    fun endsParagraph(p: Position): Boolean {
        val c = chapters[p.chapter]
        val next = c.sentences.getOrNull(p.sentence + 1) ?: return true
        return next.block != c.sentences[p.sentence].block
    }

    fun next(p: Position): Position? {
        val c = chapters[p.chapter]
        if (p.sentence + 1 < c.sentences.size) return Position(p.chapter, p.sentence + 1)
        for (i in p.chapter + 1 until chapters.size) if (chapters[i].sentences.isNotEmpty()) return Position(i, 0)
        return null
    }

    fun previous(p: Position): Position? {
        if (p.sentence > 0) return Position(p.chapter, p.sentence - 1)
        for (i in p.chapter - 1 downTo 0) {
            if (chapters[i].sentences.isNotEmpty()) return Position(i, chapters[i].sentences.size - 1)
        }
        return null
    }

    fun nextChapter(p: Position): Position? =
        (p.chapter + 1 until chapters.size).firstOrNull { chapters[it].sentences.isNotEmpty() }?.let { Position(it, 0) }

    /** Previous chapter start; if past the first sentences of a chapter, restarts the current chapter first. */
    fun previousChapter(p: Position): Position? {
        if (p.sentence > 2) return Position(p.chapter, 0)
        return (p.chapter - 1 downTo 0).firstOrNull { chapters[it].sentences.isNotEmpty() }?.let { Position(it, 0) }
            ?: if (p.sentence > 0) Position(p.chapter, 0) else null
    }

    /** 0f..1f overall progress through the text. */
    fun progress(p: Position): Float {
        if (totalSentences == 0 || p.chapter !in chapters.indices) return 0f
        return ((offsets[p.chapter] + p.sentence).toFloat() / totalSentences).coerceIn(0f, 1f)
    }

    /** Inverse of [progress]: the position at [fraction] (0..1) of the whole text. */
    fun positionAt(fraction: Float): Position {
        if (totalSentences == 0) return Position.START
        val target = (fraction.coerceIn(0f, 1f) * totalSentences).toInt().coerceAtMost(totalSentences - 1)
        val ch = offsets.indexOfLast { it <= target }.coerceAtLeast(0)
        return Position(ch, (target - offsets[ch]).coerceIn(0, chapters[ch].sentences.size - 1))
    }

    companion object {
        fun from(doc: ParsedDocument, locale: Locale = Locale.ENGLISH): ReaderBook {
            val chapters = doc.chapters.map { ch ->
                val sentences = ch.blocks.flatMapIndexed { i, b -> SentenceSegmenter.segment(b.text, i, locale) }
                ReaderChapter(ch.title, ch.blocks, sentences)
            }.filter { it.sentences.isNotEmpty() }
            return ReaderBook(doc.title, doc.author, chapters)
        }
    }
}
