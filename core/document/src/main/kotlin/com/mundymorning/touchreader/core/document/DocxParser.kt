package com.mundymorning.touchreader.core.document

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.File
import java.util.zip.ZipFile

/** Reads .docx (Office Open XML) body text, headings and lists in document order. */
class DocxParser : DocumentParser {
    override val formatName = "DOCX"
    override val extensions = setOf("docx")

    override fun parse(file: File, fallbackTitle: String, options: CleanupOptions): ParsedDocument {
        val zip = try { ZipFile(file) } catch (e: Exception) {
            throw DocumentParseException("This Word document could not be opened.", e)
        }
        zip.use {
            fun text(path: String) = zip.getEntry(path)?.let { e -> zip.getInputStream(e).use { s -> String(s.readBytes(), Charsets.UTF_8) } }
            if (zip.getEntry("EncryptedPackage") != null) {
                throw DocumentParseException("This document is password protected.")
            }
            val xml = text("word/document.xml") ?: throw DocumentParseException("Not a valid .docx file.")
            val doc = Jsoup.parse(xml, "", Parser.xmlParser())
            val blocks = mutableListOf<Block>()
            var coreTitle: String? = null
            text("docProps/core.xml")?.let { core ->
                coreTitle = Jsoup.parse(core, "", Parser.xmlParser()).selectFirst("dc|title, title")?.text()?.trim()?.ifEmpty { null }
            }
            for (p in doc.select("w|body w|p")) {
                val t = TextUtil.collapseSpaces(paragraphText(p))
                if (t.isEmpty()) continue
                val style = p.selectFirst("w|pPr > w|pStyle")?.attr("w:val").orEmpty()
                val level = when {
                    style.equals("Title", true) -> 1
                    else -> Regex("heading\\s*(\\d)", RegexOption.IGNORE_CASE).find(style)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 6) ?: 0
                }
                blocks += Block(t, level)
            }
            if (blocks.isEmpty()) throw DocumentParseException("The document has no readable text.")
            val title = coreTitle ?: titleFromFileName(fallbackTitle)
            val splitLevel = TextUtil.splitLevelFor(blocks)
            return ParsedDocument(title, null, TextUtil.chaptersFromBlocks(blocks, splitLevel, title))
        }
    }

    private fun paragraphText(p: Element): String {
        val sb = StringBuilder()
        for (e in p.getAllElements()) {
            when (e.normalName()) {
                "w:t" -> sb.append(e.wholeText())
                "w:tab", "w:br", "w:cr" -> sb.append(' ')
            }
        }
        return sb.toString()
    }
}
