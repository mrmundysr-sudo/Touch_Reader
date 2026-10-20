import com.mundymorning.touchreader.core.document.*
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class ParsersTest {
    private val dir = createTempDirectory("tr").toFile()

    private fun file(name: String, text: String) = File(dir, name).also { it.writeText(text) }
    private fun zip(name: String, entries: Map<String, String>) = File(dir, name).also { f ->
        ZipOutputStream(f.outputStream()).use { z ->
            entries.forEach { (k, v) -> z.putNextEntry(ZipEntry(k)); z.write(v.toByteArray()); z.closeEntry() }
        }
    }

    @Test fun txtUnwrapsLinesAndFindsChapters() {
        val f = file("story.txt", "Chapter 1\n\nIt was a dark and\nstormy night. The rain\nfell.\n\nChapter 2\n\nMorning came.")
        val d = TxtParser().parse(f, "story.txt")
        assertEquals(2, d.chapters.size)
        assertEquals("Chapter 1", d.chapters[0].title)
        assertEquals("It was a dark and stormy night. The rain fell.", d.chapters[0].blocks[1].text)
        assertEquals("story", d.title)
    }

    @Test fun txtJoinsHyphenation() {
        val d = TxtParser().parse(file("h.txt", "An extra-\nordinary day."), "h.txt")
        assertEquals("An extraordinary day.", d.chapters[0].blocks[0].text)
    }

    @Test fun txtLatin1Fallback() {
        val f = File(dir, "l.txt").also { it.writeBytes("café au lait".toByteArray(Charsets.ISO_8859_1)) }
        assertEquals("café au lait", TxtParser().parse(f, "l.txt").chapters[0].blocks[0].text)
    }

    @Test fun emptyTxtFails() {
        assertFailsWith<DocumentParseException> { TxtParser().parse(file("e.txt", "  \n "), "e.txt") }
    }

    @Test fun markdownStripsSyntaxAndSplitsOnHeadings() {
        val md = "# Book\n\nIntro with **bold** and [a link](http://x.y).\n\n## One\n\n- item a\n- item b\n\n## Two\n\n```\ncode\n```\nEnd."
        val d = MarkdownParser().parse(file("a.md", md), "a.md")
        assertEquals("Book", d.title)
        assertEquals("Intro with bold and a link.", d.chapters[0].blocks[1].text)
        assertTrue(d.chapters.any { it.title == "Two" })
        assertTrue(d.chapters.flatMap { it.blocks }.none { it.text.contains("**") || it.text.contains("](") })
    }

    @Test fun htmlSkipsScriptsAndReadsHeadings() {
        val html = "<html><head><title>T</title></head><body><script>var x=1</script><h1>Alpha</h1><p>Hello <b>world</b>.</p><h1>Beta</h1><p>Bye.</p></body></html>"
        val d = HtmlParser().parse(file("a.html", html), "a.html")
        assertEquals("T", d.title)
        assertEquals(listOf("Alpha", "Beta"), d.chapters.map { it.title })
        assertEquals("Hello world.", d.chapters[0].blocks[1].text)
    }

    @Test fun docxReadsHeadingsAndText() {
        val xml = """<?xml version="1.0"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
            <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Intro</w:t></w:r></w:p>
            <w:p><w:r><w:t xml:space="preserve">Hello </w:t></w:r><w:r><w:t>there.</w:t></w:r></w:p>
            <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Next</w:t></w:r></w:p>
            <w:p><w:r><w:t>More.</w:t></w:r></w:p></w:body></w:document>"""
        val d = DocxParser().parse(zip("a.docx", mapOf("word/document.xml" to xml)), "a.docx")
        assertEquals(listOf("Intro", "Next"), d.chapters.map { it.title })
        assertEquals("Hello there.", d.chapters[0].blocks[1].text)
    }

    private val container = """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>"""
    private fun opf(spine: String) = """<package xmlns="http://www.idpf.org/2007/opf"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>My Book</dc:title><dc:creator>Ann Author</dc:creator></metadata>
        <manifest><item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/><item id="c2" href="c2.xhtml" media-type="application/xhtml+xml"/><item id="c3" href="c3.xhtml" media-type="application/xhtml+xml"/></manifest>
        <spine>$spine</spine></package>"""

    @Test fun epubFollowsSpineOrderAndMergesContinuations() {
        val e = zip("a.epub", mapOf(
            "META-INF/container.xml" to container,
            "OEBPS/content.opf" to opf("""<itemref idref="c2"/><itemref idref="c1"/><itemref idref="c3"/>"""),
            "OEBPS/c1.xhtml" to "<html><body><h1>Second</h1><p>B text.</p></body></html>",
            "OEBPS/c2.xhtml" to "<html><body><h1>First</h1><p>A text.</p></body></html>",
            "OEBPS/c3.xhtml" to "<html><body><p>Continuation of second.</p></body></html>",
        ))
        val d = EpubParser().parse(e, "a.epub")
        assertEquals("My Book", d.title)
        assertEquals("Ann Author", d.author)
        assertEquals(listOf("First", "Second"), d.chapters.map { it.title })
        assertEquals("Continuation of second.", d.chapters[1].blocks.last().text)
    }

    @Test fun epubWithDrmIsRefused() {
        val e = zip("d.epub", mapOf(
            "META-INF/container.xml" to container,
            "META-INF/encryption.xml" to "<encryption><EncryptedData Id=\"x\"/></encryption>",
            "OEBPS/content.opf" to opf("""<itemref idref="c1"/>"""),
        ))
        val ex = assertFailsWith<DocumentParseException> { EpubParser().parse(e, "d.epub") }
        assertTrue(ex.message!!.contains("DRM"))
    }

    @Test fun corruptEpubGivesFriendlyError() {
        assertFailsWith<DocumentParseException> { EpubParser().parse(file("bad.epub", "not a zip"), "bad.epub") }
    }

    @Test fun registryPicksParserByExtension() {
        val r = DocumentParsers(DocumentParsers.jvmDefaults())
        assertNotNull(r.forFileName("X.EPUB"))
        assertNull(r.forFileName("x.xyz"))
    }

    @Test fun headerFooterRemoval() {
        val words = listOf("apple", "river", "stone", "cloud", "ember", "harbor")
        val pages = (1..6).map { n ->
            val w = words[n - 1]
            listOf("My Book Title", "Opening about the $w today.", "Middle talks of $w again.", "Another row regarding $w.", "Closing words on $w.", "$n")
        }
        val out = TextCleanup.stripHeadersAndFooters(pages)
        assertTrue(out.all { p -> p.size == 4 && p.none { it.contains("My Book") } })
    }

    @Test fun paragraphRebuild() {
        val lines = listOf("This is a long line that wraps around to the next", "line and ends here.", "Short.", "A new paragraph starts with-", "out any problem here at all friend.")
        val p = TextCleanup.paragraphsFromLines(lines)
        assertEquals("This is a long line that wraps around to the next line and ends here.", p[0])
        assertEquals("Short.", p[1])
        assertEquals("A new paragraph starts without any problem here at all friend.", p[2])
    }
}
