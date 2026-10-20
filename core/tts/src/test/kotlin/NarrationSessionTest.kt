import com.mundymorning.touchreader.core.document.*
import com.mundymorning.touchreader.core.reader.*
import com.mundymorning.touchreader.core.tts.*
import kotlin.test.*

private class FakeEngine : SpeechEngine {
    override var listener: SpeechEngine.Listener? = null
    val queue = ArrayDeque<Pair<Int, String>>()
    val spoken = mutableListOf<String>()
    var stops = 0
    override fun enqueue(id: Int, text: String) { queue.addLast(id to text) }
    override fun stop() { stops++; queue.clear() }
    override fun setSpeed(speed: Float) {}
    override fun release() {}
    /** Simulate the engine speaking the head of the queue to completion. */
    fun speakNext() {
        val (id, text) = queue.first()
        listener?.onStart(id); spoken += text
        queue.removeFirst(); listener?.onDone(id)
    }
}

class NarrationSessionTest {
    private val book = ReaderBook.from(ParsedDocument("T", null, listOf(
        Chapter("A", listOf(Block("S1. S2. S3."))),
        Chapter("B", listOf(Block("S4. S5."))),
    )))

    @Test fun speaksInOrderWithBoundedLookahead() {
        val e = FakeEngine(); val s = NarrationSession(book, e, lookahead = 2)
        s.play()
        assertEquals(2, e.queue.size)
        repeat(5) { assertTrue(e.queue.size <= 2); if (e.queue.isNotEmpty()) e.speakNext() }
        assertEquals(listOf("S1.", "S2.", "S3.", "S4.", "S5."), e.spoken)
        assertEquals(NarrationState.FINISHED, s.state.value)
    }

    @Test fun positionTracksAudibleSentence() {
        val e = FakeEngine(); val s = NarrationSession(book, e)
        s.play(); e.speakNext(); e.speakNext()
        assertEquals(Position(0, 1), s.position.value)
    }

    @Test fun pauseStopsAndResumeRepeatsCurrentSentence() {
        val e = FakeEngine(); val s = NarrationSession(book, e)
        s.play(); e.speakNext()
        val pos = s.position.value
        s.pause()
        assertEquals(NarrationState.PAUSED, s.state.value)
        assertTrue(e.queue.isEmpty())
        // a stale completion after pause must not advance anything
        s.play()
        assertEquals(pos, s.position.value)
        assertEquals("S1.", e.queue.first().second)
    }

    @Test fun seekWhilePlayingRequeues() {
        val e = FakeEngine(); val s = NarrationSession(book, e)
        s.play(); s.nextChapter()
        assertEquals("S4.", e.queue.first().second)
        s.previousSentence()
        assertEquals("S3.", e.queue.first().second)
    }

    @Test fun seekWhilePausedOnlyMovesPosition() {
        val e = FakeEngine(); val s = NarrationSession(book, e, startAt = Position(0, 2))
        s.nextSentence()
        assertEquals(Position(1, 0), s.position.value)
        assertTrue(e.queue.isEmpty())
        assertEquals(NarrationState.IDLE, s.state.value)
    }

    @Test fun engineErrorStopsWithMessage() {
        val e = FakeEngine(); val s = NarrationSession(book, e)
        s.play()
        e.listener!!.onError(e.queue.first().first, "boom")
        assertEquals(NarrationState.ERROR, s.state.value)
        assertEquals("boom", s.error.value)
    }

    @Test fun playAfterFinishRestartsFromBeginning() {
        val e = FakeEngine(); val s = NarrationSession(book, e)
        s.play(); repeat(5) { if (e.queue.isNotEmpty()) e.speakNext() }
        s.play()
        assertEquals("S1.", e.queue.first().second)
    }
}
