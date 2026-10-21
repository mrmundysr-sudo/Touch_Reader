package com.mundymorning.touchreader.core.export

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.OutputStream

class ChapterMark(val title: String, val startMs: Long)

/** The audio of one audiobook: a list of ADTS files played back to back. */
class AdtsFilesSource(private val files: List<File>) {
    val scans: List<Adts.Scan> = files.map { Adts.scan(it) }
    val sampleRate: Int = scans.firstOrNull { it.frames > 0 }?.sampleRate ?: 0
    val channels: Int = scans.firstOrNull { it.frames > 0 }?.channels ?: 1
    init {
        require(scans.all { it.frames == 0 || (it.sampleRate == sampleRate && it.channels == channels) }) { "Audio files differ in format" }
    }
    val frameSizes: IntArray = scans.flatMap { it.frameSizes.asList() }.toIntArray()

    fun startMs(fileIndex: Int): Long =
        scans.take(fileIndex).sumOf { it.frames.toLong() } * Adts.SAMPLES_PER_FRAME * 1000 / sampleRate

    fun writeFramesTo(out: OutputStream) {
        for (f in files) BufferedInputStream(f.inputStream(), 1 shl 16).use { Adts.read(it) { payload -> out.write(payload) } }
    }
}

/**
 * Writes an audiobook (.m4b): AAC-LC audio in an MP4 container with the index at the front (so it starts
 * playing at once), a chapter list (Nero `chpl`, read by most audiobook players) and basic tags.
 */
object M4bWriter {
    private const val FRAMES_PER_CHUNK = 21 // ~0.5 s per MP4 chunk

    fun write(out: OutputStream, audio: AdtsFilesSource, chapters: List<ChapterMark>, title: String, author: String?) {
        require(audio.frameSizes.isNotEmpty()) { "There is no audio to write" }
        val sizes = audio.frameSizes
        val payload = sizes.fold(0L) { a, b -> a + b }
        require(payload < 0xFFFF_FF00L) { "Audiobook too large for a single MP4 file" }
        val ftyp = ftyp()
        val measured = moov(audio, chapters, title, author, mdatDataStart = 0L, payload)
        val dataStart = ftyp.size + measured.size + 8L
        require(dataStart + payload < 0xFFFF_FFFFL) { "Audiobook too large for a single MP4 file" }
        val moov = moov(audio, chapters, title, author, dataStart, payload)
        out.write(ftyp)
        out.write(moov)
        DataOutputStream(out).apply { writeInt((payload + 8).toInt()); writeBytes("mdat") }
        audio.writeFramesTo(out)
        out.flush()
    }

    private fun ftyp() = box("ftyp") { writeBytes("M4B "); writeInt(0); writeBytes("M4B "); writeBytes("mp42"); writeBytes("isom") }

    private fun moov(audio: AdtsFilesSource, chapters: List<ChapterMark>, title: String, author: String?, mdatDataStart: Long, payload: Long): ByteArray {
        val sr = audio.sampleRate
        val frames = audio.frameSizes.size
        val duration = frames.toLong() * Adts.SAMPLES_PER_FRAME
        val seconds1904 = (System.currentTimeMillis() / 1000) + 2082844800L
        return box("moov") {
            write(box("mvhd") {
                writeInt(0); writeInt(seconds1904.toInt()); writeInt(seconds1904.toInt()); writeInt(sr); writeInt(duration.toInt())
                writeInt(0x00010000); writeShort(0x0100); writeShort(0); writeInt(0); writeInt(0)
                writeMatrix(); repeat(6) { writeInt(0) }; writeInt(2)
            })
            write(box("trak") {
                write(box("tkhd") {
                    writeInt(0x00000003); writeInt(seconds1904.toInt()); writeInt(seconds1904.toInt()); writeInt(1); writeInt(0); writeInt(duration.toInt())
                    writeInt(0); writeInt(0); writeShort(0); writeShort(0); writeShort(0x0100); writeShort(0)
                    writeMatrix(); writeInt(0); writeInt(0)
                })
                write(box("mdia") {
                    write(box("mdhd") { writeInt(0); writeInt(seconds1904.toInt()); writeInt(seconds1904.toInt()); writeInt(sr); writeInt(duration.toInt()); writeShort(0x55C4); writeShort(0) })
                    write(box("hdlr") { writeInt(0); writeInt(0); writeBytes("soun"); writeInt(0); writeInt(0); writeInt(0); writeBytes("SoundHandler"); writeByte(0) })
                    write(box("minf") {
                        write(box("smhd") { writeInt(0); writeShort(0); writeShort(0) })
                        write(box("dinf") { write(box("dref") { writeInt(0); writeInt(1); write(box("url ") { writeInt(1) }) }) })
                        write(box("stbl") {
                            write(box("stsd") { writeInt(0); writeInt(1); write(mp4a(audio, payload, duration)) })
                            write(box("stts") { writeInt(0); writeInt(1); writeInt(frames); writeInt(Adts.SAMPLES_PER_FRAME) })
                            write(stsc(frames))
                            write(box("stsz") { writeInt(0); writeInt(0); writeInt(frames); audio.frameSizes.forEach { writeInt(it) } })
                            write(stco(audio.frameSizes, mdatDataStart))
                        })
                    })
                })
            })
            write(box("udta") {
                if (chapters.isNotEmpty()) write(chpl(chapters))
                write(meta(title, author))
            })
        }
    }

