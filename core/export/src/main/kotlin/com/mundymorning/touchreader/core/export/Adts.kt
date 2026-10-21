package com.mundymorning.touchreader.core.export

import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * AAC-LC in ADTS framing. Rendered audio is stored this way because ADTS files can simply be concatenated
 * (so chunks join into chapters with a file copy) and Android can play them directly.
 */
object Adts {
    const val SAMPLES_PER_FRAME = 1024
    const val HEADER_SIZE = 7
    private val SAMPLE_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    fun frequencyIndex(sampleRate: Int): Int =
        SAMPLE_RATES.indexOf(sampleRate).also { require(it >= 0) { "Unsupported AAC sample rate $sampleRate" } }

    /** The 7-byte ADTS header (MPEG-4, AAC-LC, no CRC) for a raw AAC frame of [payloadSize] bytes. */
    fun header(payloadSize: Int, sampleRate: Int, channels: Int): ByteArray {
        val len = payloadSize + HEADER_SIZE
        require(len < (1 shl 13)) { "AAC frame too large for ADTS: $payloadSize" }
        val f = frequencyIndex(sampleRate)
        return byteArrayOf(
            0xFF.toByte(),
            0xF1.toByte(),
            (((2 - 1) shl 6) or (f shl 2) or (channels shr 2 and 1)).toByte(),
            (((channels and 3) shl 6) or (len shr 11 and 3)).toByte(),
            (len shr 3 and 0xFF).toByte(),
            (((len and 7) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        )
    }

    fun wrap(frames: List<ByteArray>, sampleRate: Int, channels: Int, out: OutputStream) {
        for (fr in frames) { out.write(header(fr.size, sampleRate, channels)); out.write(fr) }
    }

    /** Result of reading an ADTS stream: its format and the payload size of every frame, in order. */
    class Scan(val sampleRate: Int, val channels: Int, val frameSizes: IntArray) {
        val frames: Int get() = frameSizes.size
        val durationMs: Long get() = if (sampleRate == 0) 0 else frames.toLong() * SAMPLES_PER_FRAME * 1000 / sampleRate
    }

    /** Streams every frame's payload (header removed) to [onFrame]; returns the stream's format. */
    fun read(input: InputStream, onFrame: (ByteArray) -> Unit): Scan {
        val sizes = ArrayList<Int>()
        var sampleRate = 0
        var channels = 0
        val hdr = ByteArray(HEADER_SIZE)
        while (true) {
            val got = readFully(input, hdr, 0, HEADER_SIZE)
            if (got == 0) break
            if (got < HEADER_SIZE) throw IOException("Truncated ADTS header")
            if (hdr[0] != 0xFF.toByte() || (hdr[1].toInt() and 0xF6) != 0xF0) throw IOException("Not an ADTS frame")
            val protectionAbsent = (hdr[1].toInt() and 1) == 1
            val freqIdx = (hdr[2].toInt() shr 2) and 0xF
            val ch = ((hdr[2].toInt() and 1) shl 2) or ((hdr[3].toInt() shr 6) and 3)
            val total = ((hdr[3].toInt() and 3) shl 11) or ((hdr[4].toInt() and 0xFF) shl 3) or ((hdr[5].toInt() shr 5) and 7)
            val headerLen = if (protectionAbsent) HEADER_SIZE else HEADER_SIZE + 2
            if (freqIdx >= SAMPLE_RATES.size || total < headerLen) throw IOException("Corrupt ADTS frame")
            if (!protectionAbsent) input.skip(2)
            val payload = ByteArray(total - headerLen)
            if (readFully(input, payload, 0, payload.size) < payload.size) throw IOException("Truncated ADTS frame")
            val rate = SAMPLE_RATES[freqIdx]
            if (sampleRate == 0) { sampleRate = rate; channels = ch }
            else if (rate != sampleRate || ch != channels) throw IOException("ADTS stream changes format")
            sizes += payload.size
            onFrame(payload)
        }
        return Scan(sampleRate, channels, sizes.toIntArray())
    }

    fun scan(file: File): Scan = BufferedInputStream(file.inputStream(), 1 shl 16).use { read(it) { } }

    private fun readFully(input: InputStream, buf: ByteArray, off: Int, len: Int): Int {
        var n = 0
        while (n < len) { val r = input.read(buf, off + n, len - n); if (r < 0) break; n += r }
        return n
    }
}
