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

    @Test fun positionAtInvertsProgress() {
        val b = book()
        assertEquals(Position.START, b.positionAt(0f))
        assertEquals(Position(1, 0), b.positionAt(b.progress(Position(1, 0))))
        assertEquals(Position(0, 2), b.positionAt(b.progress(Position(0, 2))))
        assertEquals(Position(1, 0), b.positionAt(1f)) // clamps to last sentence
    }
}

class AbbreviationSegmentationTest {
    private fun sentences(t: String) = SentenceSegmenter.segment(t, 0).map { t.substring(it.start, it.end) }

    @Test fun aTitleBeforeANameDoesNotEndTheSentence() {
        assertEquals(listOf("Mr. Holmes sat down.", "Mrs. Hudson brought tea."), sentences("Mr. Holmes sat down. Mrs. Hudson brought tea."))
        assertEquals(listOf("She thanked Dr. Watson warmly.", "He bowed."), sentences("She thanked Dr. Watson warmly. He bowed."))
        assertEquals(listOf("Prof. Moriarty and Capt. Nemo met."), sentences("Prof. Moriarty and Capt. Nemo met."))
    }

    @Test fun realSentenceEndsStillSplit() {
        assertEquals(listOf("It was late.", "He left.", "She stayed."), sentences("It was late. He left. She stayed."))
        assertEquals(listOf("Who is Mr. Smith?", "Nobody knew."), sentences("Who is Mr. Smith? Nobody knew."))
    }

    @Test fun anAbbreviationAtTheVeryEndIsKept() {
        assertEquals(listOf("He called out to Dr."), sentences("He called out to Dr."))
    }

    @Test fun wordsThatMerelyEndInTheLettersAreNotAbbreviations() {
        assertEquals(listOf("I am a Gunner.", "Next."), sentences("I am a Gunner. Next."))
    }
}
