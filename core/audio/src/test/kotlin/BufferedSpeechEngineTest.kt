import com.mundymorning.touchreader.core.audio.*
import com.mundymorning.touchreader.core.tts.SpeechEngine
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

private class FakeSynth(val sampleRate0: Int = 1000, val delayMs: Long = 0, val samples: Int = 100) : Synthesizer {
    override val sampleRate = sampleRate0
    val calls = AtomicInteger()
    val speeds = CopyOnWriteArrayList<Float>()
    @Volatile var failOn: String? = null
    override fun synthesize(text: String, speed: Float, isCancelled: () -> Boolean): ShortArray {
        calls.incrementAndGet(); speeds += speed
        if (delayMs > 0) Thread.sleep(delayMs)
        if (text == failOn) error("boom")
        return ShortArray(samples)
    }
}

private class FakeSink : AudioSink {
    @Volatile var gate: CountDownLatch? = null
    val flushes = AtomicInteger()
    override fun open(sampleRate: Int) {}
    override fun write(pcm: ShortArray, isCancelled: () -> Boolean) {
        val g = gate ?: return
        while (!g.await(10, java.util.concurrent.TimeUnit.MILLISECONDS)) if (isCancelled()) return
    }
    override fun flush() { flushes.incrementAndGet() }
    override fun close() {}
}

private class Recorder : SpeechEngine.Listener {
    val events = CopyOnWriteArrayList<String>()
    override fun onStart(id: Int) { events += "start$id" }
    override fun onDone(id: Int) { events += "done$id" }
    override fun onError(id: Int, message: String) { events += "error$id:$message" }
}

private fun awaitUntil(ms: Long = 3000, cond: () -> Boolean) {
    val end = System.currentTimeMillis() + ms
    while (!cond()) { if (System.currentTimeMillis() > end) fail("condition not met within ${ms}ms"); Thread.sleep(5) }
}

class BufferedSpeechEngineTest {
    @Test fun playsInOrderWithStartBeforeDone() {
        val rec = Recorder()
        val e = BufferedSpeechEngine(FakeSynth(), FakeSink()).also { it.listener = rec }
        (1..4).forEach { e.enqueue(it, "s$it") }
        awaitUntil { rec.events.size == 8 }
        assertEquals(listOf("start1", "done1", "start2", "done2", "start3", "done3", "start4", "done4"), rec.events.toList())
        e.release()
    }

    @Test fun memoryStaysBoundedWhenPlaybackIsBlocked() {
        val synth = FakeSynth(); val sink = FakeSink().also { it.gate = CountDownLatch(1) }
        val e = BufferedSpeechEngine(synth, sink, maxReady = 2).also { it.listener = Recorder() }
        (1..20).forEach { e.enqueue(it, "s$it") }
        Thread.sleep(300)
        // 1 being "played" (blocked) + 2 buffered + 1 finished waiting for space
        assertTrue(synth.calls.get() <= 4, "synthesized ${synth.calls.get()} sentences ahead of a stalled player")
        sink.gate!!.countDown()
        e.release()
    }

    @Test fun stopDiscardsQueuedWorkAndEngineIsReusable() {
        val rec = Recorder(); val sink = FakeSink().also { it.gate = CountDownLatch(1) }
        val e = BufferedSpeechEngine(FakeSynth(), sink).also { it.listener = rec }
        (1..5).forEach { e.enqueue(it, "s$it") }
        awaitUntil { rec.events.contains("start1") }
        e.stop()
        assertTrue(sink.flushes.get() >= 1)
        sink.gate = null
        e.enqueue(100, "new")
        awaitUntil { rec.events.contains("done100") }
        assertFalse(rec.events.any { it == "done1" || it == "start2" || it == "start3" })
        e.release()
    }

    @Test fun synthesisFailureIsReported() {
        val rec = Recorder(); val synth = FakeSynth().also { it.failOn = "bad" }
        val e = BufferedSpeechEngine(synth, FakeSink()).also { it.listener = rec }
        e.enqueue(1, "bad")
        awaitUntil { rec.events.any { it.startsWith("error1") } }
        assertTrue(rec.events.first().contains("boom"))
        e.release()
    }

    @Test fun speedReachesSynthesizer() {
        val synth = FakeSynth(); val rec = Recorder()
        val e = BufferedSpeechEngine(synth, FakeSink()).also { it.listener = rec }
        e.setSpeed(1.5f); e.enqueue(1, "x")
        awaitUntil { rec.events.contains("done1") }
        assertEquals(1.5f, synth.speeds.first())
        e.release()
    }

    @Test fun slowSynthesisIsDetectedOnce() {
        // 100 samples @1000 Hz = 0.1 s of audio, but synthesis takes ~250 ms -> RTF ~2.5
        val synth = FakeSynth(delayMs = 250); val rec = Recorder()
        val calls = AtomicInteger(); val seen = CopyOnWriteArrayList<Double>()
        val e = BufferedSpeechEngine(synth, FakeSink(), slowMinSamples = 3).also { it.listener = rec }
        e.onSlow = { calls.incrementAndGet(); seen += it }
        (1..5).forEach { e.enqueue(it, "s$it") }
        awaitUntil(8000) { rec.events.contains("done5") }
        assertEquals(1, calls.get())
        assertTrue(seen.first() > 1.0)
        assertTrue(e.stats.snapshot().averageRtf > 1.0)
        e.release()
    }

    @Test fun fastSynthesisIsNotFlaggedAndFirstAudioIsMeasured() {
        val rec = Recorder(); val calls = AtomicInteger()
        val e = BufferedSpeechEngine(FakeSynth(delayMs = 5, samples = 1000), FakeSink()).also { it.listener = rec }
        e.onSlow = { calls.incrementAndGet() }
        (1..5).forEach { e.enqueue(it, "s$it") }
        awaitUntil { rec.events.contains("done5") }
        assertEquals(0, calls.get())
        assertTrue(e.stats.snapshot().timeToFirstAudioMs >= 0)
        assertTrue(e.stats.snapshot().averageRtf < 1.0)
        e.release()
    }
}
