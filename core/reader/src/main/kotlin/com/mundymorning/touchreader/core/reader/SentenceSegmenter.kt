package com.mundymorning.touchreader.core.reader

import java.text.BreakIterator
import java.util.Locale

/** A sentence inside a block, addressed by character range so the UI can highlight it. */
data class Sentence(val block: Int, val start: Int, val end: Int)

object SentenceSegmenter {
    /** Speech engines choke on very long inputs; sentences longer than this are split at clause breaks. */
    const val MAX_SENTENCE_CHARS = 380

    fun segment(text: String, block: Int, locale: Locale = Locale.ENGLISH): List<Sentence> {
        val out = mutableListOf<Sentence>()
        val it = BreakIterator.getSentenceInstance(locale)
        it.setText(text)
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            addTrimmed(text, block, start, end, out)
            start = end
            end = it.next()
        }
        return out
    }

    private fun addTrimmed(text: String, block: Int, s: Int, e: Int, out: MutableList<Sentence>) {
        var a = s
        var b = e
        while (a < b && text[a].isWhitespace()) a++
        while (b > a && text[b - 1].isWhitespace()) b--
        if (a >= b) return
        var cursor = a
        while (b - cursor > MAX_SENTENCE_CHARS) {
            val window = text.substring(cursor, cursor + MAX_SENTENCE_CHARS)
            var cut = maxOf(window.lastIndexOf(", "), window.lastIndexOf("; "), window.lastIndexOf(": "), window.lastIndexOf(" — "))
            cut = if (cut > MAX_SENTENCE_CHARS / 3) cut + 1 else window.lastIndexOf(' ').takeIf { it > 0 } ?: (MAX_SENTENCE_CHARS - 1)
            out += Sentence(block, cursor, cursor + cut)
            cursor += cut
            while (cursor < b && text[cursor].isWhitespace()) cursor++
        }
        if (cursor < b) out += Sentence(block, cursor, b)
    }
}
