package com.mundymorning.touchreader.core.document

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

internal object TextUtil {
    fun readText(file: File): String {
        val bytes = file.readBytes()
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        val start = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) 3 else 0
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, start, bytes.size - start))
                .toString()
        } catch (e: CharacterCodingException) {
            String(bytes, start, bytes.size - start, Charsets.ISO_8859_1)
        }
    }

    fun collapseSpaces(s: String): String =
        s.replace(' ', ' ').replace(Regex("[ \\t\\u200B]+"), " ").trim()

    private val chapterLine = Regex(
        "^(chapter|part|book|prologue|epilogue|preface|introduction|foreword|appendix)\\b.{0,60}$",
        RegexOption.IGNORE_CASE,
    )

    /** Heuristic for plain-text chapter headings such as "Chapter 3: The Storm". */
    fun looksLikeChapterHeading(line: String): Boolean {
        val t = line.trim()
        if (t.length > 70 || t.isEmpty()) return false
        if (t.endsWith(".") && t.length > 30) return false
        return chapterLine.matches(t)
    }

    /** A lone top-level heading is the book title, so chapters are the next level down. */
    fun splitLevelFor(blocks: List<Block>): Int {
        val h1 = blocks.count { it.level == 1 }
        return if (h1 == 1 && blocks.any { it.level == 2 }) 2 else if (h1 >= 1) 1 else 2
    }

    /**
     * Splits a flat block list into chapters at headings of level <= [splitLevel].
     * Content before the first heading becomes a chapter named [firstTitle].
     */
    fun chaptersFromBlocks(blocks: List<Block>, splitLevel: Int, firstTitle: String): List<Chapter> {
        val chapters = mutableListOf<Chapter>()
        var title = firstTitle
        var current = mutableListOf<Block>()
        fun flush() {
            if (current.isNotEmpty()) chapters += Chapter(title, current.toList())
            current = mutableListOf()
        }
        for (b in blocks) {
            if (b.isHeading && b.level <= splitLevel) {
                flush()
                title = b.text
                current += b
            } else current += b
        }
        flush()
        return chapters
    }
}