    private fun mp4a(audio: AdtsFilesSource, payload: Long, duration: Long): ByteArray = box("mp4a") {
        repeat(6) { writeByte(0) }; writeShort(1)
        writeInt(0); writeInt(0)
        writeShort(audio.channels); writeShort(16); writeShort(0); writeShort(0)
        writeInt(audio.sampleRate shl 16)
        val seconds = duration.toDouble() / audio.sampleRate
        val avg = if (seconds > 0) (payload * 8 / seconds).toInt() else 0
        val asc = byteArrayOf(
            ((2 shl 3) or (Adts.frequencyIndex(audio.sampleRate) shr 1)).toByte(),
            (((Adts.frequencyIndex(audio.sampleRate) and 1) shl 7) or (audio.channels shl 3)).toByte(),
        )
        write(box("esds") {
            writeInt(0)
            writeByte(0x03); writeByte(23 + asc.size); writeShort(0); writeByte(0)
            writeByte(0x04); writeByte(15 + asc.size); writeByte(0x40); writeByte(0x15)
            writeByte(0); writeShort(0); writeInt(avg); writeInt(avg)
            writeByte(0x05); writeByte(asc.size); write(asc)
            writeByte(0x06); writeByte(1); writeByte(2)
        })
    }

    private fun stsc(frames: Int): ByteArray = box("stsc") {
        writeInt(0)
        val full = frames / FRAMES_PER_CHUNK
        val rest = frames % FRAMES_PER_CHUNK
        val entries = (if (full > 0) 1 else 0) + (if (rest > 0) 1 else 0)
        writeInt(entries)
        if (full > 0) { writeInt(1); writeInt(FRAMES_PER_CHUNK); writeInt(1) }
        if (rest > 0) { writeInt(full + 1); writeInt(rest); writeInt(1) }
    }

    private fun stco(sizes: IntArray, dataStart: Long): ByteArray = box("stco") {
        writeInt(0)
        val chunks = (sizes.size + FRAMES_PER_CHUNK - 1) / FRAMES_PER_CHUNK
        writeInt(chunks)
        var offset = dataStart
        var i = 0
        while (i < sizes.size) {
            writeInt(offset.toInt())
            val end = minOf(i + FRAMES_PER_CHUNK, sizes.size)
            while (i < end) { offset += sizes[i]; i++ }
        }
    }

    private fun chpl(chapters: List<ChapterMark>): ByteArray = box("chpl") {
        writeInt(0x01000000); writeInt(0)
        val list = chapters.take(255)
        writeByte(list.size)
        for (c in list) {
            writeLong(c.startMs * 10_000L)
            val t = c.title.toByteArray(Charsets.UTF_8).let { if (it.size > 255) it.copyOf(255) else it }
            writeByte(t.size); write(t)
        }
    }

    private fun meta(title: String, author: String?): ByteArray = box("meta") {
        writeInt(0)
        write(box("hdlr") { writeInt(0); writeInt(0); writeBytes("mdir"); writeBytes("appl"); writeInt(0); writeInt(0); writeByte(0) })
        write(box("ilst") {
            write(textItem("©nam", title))
            write(textItem("©alb", title))
            author?.takeIf { it.isNotBlank() }?.let { write(textItem("©ART", it)) }
            write(textItem("©cmt", "Machine-generated speech created with Touch Reader"))
            write(box("stik") { write(box("data") { writeInt(21); writeInt(0); writeByte(2) }) })
        })
    }

    private fun textItem(name: String, value: String): ByteArray = box(name) {
        write(box("data") { writeInt(1); writeInt(0); write(value.toByteArray(Charsets.UTF_8)) })
    }

    private fun DataOutputStream.writeMatrix() {
        writeInt(0x00010000); writeInt(0); writeInt(0); writeInt(0); writeInt(0x00010000); writeInt(0); writeInt(0); writeInt(0); writeInt(0x40000000)
    }

    private fun box(type: String, body: DataOutputStream.() -> Unit): ByteArray {
        val content = ByteArrayOutputStream()
        DataOutputStream(content).body()
        val bytes = content.toByteArray()
        val out = ByteArrayOutputStream(bytes.size + 8)
        DataOutputStream(out).apply { writeInt(bytes.size + 8); write(type.toByteArray(Charsets.ISO_8859_1)) }
        out.write(bytes)
        return out.toByteArray()
    }
}
