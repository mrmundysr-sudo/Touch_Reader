package com.mundymorning.touchreader.importer

import com.mundymorning.touchreader.core.document.*
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.IOException

/**
 * PDF text extraction through PDFBox-Android (Apache-2.0). Works on PDFs that have a text layer.
 * Scanned (image-only) PDFs are reported as unsupported: no OCR engine is integrated yet.
 */
class PdfParser : DocumentParser {
    override val formatName = "PDF"
    override val extensions = setOf("pdf")

    override fun parse(file: File, fallbackTitle: String, options: CleanupOptions): ParsedDocument {
        val doc = try {
            PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly())
        } catch (e: InvalidPasswordException) {
            throw DocumentParseException("This PDF is password protected.", e)
        } catch (e: IOException) {
            throw DocumentParseException("This PDF could not be read.", e)
        }
        doc.use {
            if (!doc.currentAccessPermission.canExtractContent()) {
                throw DocumentParseException("This PDF does not allow its text to be extracted.")
            }
            val pageCount = doc.numberOfPages
            val stripper = PDFTextStripper()
            var pages = ArrayList<List<String>>(pageCount)
            for (p in 1..pageCount) {
                stripper.startPage = p
                stripper.endPage = p
                pages += stripper.getText(doc).replace("\r", "").split('\n').map { it.trim() }
            }
            val chars = pages.sumOf { pg -> pg.sumOf { it.length } }
            if (pageCount == 0 || chars < 25 * pageCount) {
                throw NoTextLayerException(
                    "This PDF looks like scanned images with no text layer. Touch Reader can't read these yet because offline OCR isn't built in."
                )
            }
            if (options.removeHeadersAndFooters) pages = ArrayList(TextCleanup.stripHeadersAndFooters(pages))

            val ranges = outlineRanges(doc, pageCount) ?: chunkRanges(pageCount)
            val chapters = ranges.mapNotNull { (title, from, to) ->
                val lines = (from..to).flatMap { pages[it] }
                val paragraphs = TextCleanup.paragraphsFromLines(lines, options)
                if (paragraphs.isEmpty()) null else Chapter(title, paragraphs.map { Block(it) })
            }
            if (chapters.isEmpty()) throw NoTextLayerException("No readable text was found in this PDF.")
            val title = doc.documentInformation?.title?.trim()?.takeIf { it.isNotEmpty() } ?: titleFromFileName(fallbackTitle)
            return ParsedDocument(title, doc.documentInformation?.author?.trim()?.ifEmpty { null }, chapters)
        }
    }

    private data class Range(val title: String, val from: Int, val to: Int)

    private fun titleFromFileName(name: String) = name.substringBeforeLast('.').replace('_', ' ').trim().ifEmpty { "Untitled" }

    /** Chapters from the PDF's own bookmarks (top level), if it has at least two usable ones. */
    private fun outlineRanges(doc: PDDocument, pageCount: Int): List<Range>? = try {
        val outline = doc.documentCatalog.documentOutline ?: return null
        val starts = mutableListOf<Pair<String, Int>>()
        var item = outline.firstChild
        while (item != null) {
            val page = item.findDestinationPage(doc)
            val idx = page?.let { doc.pages.indexOf(it) } ?: -1
            if (idx >= 0 && !item.title.isNullOrBlank()) starts += item.title.trim() to idx
            item = item.nextSibling
        }
        val sorted = starts.sortedBy { it.second }.distinctBy { it.second }
        if (sorted.size < 2) null else {
            val out = mutableListOf<Range>()
            if (sorted.first().second > 0) out += Range("Front matter", 0, sorted.first().second - 1)
            sorted.forEachIndexed { i, (t, s) ->
                val end = if (i + 1 < sorted.size) sorted[i + 1].second - 1 else pageCount - 1
                out += Range(t, s, maxOf(s, end))
            }
            out
        }
    } catch (e: Exception) { null }

    private fun chunkRanges(pageCount: Int): List<Range> {
        val size = 10
        return (0 until pageCount step size).map { s ->
            val e = minOf(s + size, pageCount) - 1
            Range(if (s == e) "Page ${s + 1}" else "Pages ${s + 1}–${e + 1}", s, e)
        }
    }
}
