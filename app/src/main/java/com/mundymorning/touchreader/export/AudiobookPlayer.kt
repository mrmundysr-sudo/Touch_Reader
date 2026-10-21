package com.mundymorning.touchreader.export

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.PlaybackParams
import androidx.core.content.ContextCompat
import androidx.media.AudioAttributesCompat
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class ChapterAudio(val chapter: Int, val title: String, val file: File, val durationMs: Long)

data class AudiobookState(
    val docId: Long? = null,
    val title: String = "",
    val chapters: List<ChapterAudio> = emptyList(),
    val index: Int = 0,
    val playing: Boolean = false,
    val positionMs: Int = 0,
    val speed: Float = 1f,
    val error: String? = null,
) {
    val current: ChapterAudio? get() = chapters.getOrNull(index)
    val durationMs: Int get() = current?.durationMs?.toInt() ?: 0
}

/**
 * Plays the finished chapters of a rendered audiobook with the phone's own media player. Chapters are plain
 * AAC files, so this starts at once, whatever the phone's speed. Position is remembered per book.
 */
class AudiobookPlayer(private val app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = app.getSharedPreferences("audiobook", Context.MODE_PRIVATE)
    private val audio = app.getSystemService(AudioManager::class.java)
    private val _state = MutableStateFlow(AudiobookState())
    val state: StateFlow<AudiobookState> = _state.asStateFlow()

    private var player: MediaPlayer? = null
    private var tick: Job? = null
    private var resumeAfterFocus = false

    private val focus: AudioFocusRequestCompat by lazy {
        AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributesCompat.Builder().setUsage(AudioAttributesCompat.USAGE_MEDIA).setContentType(AudioAttributesCompat.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> { resumeAfterFocus = false; pause() }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { resumeAfterFocus = _state.value.playing; pause() }
                    AudioManager.AUDIOFOCUS_GAIN -> if (resumeAfterFocus) { resumeAfterFocus = false; play() }
                }
            }.build()
    }

    /** Loads the finished [chapters] of a book. Without [startChapter] it carries on where this book was left. */
    fun open(docId: Long, title: String, chapters: List<ChapterAudio>, startChapter: Int? = null) {
        val st = _state.value
        if (st.docId == docId && startChapter == null && st.chapters.isNotEmpty()) {
            // more chapters may have finished meanwhile; adding them must not interrupt what is playing
            if (st.chapters.size != chapters.size) _state.update { it.copy(chapters = chapters, title = title) }
            return
        }
        close()
        if (chapters.isEmpty()) { _state.value = AudiobookState(docId, title); return }
        val saved = prefs.getString("pos_$docId", null)?.split(':')
        val savedIdx = saved?.getOrNull(0)?.toIntOrNull()?.takeIf { it in chapters.indices }
        val savedMs = saved?.getOrNull(1)?.toIntOrNull() ?: 0
        val idx = startChapter?.let { c -> chapters.indexOfFirst { it.chapter == c }.takeIf { it >= 0 } } ?: savedIdx ?: 0
        _state.value = AudiobookState(docId, title, chapters, idx, false, if (startChapter == null && idx == savedIdx) savedMs else 0, prefs.getFloat("speed", 1f))
        prepare(startMs = _state.value.positionMs)
    }

    private fun prepare(startMs: Int) {
        player?.release(); player = null
        val ch = _state.value.current ?: return
        try {
            val mp = MediaPlayer()
            mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            mp.setWakeMode(app, android.os.PowerManager.PARTIAL_WAKE_LOCK)
            mp.setDataSource(ch.file.path)
            mp.setOnCompletionListener { onChapterEnded() }
            mp.setOnErrorListener { _, _, _ -> _state.update { it.copy(playing = false, error = "This chapter could not be played.") }; true }
            mp.prepare()
            if (startMs > 0) mp.seekTo(startMs)
            player = mp
            _state.update { it.copy(positionMs = startMs, error = null) }
        } catch (e: Exception) {
            _state.update { it.copy(playing = false, error = "This chapter could not be opened (${e.javaClass.simpleName}).") }
        }
    }

    fun play() {
        val mp = player ?: return
        val granted = AudioManagerCompat.requestAudioFocus(audio, focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!granted) { _state.update { it.copy(error = "Another app is using the audio.") }; return }
        ContextCompat.startForegroundService(app, Intent(app, AudiobookService::class.java))
        try {
            mp.start()
            applySpeed(mp)
        } catch (e: IllegalStateException) { _state.update { it.copy(error = "Playback could not start.") }; return }
        _state.update { it.copy(playing = true, error = null) }
        startTicking()
    }

    fun pause() {
        player?.let { if (it.isPlaying) it.pause() }
        tick?.cancel()
        _state.update { st -> st.copy(playing = false, positionMs = player?.currentPosition ?: st.positionMs) }
        savePosition()
        if (!resumeAfterFocus) AudioManagerCompat.abandonAudioFocusRequest(audio, focus)
    }

    fun toggle() { if (_state.value.playing) pause() else play() }

    fun seekTo(ms: Int) {
        val d = _state.value.durationMs
        val target = ms.coerceIn(0, if (d > 0) d else Int.MAX_VALUE)
        player?.seekTo(target)
        _state.update { it.copy(positionMs = target) }
    }

    fun skip(seconds: Int) = seekTo((player?.currentPosition ?: _state.value.positionMs) + seconds * 1000)

    fun selectChapter(index: Int, autoPlay: Boolean = true) {
        if (index !in _state.value.chapters.indices) return
        val resume = _state.value.playing || autoPlay
        tick?.cancel()
        _state.update { it.copy(index = index, positionMs = 0) }
        prepare(0)
        if (resume) play()
    }

    fun next() { if (_state.value.index + 1 < _state.value.chapters.size) selectChapter(_state.value.index + 1, true) }
    fun previous() {
        if ((player?.currentPosition ?: 0) > 4000) seekTo(0) else if (_state.value.index > 0) selectChapter(_state.value.index - 1, true)
    }

    fun setSpeed(speed: Float) {
        prefs.edit().putFloat("speed", speed).apply()
        _state.update { it.copy(speed = speed) }
        player?.let { if (it.isPlaying) applySpeed(it) }
    }

    private fun applySpeed(mp: MediaPlayer) {
        val s = _state.value.speed
        try { mp.playbackParams = PlaybackParams().setSpeed(s) } catch (_: Exception) { }
    }

    private fun onChapterEnded() {
        if (_state.value.index + 1 < _state.value.chapters.size) selectChapter(_state.value.index + 1, true)
        else {
            tick?.cancel()
            _state.update { it.copy(playing = false, positionMs = 0) }
            AudioManagerCompat.abandonAudioFocusRequest(audio, focus)
            prefs.edit().remove("pos_${_state.value.docId}").apply()
        }
    }

    private fun startTicking() {
        tick?.cancel()
        tick = scope.launch {
            var sinceSave = 0
            while (isActive) {
                delay(500)
                val p = player ?: break
                _state.update { it.copy(positionMs = runCatching { p.currentPosition }.getOrDefault(it.positionMs)) }
                if (++sinceSave >= 10) { sinceSave = 0; savePosition() }
            }
        }
    }

    private fun savePosition() {
        val st = _state.value
        val id = st.docId ?: return
        prefs.edit().putString("pos_$id", "${st.index}:${player?.currentPosition ?: st.positionMs}").apply()
    }

    fun close() {
        tick?.cancel()
        savePosition()
        player?.release(); player = null
        AudioManagerCompat.abandonAudioFocusRequest(audio, focus)
        _state.value = AudiobookState()
    }
}
