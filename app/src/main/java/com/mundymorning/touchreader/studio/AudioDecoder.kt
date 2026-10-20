package com.mundymorning.touchreader.studio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.mundymorning.touchreader.core.voicestudio.Pcm
import com.mundymorning.touchreader.core.voicestudio.UnsupportedAudioException
import com.mundymorning.touchreader.core.voicestudio.WavIo
import java.nio.ByteOrder

/** Turns an imported audio file (WAV, MP3, M4A/AAC, OGG…) into mono PCM using the phone's own decoders. */
object AudioDecoder {
    private const val MAX_SECONDS = 120
    private const val MAX_WAV_BYTES = 64 * 1024 * 1024

    fun decode(context: Context, uri: Uri): Pcm {
        val head = context.contentResolver.openInputStream(uri)?.use { it.readNBytes(12) } ?: throw UnsupportedAudioException("The file could not be opened.")
        if (head.size >= 12 && String(head, 0, 4) == "RIFF" && String(head, 8, 4) == "WAVE") {
            val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readNBytes(MAX_WAV_BYTES) }
            return WavIo.read(bytes).let { limit(it) }
        }
        return decodeWithMediaCodec(context, uri)
    }

    private fun limit(p: Pcm): Pcm {
        val max = MAX_SECONDS * p.sampleRate
        return if (p.samples.size > max) Pcm(p.sampleRate, p.samples.copyOf(max)) else p
    }

    private fun decodeWithMediaCodec(context: Context, uri: Uri): Pcm {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: throw UnsupportedAudioException("No audio was found in this file.")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val codec = try { MediaCodec.createDecoderByType(mime) } catch (e: Exception) {
                throw UnsupportedAudioException("This phone cannot read this type of audio ($mime).")
            }
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            codec.configure(format, null, null, 0)
            codec.start()
            var out = FloatArray(rate * 10)
            var count = 0
            val info = MediaCodec.BufferInfo()
            var inputDone = false; var outputDone = false
            try {
                while (!outputDone) {
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val buf = codec.getInputBuffer(i)!!
                            val n = extractor.readSampleData(buf, 0)
                            if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                            else { codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0); extractor.advance() }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        o >= 0 -> {
                            val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val frames = buf.remaining() / channels
                            if (count + frames > out.size) out = out.copyOf(maxOf(out.size * 2, count + frames))
                            for (f in 0 until frames) {
                                var sum = 0f
                                for (c in 0 until channels) sum += buf.get() / 32768f
                                out[count++] = sum / channels
                            }
                            codec.releaseOutputBuffer(o, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || count >= rate * MAX_SECONDS) outputDone = true
                        }
                    }
                }
            } finally {
                runCatching { codec.stop() }; codec.release()
            }
            if (count == 0) throw UnsupportedAudioException("No audio could be read from this file.")
            return Pcm(rate, out.copyOf(count))
        } finally {
            extractor.release()
        }
    }
}
