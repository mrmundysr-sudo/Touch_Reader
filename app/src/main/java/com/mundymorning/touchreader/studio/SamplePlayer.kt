package com.mundymorning.touchreader.studio

import android.media.MediaPlayer
import com.mundymorning.touchreader.core.voicestudio.Pcm
import com.mundymorning.touchreader.core.voicestudio.WavIo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Plays a short voice sample (the recording the user just made, or a saved voice's reference). */
class SamplePlayer(private val cacheDir: File) {
    private var player: MediaPlayer? = null
    private val _playingKey = MutableStateFlow<String?>(null)
    val playingKey: StateFlow<String?> = _playingKey.asStateFlow()

    fun play(key: String, file: File) {
        stop()
        player = MediaPlayer().apply {
            setDataSource(file.path)
            setOnCompletionListener { stop() }
            setOnErrorListener { _, _, _ -> stop(); true }
            prepare(); start()
        }
        _playingKey.value = key
    }

    fun playPcm(key: String, pcm: Pcm) {
        val f = File(cacheDir, "preview.wav").also { it.writeBytes(WavIo.write16(pcm)) }
        play(key, f)
    }

    fun stop() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        _playingKey.value = null
        File(cacheDir, "preview.wav").delete()
    }
}
