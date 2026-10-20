package com.mundymorning.touchreader.studio

import android.media.MediaPlayer
import com.mundymorning.touchreader.core.voicestudio.Pcm
import com.mundymorning.touchreader.core.voicestudio.WavIo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Plays a short voice sample (the recording the user just made, or a saved voice's reference).
 * A failure to play is reported through [error]; it never crashes the app.
 */
class SamplePlayer(private val cacheDir: File) {
    private var player: MediaPlayer? = null
    private var temp: File? = null

    private val _playingKey = MutableStateFlow<String?>(null)
    val playingKey: StateFlow<String?> = _playingKey.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun clearError() { _error.value = null }

    fun play(key: String, file: File) {
        release()
        start(key, file)
    }

    fun playPcm(key: String, pcm: Pcm) {
        release() // first: releasing must never delete the file we are about to write
        val f = File(cacheDir, "preview-${System.nanoTime()}.wav")
        try { f.writeBytes(WavIo.write16(pcm)) } catch (e: Exception) { _error.value = "The sample could not be prepared for playback."; return }
        temp = f
        start(key, f)
    }

    fun stop() = release()

    private fun start(key: String, file: File) {
        try {
            player = MediaPlayer().apply {
                setDataSource(file.path)
                setOnCompletionListener { release() }
                setOnErrorListener { _, _, _ -> release(); _error.value = "Playback failed."; true }
                prepare(); start()
            }
            _error.value = null
            _playingKey.value = key
        } catch (e: Exception) {
            release()
            _error.value = "This sample could not be played (${e.javaClass.simpleName})."
        }
    }

    private fun release() {
        player?.let { runCatching { it.stop() }; runCatching { it.release() } }
        player = null
        _playingKey.value = null
        temp?.delete()
        temp = null
    }
}
