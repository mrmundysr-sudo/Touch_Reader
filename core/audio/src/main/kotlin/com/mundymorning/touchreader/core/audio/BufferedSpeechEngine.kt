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
) : SpeechEngine {
    override var listener: SpeechEngine.Listener? = null

    /** Invoked (once per run) with the average RTF when synthesis is too slow to keep up. */
    @Volatile var onSlow: ((Double) -> Unit)? = null

    val stats = SynthesisStats()

    private class Request(val gen: Int, val id: Int, val text: String, val enqueuedAt: Long, val first: Boolean)
    private class Ready(val req: Request, val pcm: ShortArray)

    @Volatile private var generation = 0
    @Volatile private var speed = 1f
    @Volatile private var released = false
    @Volatile private var slowNotified = false
    private val inFlight = AtomicInteger(0)
    private val requests = LinkedBlockingQueue<Request>()
    private val ready = ArrayBlockingQueue<Ready>(maxReady)

    private val synthThread = Thread({ synthLoop() }, "tr-synth").apply { isDaemon = true; start() }
    private val playThread = Thread({ playLoop() }, "tr-play").apply { isDaemon = true; start() }

    override fun enqueue(id: Int, text: String) {
        val first = inFlight.getAndIncrement() == 0
        requests.put(Request(generation, id, text, nanoTime(), first))
    }

    override fun stop() {
        synchronized(this) {
            generation++
            requests.clear()
            ready.clear()
            inFlight.set(0)
            slowNotified = false
        }
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

    private fun current(r: Request) = r.gen == generation && !released

    private fun synthLoop() {
        while (!released) {
            val req = try { requests.take() } catch (e: InterruptedException) { return }
            if (!current(req)) continue
            val t0 = nanoTime()
            val pcm = try {
                synthesizer.synthesize(req.text, speed) { !current(req) }
            } catch (e: Exception) {
                if (current(req)) listener?.onError(req.id, e.message ?: "Speech synthesis failed.")
                continue
            }
            if (!current(req)) continue
            val seconds = pcm.size.toDouble() / synthesizer.sampleRate
            stats.record(nanoTime() - t0, seconds)
            checkSlow()
            val item = Ready(req, pcm)
            try {
                // Bounded: blocks here when playback is far behind, instead of growing memory.
                while (!ready.offer(item, 50, TimeUnit.MILLISECONDS)) if (!current(req)) break
            } catch (e: InterruptedException) { return }
        }
    }

    private fun checkSlow() {
        val s = stats.snapshot()
        if (!slowNotified && stats.windowFull(slowMinSamples) && s.averageRtf > slowRtf) {
            slowNotified = true
            onSlow?.invoke(s.averageRtf)
        }
    }

    private fun playLoop() {
        sink.open(synthesizer.sampleRate)
        while (!released) {
            val item = try { ready.take() } catch (e: InterruptedException) { return }
            val req = item.req
            if (!current(req)) continue
            if (req.first) stats.recordFirstAudio((nanoTime() - req.enqueuedAt) / 1_000_000)
            listener?.onStart(req.id)
            sink.write(item.pcm) { !current(req) }
            if (current(req)) {
                inFlight.decrementAndGet()
                listener?.onDone(req.id)
            }
        }
    }
}
