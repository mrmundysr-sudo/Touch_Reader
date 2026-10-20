package com.mundymorning.touchreader.core.voicepacks

import org.json.JSONObject

/** One file of a multi-file pack, fetched straight from its original host. */
data class PackFile(val path: String, val url: String, val sizeBytes: Long, val sha256: String)

/**
 * One downloadable voice/model package. Every field that matters for legal and integrity reasons
 * (license, checksum, size) is mandatory: an entry missing any of them is rejected, not guessed.
 *
 * A pack is either a single .zip ([url]) or a list of [files] (preferred when the upstream host serves
 * the files individually, so Touch Reader never re-hosts the weights).
 */
data class VoicePack(
    val id: String,
    val name: String,
    val description: String,
    /** Which synthesizer understands the files, e.g. "supertonic". */
    val engine: String,
    val language: String,
    val version: Int,
    /** Archive URL for zip packs; empty for file-list packs. */
    val url: String,
    /** Total bytes to download. */
    val sizeBytes: Long,
    /** Declared unpacked size; install refuses archives that expand beyond it (zip-bomb guard). */
    val unpackedBytes: Long,
    /** Archive checksum for zip packs; empty for file-list packs (each file has its own). */
    val sha256: String,
    val license: String,
    val licenseUrl: String?,
    val attribution: String?,
    val files: List<PackFile> = emptyList(),
    /** Terms the user must accept before downloading (e.g. use restrictions that must be passed on). */
    val notice: String? = null,
    val requiresAcceptance: Boolean = false,
    /** Ids of packs that must be installed first (e.g. an add-on that works on top of a voice). */
    val requires: List<String> = emptyList(),
) {
    val isFileList get() = files.isNotEmpty()
}

data class CatalogResult(val packs: List<VoicePack>, val rejected: List<String>)

object VoicePackCatalog {
    private val idPattern = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")
    private val shaPattern = Regex("^[0-9a-f]{64}$")

    /** Parses {"packs":[...]}; invalid entries are reported in [CatalogResult.rejected]. */
    fun parse(json: String): CatalogResult {
        val array = JSONObject(json).optJSONArray("packs") ?: return CatalogResult(emptyList(), listOf("missing 'packs' array"))
        val packs = mutableListOf<VoicePack>()
        val rejected = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            val label = o.optString("id", "#$i")
            val problem = validate(o)
            if (problem != null) { rejected += "$label: $problem"; continue }
            val files = o.optJSONArray("files")?.let { fa ->
                (0 until fa.length()).map { k ->
                    val f = fa.getJSONObject(k)
                    PackFile(f.getString("path"), f.getString("url"), f.getLong("sizeBytes"), f.getString("sha256").lowercase())
                }
            }.orEmpty()
            val total = if (files.isNotEmpty()) files.sumOf { it.sizeBytes } else o.getLong("sizeBytes")
            packs += VoicePack(
                id = o.getString("id"), name = o.getString("name"), description = o.optString("description"),
                engine = o.getString("engine"), language = o.optString("language"), version = o.optInt("version", 1),
                url = o.optString("url"), sizeBytes = total,
                unpackedBytes = if (files.isNotEmpty()) total else o.getLong("unpackedBytes"),
                sha256 = o.optString("sha256").lowercase(), license = o.getString("license"),
                licenseUrl = o.optString("licenseUrl").ifEmpty { null }, attribution = o.optString("attribution").ifEmpty { null },
                files = files, notice = o.optString("notice").ifEmpty { null },
                requiresAcceptance = o.optBoolean("requiresAcceptance", false),
                requires = o.optJSONArray("requires")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
            )
        }
        return CatalogResult(packs, rejected)
    }

    private fun validate(o: JSONObject): String? {
        if (!idPattern.matches(o.optString("id"))) return "bad id"
        if (o.optString("name").isBlank()) return "missing name"
        if (o.optString("engine").isBlank()) return "missing engine"
        if (o.optString("license").isBlank()) return "missing license"
        if (o.optBoolean("requiresAcceptance", false) && o.optString("notice").isBlank()) return "requiresAcceptance without a notice"
        val files = o.optJSONArray("files")
        if (files != null && files.length() > 0) {
            val seen = HashSet<String>()
            for (k in 0 until files.length()) {
                val f = files.optJSONObject(k) ?: return "file #$k is not an object"
                val path = f.optString("path")
                if (path.isBlank() || path.startsWith("/") || path.split('/').any { it == ".." || it.isEmpty() }) return "file #$k has an unsafe path"
                if (!seen.add(path)) return "duplicate file path $path"
                if (!f.optString("url").startsWith("https://")) return "file '$path' url must be https"
                if (f.optLong("sizeBytes") <= 0) return "file '$path' missing sizeBytes"
                if (!shaPattern.matches(f.optString("sha256").lowercase())) return "file '$path' missing or malformed sha256"
            }
            return null
        }
        if (!o.optString("url").startsWith("https://")) return "url must be https"
        if (o.optLong("sizeBytes") <= 0) return "missing sizeBytes"
        if (o.optLong("unpackedBytes") <= 0) return "missing unpackedBytes"
        if (!shaPattern.matches(o.optString("sha256").lowercase())) return "missing or malformed sha256"
        return null
    }
}
