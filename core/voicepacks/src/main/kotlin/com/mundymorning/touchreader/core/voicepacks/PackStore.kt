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
