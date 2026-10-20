package com.mundymorning.touchreader.core.document

import java.io.File

class TxtParser : DocumentParser {
    override val formatName = "TXT"
    override val extensions = setOf("txt", "text")

    override fun parse(file: File, fallbackTitle: String, options: CleanupOptions): ParsedDocument {
        val text = TextUtil.readText(file).replace("\r\n", "\n").replace('\r', '\n')
        val blocks = paragraphsFromText(text, options)
        if (blocks.isEmpty()) throw DocumentParseException("The text file is empty.")
        val chapters = TextUtil.chaptersFromBlocks(blocks, splitLevel = 1, firstTitle = "Beginning")
            .ifEmpty { listOf(Chapter(fallbackTitle, blocks)) }
        return ParsedDocument(titleFromFileName(fallbackTitle), null, chapters)
    }

    internal fun paragraphsFromText(text: String, options: CleanupOptions): List<Block> {
        val out = mutableListOf<Block>()
        val lines = mutableListOf<String>()
        fun flush() {
            if (lines.isEmpty()) return
            val joined = if (options.unwrapLines) unwrap(lines, options.joinHyphenatedWords) else lines.joinToString(" ")
            val clean = TextUtil.collapseSpaces(joined)
            if (clean.isNotEmpty()) {
                out += if (TextUtil.looksLikeChapterHeading(clean)) Block(clean, 1) else Block(clean)
            }
            lines.clear()
        }
        for (raw in text.split('\n')) {
            val line = raw.trim()
            if (line.isEmpty()) flush()
            else if (TextUtil.looksLikeChapterHeading(line)) { flush(); lines += line; flush() }
            else lines += line
        }
        flush()
        return out
    }

    private fun unwrap(lines: List<String>, joinHyphens: Boolean): String {
        val sb = StringBuilder()
        for ((i, l) in lines.withIndex()) {
            if (i > 0) {
                if (joinHyphens && sb.endsWith("-") && sb.length > 1 && sb[sb.length - 2].isLetter() && l.firstOrNull()?.isLowerCase() == true) {
                    sb.setLength(sb.length - 1)
                } else sb.append(' ')
            }
            sb.append(l)
        }
        return sb.toString()
    }
}
