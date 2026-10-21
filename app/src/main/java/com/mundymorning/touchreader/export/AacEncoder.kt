package com.mundymorning.touchreader.export

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.mundymorning.touchreader.core.export.PcmEncoder
import java.nio.ByteOrder

/** AAC-LC mono encoder using the phone's own MediaCodec (no extra codec library, so no extra licence). */
class AacEncoder : PcmEncoder {
    override fun encode(pcm: ShortArray, sampleRate: Int, bitrateKbps: Int): List<ByteArray> {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps * 1000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val frames = ArrayList<ByteArray>()
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var position = 0
            var ptsUs = 0L
            var inputDone = false
            var outputDone = false
            var idle = 0
            while (!outputDone) {
                var progressed = false
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        buf.clear()
                        buf.order(ByteOrder.LITTLE_ENDIAN)
                        val n = minOf(buf.remaining() / 2, pcm.size - position)
                        if (n <= 0) {
                            codec.queueInputBuffer(i, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            for (k in 0 until n) buf.putShort(pcm[position + k])
                            codec.queueInputBuffer(i, 0, n * 2, ptsUs, 0)
                            position += n
                            ptsUs = position * 1_000_000L / sampleRate
                        }
                        progressed = true
                    }
                }
                val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (o >= 0) {
                    val out = codec.getOutputBuffer(o)!!
                    if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        out.position(info.offset); out.limit(info.offset + info.size)
                        frames += ByteArray(info.size).also { out.get(it) }
                    }
                    codec.releaseOutputBuffer(o, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                    progressed = true
                } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || o == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) progressed = true
                if (progressed) idle = 0 else if (++idle > MAX_IDLE_ROUNDS) error("The audio encoder stopped responding")
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
        return frames
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val MAX_IDLE_ROUNDS = 1_500 // about 30 s without any progress
    }
}
