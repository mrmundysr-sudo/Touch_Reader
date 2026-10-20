package com.mundymorning.touchreader.core.document

import java.io.File

class MarkdownParser : DocumentParser {
    override val formatName = "Markdown"
    override val extensions = setOf("md", "markdown")

    override fun parse(file: File, fallbackTitle: String, options: CleanupOptions): ParsedDocument {
        val blocks = mutableListOf<Block>()
        val para = StringBuilder()
        fun flush() {
            val t = TextUtil.collapseSpaces(inline(para.toString()))
            if (t.isNotEmpty()) blocks += Block(t)
            para.setLength(0)
        }
        var inFence = false
        for (raw in TextUtil.readText(file).replace("\r\n", "\n").lines()) {
            val line = raw.trim()
            if (line.startsWith("```") || line.startsWith("~~~")) { flush(); inFence = !inFence; continue }
            if (inFence) { if (line.isNotEmpty()) blocks += Block(line); continue }
            val h = Regex("^(#{1,6})\\s+(.*?)\\s*#*$").matchEntire(line)
            when {
                line.isEmpty() -> flush()
                h != null -> { flush(); blocks += Block(TextUtil.collapseSpaces(inline(h.groupValues[2])), h.groupValues[1].length) }
                line.matches(Regex("^([-*_]\\s*){3,}$")) || line.matches(Regex("^[=-]{3,}$")) -> flush()
                Regex("^([-*+]|\\d+[.)])\\s+").containsMatchIn(line) -> {
                    flush()
                    para.append(line.replace(Regex("^([-*+]|\\d+[.)])\\s+"), ""))
                    flush()
                }
                else -> { if (para.isNotEmpty()) para.append(' '); para.append(line.removePrefix(">").trim()) }
            }
        }
        flush()
        if (blocks.isEmpty()) throw DocumentParseException("The Markdown file has no readable text.")
        val h1 = blocks.firstOrNull { it.level == 1 }?.text
        val splitLevel = TextUtil.splitLevelFor(blocks)
        return ParsedDocument(
            h1 ?: titleFromFileName(fallbackTitle), null,
            TextUtil.chaptersFromBlocks(blocks, splitLevel, "Beginning"),
        )
    }

    private fun inline(s: String): String = s
        .replace(Regex("!\\[[^]]*]\\([^)]*\\)"), "")
        .replace(Regex("\\[([^]]*)]\\([^)]*\\)"), "$1")
        .replace(Regex("<[^>]+>"), "")
        .replace(Regex("(\\*\\*|__)(.+?)\\1"), "$2")
        .replace(Regex("(?<![\\w*])[*_](.+?)[*_](?![\\w*])"), "$1")
        .replace("`", "")
}
