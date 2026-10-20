import com.mundymorning.touchreader.core.supertonic.*
import kotlin.test.*

class SupertonicTextTest {
    @Test fun wrapsWithLanguageTagsAndAddsFinalPeriod() {
        assertEquals("<en>Hello world.</en>", SupertonicText.preprocess("Hello world", "en"))
        assertEquals("<en>Really?</en>", SupertonicText.preprocess("Really?", "en"))
    }

    @Test fun normalizesPunctuationSymbolsAndEmoji() {
        assertEquals("<en>It's a \"test\" - ok, fine.</en>", SupertonicText.preprocess("It’s a “test” — ok , fine", "en"))
        assertEquals("<en>Mail me at home.</en>", SupertonicText.preprocess("Mail me @ home 😀", "en"))
        assertEquals("<en>a b.</en>", SupertonicText.preprocess("a_b", "en"))
    }

    @Test fun expandsKnownExpressions() {
        assertEquals("<en>Fruit, for example, apples.</en>", SupertonicText.preprocess("Fruit, e.g., apples", "en"))
    }

    @Test fun rejectsUnknownLanguage() {
        assertFailsWith<IllegalArgumentException> { SupertonicText.preprocess("x", "xx") }
    }

    @Test fun shortTextIsOneChunk() {
        assertEquals(listOf("One. Two."), SupertonicText.chunk("One. Two."))
        assertEquals(listOf(""), SupertonicText.chunk("   "))
    }

    @Test fun longTextIsSplitWithinLimitWithoutLosingWords() {
        val text = (1..60).joinToString(" ") { "Sentence number $it ends here." }
        val chunks = SupertonicText.chunk(text, 100)
        assertTrue(chunks.size > 5)
        assertTrue(chunks.all { it.length <= 100 }, "chunks: ${chunks.map { it.length }}")
        assertEquals(text.replace(Regex("\\s+"), " "), chunks.joinToString(" "))
    }

    @Test fun doesNotSplitAfterAbbreviations() {
        val chunks = SupertonicText.chunk("Dr. Smith went home. " + "Then he slept soundly. ".repeat(10), 60)
        assertTrue(chunks.first().startsWith("Dr. Smith went home."), chunks.first())
    }

    @Test fun hugeSentenceFallsBackToCommasThenWords() {
        val clause = List(40) { "word$it" }.joinToString(" ")
        val chunks = SupertonicText.chunk("$clause, $clause, $clause.", 150)
        assertTrue(chunks.all { it.length <= 150 })
    }

    @Test fun cjkUsesSmallerChunks() {
        assertEquals(120, SupertonicText.maxChunkFor("ja"))
        assertEquals(300, SupertonicText.maxChunkFor("en"))
    }

    @Test fun indexerMapsAndDropsUnknown() {
        val table = LongArray(200) { -1L }.also { it['a'.code] = 5; it['b'.code] = 6 }
        val ix = UnicodeIndexer(table)
        assertContentEquals(longArrayOf(5, 6, 5), ix.encode("abXa中"))
    }
}
