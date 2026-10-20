package com.mundymorning.touchreader.core.voicestudio

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Mono audio as floats in [-1, 1]. */
class Pcm(val sampleRate: Int, val samples: FloatArray) {
    val seconds: Double get() = samples.size.toDouble() / sampleRate
}

class UnsupportedAudioException(message: String) : Exception(message)

/** Minimal WAV reader/writer (PCM 8/16/24/32-bit and 32-bit float in; 16-bit mono out). */
object WavIo {
    fun read(bytes: ByteArray): Pcm {
        if (bytes.size < 44 || String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") {
            throw UnsupportedAudioException("This is not a WAV file.")
        }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var format = 0; var channels = 0; var rate = 0; var bits = 0
        var dataStart = -1; var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val len = bb.getInt(pos + 4).let { if (it < 0) Int.MAX_VALUE else it }
            val body = pos + 8
            if (id == "fmt ") {
                format = bb.getShort(body).toInt() and 0xFFFF
                channels = bb.getShort(body + 2).toInt()
                rate = bb.getInt(body + 4)
                bits = bb.getShort(body + 14).toInt()
                if (format == 0xFFFE && len >= 26) format = bb.getShort(body + 24).toInt() and 0xFFFF // extensible
            } else if (id == "data") {
                dataStart = body
                dataLen = minOf(len, bytes.size - body)
                break
            }
            pos = body + len + (len and 1)
        }
        if (dataStart < 0 || channels < 1 || rate <= 0) throw UnsupportedAudioException("The WAV file is damaged.")
        val bytesPer = bits / 8
        val frames = dataLen / (bytesPer * channels)
        val out = FloatArray(frames)
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) {
                val o = dataStart + (f * channels + c) * bytesPer
                sum += when {
                    format == 1 && bits == 16 -> bb.getShort(o) / 32768f
                    format == 1 && bits == 8 -> ((bytes[o].toInt() and 0xFF) - 128) / 128f
                    format == 1 && bits == 24 -> (((bytes[o + 2].toInt() shl 16) or ((bytes[o + 1].toInt() and 0xFF) shl 8) or (bytes[o].toInt() and 0xFF)) / 8388608f)
                    format == 1 && bits == 32 -> bb.getInt(o) / 2147483648f
                    format == 3 && bits == 32 -> bb.getFloat(o)
                    else -> throw UnsupportedAudioException("Unsupported WAV format ($bits-bit, type $format).")
                }
            }
            out[f] = sum / channels
        }
        return Pcm(rate, out)
    }

    fun write16(pcm: Pcm): ByteArray {
        val data = ByteBuffer.allocate(pcm.samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in pcm.samples) data.putShort((s * 32767f).coerceIn(-32768f, 32767f).toInt().toShort())
        val out = ByteArrayOutputStream()
        fun le(v: Int, n: Int) { for (i in 0 until n) out.write((v shr (8 * i)) and 0xFF) }
        out.write("RIFF".toByteArray()); le(36 + data.capacity(), 4); out.write("WAVEfmt ".toByteArray())
        le(16, 4); le(1, 2); le(1, 2); le(pcm.sampleRate, 4); le(pcm.sampleRate * 2, 4); le(2, 2); le(16, 2)
        out.write("data".toByteArray()); le(data.capacity(), 4)
        out.write(data.array())
        return out.toByteArray()
    }
}
