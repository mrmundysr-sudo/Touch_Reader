package com.mundymorning.touchreader.core.tts

import com.mundymorning.touchreader.core.reader.Position
import com.mundymorning.touchreader.core.reader.ReaderBook
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class NarrationState { IDLE, PLAYING, PAUSED, FINISHED, ERROR }

/**
 * Drives a [SpeechEngine] through a [ReaderBook] sentence by sentence.
 *
 * Keeps a bounded look-ahead ([lookahead] sentences) queued in the engine so narration is
 * continuous, tracks which sentence is currently audible, and supports pause/seek.
 * Thread-safe: engine callbacks arrive on arbitrary threads.
 */
class NarrationSession(
    private val book: ReaderBook,
    private val engine: SpeechEngine,
    startAt: Position = Position.START,
    private val lookahead: Int = 3,
) : SpeechEngine.Listener {
    private val lock = Any()
    private val inflight = LinkedHashMap<Int, Position>() // id -> position, in queue order
    private var nextId = 1
    private var lastQueued: Position? = null

    private val _position = MutableStateFlow(book.clamp(startAt))
    val position: StateFlow<Position> = _position.asStateFlow()

    private val _state = MutableStateFlow(NarrationState.IDLE)
    val state: StateFlow<NarrationState> = _state.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** When true, narration pauses after the last sentence of the chapter now being read (sleep timer). */
    @Volatile var pauseAtChapterEnd: Boolean = false

    init { engine.listener = this }

    fun play() = synchronized(lock) {
        if (book.totalSentences == 0) return@synchronized
        if (_state.value == NarrationState.PLAYING) return@synchronized
        if (_state.value == NarrationState.FINISHED) _position.value = Position.START
        _error.value = null
        _state.value = NarrationState.PLAYING
        restartFrom(_position.value)
    }

    fun pause() = synchronized(lock) {
        if (_state.value != NarrationState.PLAYING) return@synchronized
        _state.value = NarrationState.PAUSED
        clearQueue()
    }

    fun toggle() { if (_state.value == NarrationState.PLAYING) pause() else play() }

    fun stop() = synchronized(lock) {
        clearQueue()
        _state.value = NarrationState.IDLE
    }

    fun seekTo(p: Position) = synchronized(lock) {
        val target = book.clamp(p)
        _position.value = target
        if (_state.value == NarrationState.PLAYING) restartFrom(target)
    }

    fun nextSentence() = book.next(_position.value)?.let(::seekTo)
    fun previousSentence() = book.previous(_position.value)?.let(::seekTo)
    fun nextChapter() = book.nextChapter(_position.value)?.let(::seekTo)
    fun previousChapter() = book.previousChapter(_position.value)?.let(::seekTo)

    /** Re-queue from the current sentence, e.g. after the voice or speed changed. */
    fun restartCurrent() = synchronized(lock) {
        if (_state.value == NarrationState.PLAYING) restartFrom(_position.value)
    }

    fun release() = synchronized(lock) {
        clearQueue()
        engine.listener = null
        _state.value = NarrationState.IDLE
    }

    // --- internals (call with lock held) ---

    private fun clearQueue() {
        inflight.clear() // first, so callbacks fired by stop() are ignored as stale
        lastQueued = null
        engine.stop()
    }

    private fun restartFrom(p: Position) {
        clearQueue()
        enqueueAt(p)
        fill()
    }

    private fun enqueueAt(p: Position) {
        val id = nextId++
        inflight[id] = p
        lastQueued = p
        engine.enqueue(id, book.sentenceText(p))
    }

    private fun fill() {
        while (inflight.size < lookahead) {
            val next = lastQueued?.let(book::next) ?: return
            enqueueAt(next)
        }
    }

    // --- engine callbacks ---

    override fun onStart(id: Int) = synchronized(lock) {
        val p = inflight[id] ?: return@synchronized
        _position.value = p
    }

    override fun onDone(id: Int) = synchronized(lock) {
        val p = inflight.remove(id) ?: return@synchronized
        if (_state.value != NarrationState.PLAYING) return@synchronized
        if (pauseAtChapterEnd && book.next(p)?.chapter != p.chapter) {
            pauseAtChapterEnd = false
            clearQueue()
            _position.value = book.next(p) ?: p
            _state.value = NarrationState.PAUSED
            return@synchronized
        }
        if (inflight.isEmpty() && book.next(p) == null) {
            _state.value = NarrationState.FINISHED
            lastQueued = null
        } else fill()
    }

    override fun onError(id: Int, message: String) = synchronized(lock) {
        if (inflight[id] == null) return@synchronized
        clearQueue()
        _error.value = message
        _state.value = NarrationState.ERROR
    }
}
