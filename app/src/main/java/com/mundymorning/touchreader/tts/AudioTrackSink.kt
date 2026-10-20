package com.mundymorning.touchreader.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.mundymorning.touchreader.core.audio.AudioSink

/** Streams 16-bit mono PCM to the speaker. Blocking writes pace the synthesizer to real time. */
class AudioTrackSink : AudioSink {
    @Volatile private var track: AudioTrack? = null

    override fun open(sampleRate: Int) {
        val minBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // About a quarter second of audio: small enough that "now speaking" tracks what is heard.
        val bytes = maxOf(minBytes, sampleRate / 4 * 2)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            )
            .setBufferSizeInBytes(bytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build().also { it.play() }
    }

    override fun write(pcm: ShortArray, isCancelled: () -> Boolean) {
        val t = track ?: return
        var off = 0
        while (off < pcm.size && !isCancelled()) {
            val n = t.write(pcm, off, minOf(4096, pcm.size - off))
            if (n <= 0) break
            off += n
        }
    }

    override fun flush() {
        val t = track ?: return
        runCatching { t.pause(); t.flush(); t.play() }
    }

    override fun close() {
        track?.let { runCatching { it.stop() }; it.release() }
        track = null
    }
}
