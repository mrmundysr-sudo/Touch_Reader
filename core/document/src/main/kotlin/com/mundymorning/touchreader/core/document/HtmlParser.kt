package com.mundymorning.touchreader.core.document

import org.jsoup.Jsoup
import java.io.File

class HtmlParser : DocumentParser {
    override val formatName = "HTML"
    override val extensions = setOf("html", "htm", "xhtml")

    override fun parse(file: File, fallbackTitle: String, options: CleanupOptions): ParsedDocument {
        val doc = Jsoup.parse(TextUtil.readText(file))
        val title = doc.title().takeIf { it.isNotBlank() } ?: titleFromFileName(fallbackTitle)
        val blocks = HtmlExtractor.blocks(doc.body())
        if (blocks.isEmpty()) throw DocumentParseException("The HTML file has no readable text.")
        val level = TextUtil.splitLevelFor(blocks)
        return ParsedDocument(title, null, TextUtil.chaptersFromBlocks(blocks, level, title))
    }
}
