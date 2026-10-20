import com.mundymorning.touchreader.core.document.*
import com.mundymorning.touchreader.core.reader.*
import kotlin.test.*

class ReaderBookTest {
    private fun book() = ReaderBook.from(
        ParsedDocument("T", null, listOf(
            Chapter("One", listOf(Block("One", 1), Block("First sentence. Second sentence! Third?"))),
            Chapter("Empty", listOf(Block("   "))),
            Chapter("Two", listOf(Block("Only one here."))),
        ))
    )

    @Test fun segmentsSentencesWithOffsets() {
        val b = book()
        assertEquals(2, b.chapters.size) // empty chapter dropped
        val c = b.chapters[0]
        assertEquals(listOf("One", "First sentence.", "Second sentence!", "Third?"), c.sentences.indices.map { c.text(it) })
    }

    @Test fun longSentencesAreSplit() {
        val long = (1..200).joinToString(" ") { "word$it" } + "."
        val s = SentenceSegmenter.segment(long, 0)
        assertTrue(s.size > 1)
        assertTrue(s.all { it.end - it.start <= SentenceSegmenter.MAX_SENTENCE_CHARS })
        assertEquals(long.replace(" ", ""), s.joinToString("") { long.substring(it.start, it.end) }.replace(" ", ""))
    }

    @Test fun navigationCrossesChapters() {
        val b = book()
        val last = Position(0, 3)
        assertEquals(Position(1, 0), b.next(last))
        assertEquals(last, b.previous(Position(1, 0)))
        assertNull(b.next(Position(1, 0)))
        assertNull(b.previous(Position.START))
    }

    @Test fun chapterNavigation() {
        val b = book()
        assertEquals(Position(1, 0), b.nextChapter(Position(0, 2)))
        assertNull(b.nextChapter(Position(1, 0)))
        assertEquals(Position(0, 0), b.previousChapter(Position(0, 3))) // restarts chapter
        assertEquals(Position(0, 0), b.previousChapter(Position(1, 0)))
        assertNull(b.previousChapter(Position.START))
    }

    @Test fun progressAndClamp() {
        val b = book()
        assertEquals(0f, b.progress(Position.START))
        assertTrue(b.progress(Position(1, 0)) in 0.7f..0.9f)
        assertEquals(Position(1, 0), b.clamp(Position(9, 9)))
    }

    @Test fun sentenceAtOffset() {
        val c = book().chapters[0]
        assertEquals(2, c.sentenceAt(1, c.blocks[1].text.indexOf("Second") + 3))
    }
}
