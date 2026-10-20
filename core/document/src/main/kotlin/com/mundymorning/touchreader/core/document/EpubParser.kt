package com.mundymorning.touchreader.core.document

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipFile

/**
 * EPUB 2/3 reader. Follows the OPF spine for reading order. Files that
 * declare encryption (DRM) are refused rather than circumvented.
 */
class EpubParser : DocumentParser {
    override val formatName = "EPUB"
    override val extensions = setOf("epub")

    override fun parse(file: File, fallbackTitle: String, options: CleanupOptions): ParsedDocument {
        val zip = try { ZipFile(file) } catch (e: Exception) {
            throw DocumentParseException("This EPUB could not be opened.", e)
        }
        zip.use {
            fun text(path: String): String? =
                zip.getEntry(path)?.let { e -> zip.getInputStream(e).use { s -> String(s.readBytes(), Charsets.UTF_8) } }

            val encryption = text("META-INF/encryption.xml")
            if (encryption != null && Regex("EncryptedData", RegexOption.IGNORE_CASE).containsMatchIn(encryption) &&
                !encryption.contains("IDPF-font-obfuscation") && !encryption.contains("fontobfuscation")
            ) {
                throw DocumentParseException("This EPUB is protected by DRM. Touch Reader cannot open protected books.")
            }

            val container = text("META-INF/container.xml") ?: throw DocumentParseException("Not a valid EPUB (no container.xml).")
            val opfPath = Jsoup.parse(container, "", Parser.xmlParser()).selectFirst("rootfile")?.attr("full-path")
                ?: throw DocumentParseException("Not a valid EPUB (no package file).")
            val opf = Jsoup.parse(text(opfPath) ?: throw DocumentParseException("EPUB package file is missing."), "", Parser.xmlParser())
            val baseDir = opfPath.substringBeforeLast('/', "")

            val title = opf.selectFirst("metadata > *|title, dc|title, title")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: titleFromFileName(fallbackTitle)
            val author = opf.selectFirst("dc|creator, creator")?.text()?.trim()?.takeIf { it.isNotEmpty() }

            val manifest = opf.select("manifest > item").associate { it.attr("id") to it }
            val tocTitles = tocTitles(opf, manifest.values.toList(), baseDir, ::text)

            val chapters = mutableListOf<Chapter>()
            var n = 0
            for (ref in opf.select("spine > itemref")) {
                val item = manifest[ref.attr("idref")] ?: continue
                val href = URLDecoder.decode(item.attr("href"), "UTF-8").substringBefore('#')
                val path = resolve(baseDir, href)
                val html = text(path) ?: continue
                val blocks = HtmlExtractor.blocks(Jsoup.parse(html).body())
                if (blocks.isEmpty()) continue
                n++
                val heading = blocks.firstOrNull { it.isHeading }?.text
                val chTitle = tocTitles[path] ?: heading
                if (chTitle == null && chapters.isNotEmpty()) {
                    // Continuation file of a long chapter: keep it in the previous chapter.
                    val prev = chapters.removeAt(chapters.lastIndex)
                    chapters += prev.copy(blocks = prev.blocks + blocks)
                } else {
                    chapters += Chapter(chTitle ?: "Section $n", blocks)
                }
            }
            if (chapters.isEmpty()) throw DocumentParseException("No readable text was found in this EPUB.")
            return ParsedDocument(title, author, chapters)
        }
    }

    private fun resolve(baseDir: String, href: String): String {
        val parts = ArrayDeque<String>()
        (if (baseDir.isEmpty()) href else "$baseDir/$href").split('/').forEach {
            when (it) { "", "." -> {} ; ".." -> parts.removeLastOrNull(); else -> parts.addLast(it) }
        }
        return parts.joinToString("/")
    }

    /** Maps spine file path -> title using the EPUB3 nav document or EPUB2 NCX. */
    private fun tocTitles(
        opf: org.jsoup.nodes.Document,
        items: List<org.jsoup.nodes.Element>,
        baseDir: String,
        text: (String) -> String?,
    ): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val nav = items.firstOrNull { it.attr("properties").split(' ').contains("nav") }
        if (nav != null) {
            val navPath = resolve(baseDir, URLDecoder.decode(nav.attr("href"), "UTF-8"))
            val doc = text(navPath)?.let { Jsoup.parse(it) }
            val navDir = navPath.substringBeforeLast('/', "")
            doc?.select("nav[epub:type=toc] a[href], nav#toc a[href]")?.forEach { a ->
                val p = resolve(navDir, URLDecoder.decode(a.attr("href"), "UTF-8").substringBefore('#'))
                val t = TextUtil.collapseSpaces(a.text())
                if (t.isNotEmpty()) result.putIfAbsent(p, t)
            }
        }
        if (result.isEmpty()) {
            val ncx = items.firstOrNull { it.attr("media-type") == "application/x-dtbncx+xml" }
            if (ncx != null) {
                val ncxPath = resolve(baseDir, URLDecoder.decode(ncx.attr("href"), "UTF-8"))
                val ncxDir = ncxPath.substringBeforeLast('/', "")
                text(ncxPath)?.let { Jsoup.parse(it, "", Parser.xmlParser()) }?.select("navPoint")?.forEach { np ->
                    val label = np.selectFirst("navLabel > text")?.text()?.let(TextUtil::collapseSpaces)
                    val src = np.selectFirst("content")?.attr("src")
                    if (!label.isNullOrEmpty() && src != null) {
                        result.putIfAbsent(resolve(ncxDir, URLDecoder.decode(src, "UTF-8").substringBefore('#')), label)
                    }
                }
            }
        }
        return result
    }
}
