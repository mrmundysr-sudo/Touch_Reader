package com.mundymorning.touchreader.core.voicepacks

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class InsufficientSpaceException(val needed: Long, val available: Long) : IOException("Not enough free storage: need $needed bytes, have $available")
class ChecksumMismatchException : IOException("The download is corrupt (checksum mismatch).")
class DownloadCancelledException : IOException("Download cancelled.")

/**
 * Downloads a pack with resume (HTTP Range), cancellation, free-space check and SHA-256 verification.
 * The partial file is kept on cancel/failure so the next attempt continues where it stopped.
 */
class PackDownloader(
    private val allowInsecureHttp: Boolean = false, // tests only
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    fun download(
        pack: VoicePack,
        partFile: File,
        destFile: File,
        isCancelled: () -> Boolean = { false },
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ) {
        val url = URL(pack.url)
        require(url.protocol == "https" || (allowInsecureHttp && url.protocol == "http")) { "Only https downloads are allowed." }
        partFile.parentFile?.mkdirs()
        if (partFile.length() > pack.sizeBytes) partFile.delete()

        var have = partFile.length()
        val dir = partFile.parentFile ?: File(".")
        val needed = ((pack.sizeBytes - have) * 1.1).toLong()
        if (dir.usableSpace < needed) throw InsufficientSpaceException(needed, dir.usableSpace)

        if (have < pack.sizeBytes) {
            val conn = open(url)
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
            try {
                val code = conn.responseCode
                when {
                    code == 206 -> {}
                    code == 200 -> { have = 0 } // server ignored Range: start over
                    else -> throw IOException("Server returned HTTP $code")
                }
                RandomAccessFile(partFile, "rw").use { out ->
                    out.setLength(have)
                    out.seek(have)
                    conn.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (isCancelled()) throw DownloadCancelledException()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            have += n
                            onProgress(have, pack.sizeBytes)
                            if (have > pack.sizeBytes) break
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
        }

        if (partFile.length() != pack.sizeBytes || sha256(partFile) != pack.sha256) {
            partFile.delete()
            throw ChecksumMismatchException()
        }
        destFile.parentFile?.mkdirs()
        destFile.delete()
        if (!partFile.renameTo(destFile)) throw IOException("Could not finalize download.")
        onProgress(pack.sizeBytes, pack.sizeBytes)
    }

    companion object {
        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { s ->
                val buf = ByteArray(64 * 1024)
                while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
