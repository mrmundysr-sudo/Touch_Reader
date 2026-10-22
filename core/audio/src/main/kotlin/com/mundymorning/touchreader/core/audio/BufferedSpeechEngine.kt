package com.mundymorning.touchreader.core.audio

import com.mundymorning.touchreader.core.tts.SpeechEngine
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A [SpeechEngine] for synthesizers that produce PCM. One thread synthesizes upcoming sentences into a
 * bounded buffer while another plays the previous one, so narration is continuous and memory stays bounded.
 * Stopping discards everything queued; stale work is dropped by a generation counter.
 */
class BufferedSpeechEngine(
    private val synthesizer: Synthesizer,
    private val sink: AudioSink,
    /** How many finished sentences may wait in memory ahead of playback. */
    maxReady: Int = 2,
    /** Average real-time factor above which [onSlow] fires. */
    private val slowRtf: Double = 1.0,
    private val slowMinSamples: Int = 3,
    private val nanoTime: () -> Long = System::nanoTime,
    /**
     * When above 0, sentences of one paragraph that are waiting in the queue are spoken together as one piece of up to
     * this many characters (so the voice carries on from one sentence to the next instead of ending each on a full
     * stop), then cut back into sentences at the pauses so each is still reported separately.
     */
    private val mergeMaxChars: Int = 0,
    /** Quiet added after a sentence that is followed by another of the same paragraph (only when [mergeMaxChars] is on). */
    private val sentencePauseMs: Int = 0,
    /** Quiet added after the last sentence of a paragraph (only when [mergeMaxChars] is on). */
    private val paragraphPauseMs: Int = 0,
) : SpeechEngine {
    override var listener: SpeechEngine.Listener? = null

    /** Invoked (once per run) with the average RTF when synthesis is too slow to keep up. */
    @Volatile var onSlow: ((Double) -> Unit)? = null

    /**
     * Invoked once per engine (from the synthesis thread) with seconds of work per second of speech, measured on
     * the sentences actually being narrated: the first sentence (model warm-up) is left out and the next
     * [SPEED_SAMPLES] are averaged. Lets the app learn how fast this phone is without a separate speed test.
     */
    @Volatile var onSpeedKnown: ((Double) -> Unit)? = null
    private var speedWork = 0.0
    private var speedAudio = 0.0
    private var speedSamples = 0
    private var seen = 0
    @Volatile private var speedReported = false

    val stats = SynthesisStats()

    private class Request(val gen: Int, val id: Int, val text: String, val enqueuedAt: Long, val first: Boolean, val paragraphEnd: Boolean)
    private class Ready(val req: Request, val pcm: ShortArray, val pauseAfterMs: Int = 0)

    @Volatile private var generation = 0
    @Volatile private var speed = 1f
    @Volatile private var released = false
    @Volatile private var slowNotified = false
    /** Only one thing may use the synthesizer at a time: the synthesis thread, or a speed [measure]. */
    private val synthLock = Any()
    private val inFlight = AtomicInteger(0)
    private val requests = LinkedBlockingQueue<Request>()
    private val ready = ArrayBlockingQueue<Ready>(maxReady)

    private val synthThread = Thread({ synthLoop() }, "tr-synth").apply { isDaemon = true; start() }
    private val playThread = Thread({ playLoop() }, "tr-play").apply { isDaemon = true; start() }

    override fun enqueue(id: Int, text: String) = enqueue(id, text, true)

    override fun enqueue(id: Int, text: String, paragraphEnd: Boolean) {
        val first = inFlight.getAndIncrement() == 0
        requests.put(Request(generation, id, text, nanoTime(), first, paragraphEnd))
    }

    override fun stop() {
        synchronized(this) {
            generation++
            requests.clear()
            ready.clear()
            inFlight.set(0)
            slowNotified = false
        }
        synthesizer.resetContext()
        sink.flush()
    }

    override fun setSpeed(speed: Float) { this.speed = speed }

    override fun release() {
        released = true
        stop()
        synthThread.interrupt()
        playThread.interrupt()
        sink.close()
        synthesizer.release()
    }

    /**
     * Times how long this synthesizer takes to speak [texts] (on the calling thread, waiting for any sentence
     * already being synthesized) and returns seconds of work per second of speech. The first text only warms
     * the engine up and is not counted when there is more than one. Returns null if nothing could be measured.
     */
    fun measure(texts: List<String>, isCancelled: () -> Boolean = { false }): Double? {
        var work = 0.0
        var audio = 0.0
        for ((i, text) in texts.withIndex()) {
            if (isCancelled() || released) return null
            val t0 = nanoTime()
            val pcm = synchronized(synthLock) { synthesizer.synthesize(text, 1f, isCancelled) }
            val seconds = (nanoTime() - t0) / 1e9
            if (isCancelled()) return null
            if (i == 0 && texts.size > 1) continue
            work += seconds; audio += pcm.size.toDouble() / synthesizer.sampleRate
        }
        synthesizer.resetContext()
        return if (audio > 0) work / audio else null
    }

    private fun current(r: Request) = r.gen == generation && !released

    /** [first] plus the sentences queued right behind it that belong to the same paragraph and fit in [mergeMaxChars]. */
    private fun collectGroup(first: Request): List<Request> {
        val group = arrayListOf(first)
        if (mergeMaxChars <= 0) return group
        var chars = first.text.length
        while (!group.last().paragraphEnd) {
            val next = requests.peek() ?: break
            if (next.gen != first.gen || chars + 1 + next.text.length > mergeMaxChars) break
            requests.poll()
            group += next
            chars += 1 + next.text.length
        }
        return group
    }

    private fun synthLoop() {
        while (!released) {
            val req = try { requests.take() } catch (e: InterruptedException) { return }
            if (!current(req)) continue
            val group = collectGroup(req)
            val text = if (group.size == 1) req.text else group.joinToString(" ") { it.text }
            val t0 = nanoTime()
            val pcm = try {
                synchronized(synthLock) { synthesizer.synthesize(text, speed) { !current(req) } }
            } catch (e: Exception) {
                if (current(req)) listener?.onError(req.id, e.message ?: "Speech synthesis failed.")
                continue
            }
            if (!current(req)) continue
            val seconds = pcm.size.toDouble() / synthesizer.sampleRate
            stats.record(nanoTime() - t0, seconds)
            noteSpeed(nanoTime() - t0, seconds)
            checkSlow()
            if (group.last().paragraphEnd) synthesizer.resetContext() // the next paragraph starts afresh
            val parts = if (group.size == 1) listOf(pcm) else AudioSplit.split(pcm, group.map { it.text.length.coerceAtLeast(1) }, synthesizer.sampleRate)
            for ((i, part) in parts.withIndex()) {
                val last = i == parts.lastIndex
                val pause = if (mergeMaxChars <= 0 || !last) 0 else if (group[i].paragraphEnd) paragraphPauseMs else sentencePauseMs
                val item = Ready(group[i], part, pause)
                try {
                    // Bounded: blocks here when playback is far behind, instead of growing memory.
                    while (!ready.offer(item, 50, TimeUnit.MILLISECONDS)) if (!current(req)) break
                } catch (e: InterruptedException) { return }
                if (!current(req)) break
            }
        }
    }

    private fun noteSpeed(nanos: Long, audioSeconds: Double) {
        if (speedReported || audioSeconds <= 0) return
        if (seen++ == 0) return
        speedWork += nanos / 1e9; speedAudio += audioSeconds; speedSamples++
        if (speedSamples >= SPEED_SAMPLES) { speedReported = true; onSpeedKnown?.invoke(speedWork / speedAudio) }
    }

    private fun checkSlow() {
        val s = stats.snapshot()
        if (!slowNotified && (stats.windowFull(slowMinSamples) && s.averageRtf > slowRtf || s.lastRtf > VERY_SLOW_RTF)) {
            slowNotified = true
            onSlow?.invoke(s.averageRtf)
        }
    }

    private companion object { const val VERY_SLOW_RTF = 3.0; const val SPEED_SAMPLES = 2 }

    private fun playLoop() {
        sink.open(synthesizer.sampleRate)
        while (!released) {
            val item = try { ready.take() } catch (e: InterruptedException) { return }
            val req = item.req
            if (!current(req)) continue
            if (req.first) stats.recordFirstAudio((nanoTime() - req.enqueuedAt) / 1_000_000)
            listener?.onStart(req.id)
            sink.write(item.pcm) { !current(req) }
            if (current(req) && item.pauseAfterMs > 0) sink.write(ShortArray(item.pauseAfterMs * synthesizer.sampleRate / 1000)) { !current(req) }
            if (current(req)) {
                inFlight.decrementAndGet()
                listener?.onDone(req.id)
            }
        }
    }
}
