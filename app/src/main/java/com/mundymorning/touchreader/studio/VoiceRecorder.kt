package com.mundymorning.touchreader.studio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.mundymorning.touchreader.core.voicestudio.Pcm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/** Records from the microphone into memory (max [MAX_SECONDS]); nothing is written to disk until a voice is saved. */
class VoiceRecorder {
    data class State(val recording: Boolean = false, val elapsedMs: Long = 0, val level: Float = 0f, val error: String? = null)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var stopRequested = false
    private var thread: Thread? = null
    private var data = ShortArray(0)
    private var count = 0
    private var rate = 44100

    @SuppressLint("MissingPermission") // the screen asks for RECORD_AUDIO before calling start()
    fun start(): Boolean {
        if (_state.value.recording) return true
        val candidates = intArrayOf(44100, 48000, 16000)
        var rec: AudioRecord? = null
        for (sr in candidates) {
            val min = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) continue
            val r = try {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, sr))
            } catch (e: Exception) { null }
            if (r != null && r.state == AudioRecord.STATE_INITIALIZED) { rec = r; rate = sr; break }
            r?.release()
        }
        if (rec == null) { _state.value = State(error = "The microphone could not be opened. Another app may be using it."); return false }
        data = ShortArray(rate * 10); count = 0; stopRequested = false
        _state.value = State(recording = true)
        val recorder = rec
        thread = Thread({
            val buf = ShortArray(2048)
            val started = System.nanoTime()
            var lastUi = 0L
            try {
                recorder.startRecording()
                while (!stopRequested) {
                    val n = recorder.read(buf, 0, buf.size)
                    if (n <= 0) { if (n < 0) break else continue }
                    if (count + n > data.size) data = data.copyOf(data.size * 2)
                    var peak = 0
                    for (i in 0 until n) { data[count + i] = buf[i]; peak = maxOf(peak, abs(buf[i].toInt())) }
                    count += n
                    val now = System.nanoTime()
                    if (now - lastUi > 80_000_000L) {
                        lastUi = now
                        _state.value = State(recording = true, elapsedMs = (now - started) / 1_000_000, level = peak / 32768f)
                    }
                    if (count >= rate * MAX_SECONDS) break
                }
            } catch (e: Exception) {
                _state.value = State(error = "Recording stopped unexpectedly.")
            } finally {
                runCatching { recorder.stop() }; recorder.release()
                _state.value = _state.value.copy(recording = false)
            }
        }, "tr-record").also { it.start() }
        return true
    }

    /** Stops and returns what was recorded, or null if nothing usable was captured. */
    fun stop(): Pcm? {
        stopRequested = true
        thread?.join(2000)
        thread = null
        _state.value = State()
        if (count == 0) return null
        return Pcm(rate, FloatArray(count) { data[it] / 32768f }).also { data = ShortArray(0); count = 0 }
    }

    fun discard() { stopRequested = true; thread?.join(2000); thread = null; data = ShortArray(0); count = 0; _state.value = State() }

    companion object { const val MAX_SECONDS = 90 }
}
