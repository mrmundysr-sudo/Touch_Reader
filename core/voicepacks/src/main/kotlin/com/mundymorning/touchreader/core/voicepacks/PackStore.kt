package com.mundymorning.touchreader.core.voicepacks

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

data class InstalledPack(val id: String, val version: Int, val name: String, val license: String, val dir: File)

/**
 * Installed packs live under `<root>/installed/<id>/`. Archives are unpacked to a temp folder first and
 * moved into place only when complete, so a crash never leaves a half-installed pack that looks valid.
 */
class PackStore(private val root: File) {
    private val installedDir = File(root, "installed")
    val downloadsDir = File(root, "downloads")

    fun partFile(pack: VoicePack) = File(downloadsDir, "${pack.id}-${pack.version}.zip.part")
    fun archiveFile(pack: VoicePack) = File(downloadsDir, "${pack.id}-${pack.version}.zip")

    fun installed(): List<InstalledPack> =
        installedDir.listFiles { f -> f.isDirectory && File(f, META).isFile }.orEmpty().mapNotNull { dir ->
            try {
                val m = JSONObject(File(dir, META).readText())
                InstalledPack(m.getString("id"), m.getInt("version"), m.getString("name"), m.getString("license"), dir)
            } catch (e: Exception) { null }
        }.sortedBy { it.name }

    fun get(id: String): InstalledPack? = installed().firstOrNull { it.id == id }

    fun sizeOnDisk(id: String): Long = get(id)?.dir?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    fun delete(id: String): Boolean = get(id)?.dir?.deleteRecursively() ?: false

    /** Unpack [archive] for [pack]. Rejects path traversal and archives that expand beyond the declared bound. */
    fun install(pack: VoicePack, archive: File) {
        installedDir.mkdirs()
        val tmp = File(installedDir, ".tmp-${pack.id}").also { it.deleteRecursively(); it.mkdirs() }
        try {
            var total = 0L
            ZipInputStream(archive.inputStream().buffered()).use { zin ->
                val base = tmp.canonicalFile
                while (true) {
                    val e = zin.nextEntry ?: break
                    val out = File(tmp, e.name).canonicalFile
                    if (out != base && !out.path.startsWith(base.path + File.separator)) {
                        throw IOException("Unsafe path in archive: ${e.name}")
                    }
                    if (e.isDirectory) { out.mkdirs(); continue }
                    out.parentFile?.mkdirs()
                    out.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = zin.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > pack.unpackedBytes) throw IOException("Archive is larger than declared.")
                            o.write(buf, 0, n)
                        }
                    }
                }
            }
            File(tmp, META).writeText(
                JSONObject().put("id", pack.id).put("version", pack.version).put("name", pack.name)
                    .put("engine", pack.engine).put("license", pack.license)
                    .put("licenseUrl", pack.licenseUrl ?: JSONObject.NULL).put("installedAt", System.currentTimeMillis()).toString()
            )
            val finalDir = File(installedDir, pack.id)
            finalDir.deleteRecursively()
            if (!tmp.renameTo(finalDir)) throw IOException("Could not move the pack into place.")
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun stagingDir(pack: VoicePack) = File(downloadsDir, "${pack.id}-${pack.version}.files")

    /**
     * File-list packs: download every file (resumable, each verified against its own SHA-256) into a staging
     * folder, then move the finished folder into place in one step. Files that are already complete are skipped,
     * so an interrupted install continues instead of starting over.
     */
    fun installFiles(
        pack: VoicePack,
        downloader: PackDownloader = PackDownloader(),
        isCancelled: () -> Boolean = { false },
        onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ) {
        require(pack.isFileList) { "Not a file-list pack" }
        val staging = stagingDir(pack).also { it.mkdirs() }
        val remaining = pack.files.sumOf { f ->
            val done = File(staging, f.path)
            if (done.isFile && done.length() == f.sizeBytes) 0L else f.sizeBytes - File(staging, f.path + ".part").length().coerceAtMost(f.sizeBytes)
        }
        val needed = (remaining * 1.1).toLong()
        if (staging.usableSpace < needed) throw InsufficientSpaceException(needed, staging.usableSpace)

        var finished = 0L
        for (f in pack.files) {
            val dest = File(staging, f.path)
            dest.parentFile?.mkdirs()
            if (dest.isFile && dest.length() == f.sizeBytes && PackDownloader.sha256(dest) == f.sha256) {
                finished += f.sizeBytes; onProgress(finished, pack.sizeBytes); continue
            }
            val base = finished
            downloader.downloadFile(f.url, f.sizeBytes, f.sha256, File(staging, f.path + ".part"), dest, isCancelled) { d, _ ->
                onProgress(base + d, pack.sizeBytes)
            }
            finished += f.sizeBytes
        }
        installedDir.mkdirs()
        val finalDir = File(installedDir, pack.id)
        finalDir.deleteRecursively()
        File(staging, META).writeText(
            JSONObject().put("id", pack.id).put("version", pack.version).put("name", pack.name)
                .put("engine", pack.engine).put("license", pack.license)
                .put("licenseUrl", pack.licenseUrl ?: JSONObject.NULL).put("installedAt", System.currentTimeMillis()).toString()
        )
        if (!staging.renameTo(finalDir)) throw IOException("Could not move the pack into place.")
    }

    /** Download (resumable), verify, install, then remove the archive. */
    fun installFromNetwork(
        pack: VoicePack,
        downloader: PackDownloader = PackDownloader(),
        isCancelled: () -> Boolean = { false },
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ) {
        val archive = archiveFile(pack)
        downloader.download(pack, partFile(pack), archive, isCancelled, onProgress)
        try { install(pack, archive) } finally { archive.delete() }
    }

    private companion object { const val META = "pack.json" }
}
