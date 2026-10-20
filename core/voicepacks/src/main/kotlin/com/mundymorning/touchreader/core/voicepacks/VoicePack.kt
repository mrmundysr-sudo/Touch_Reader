package com.mundymorning.touchreader.core.voicepacks

import org.json.JSONObject

/**
 * One downloadable voice/model package. Every field that matters for legal and integrity reasons
 * (license, checksum, size) is mandatory: an entry missing any of them is rejected, not guessed.
 */
data class VoicePack(
    val id: String,
    val name: String,
    val description: String,
    /** Which synthesizer understands the files, e.g. "kokoro" or "piper". */
    val engine: String,
    val language: String,
    val version: Int,
    /** A .zip archive. */
    val url: String,
    val sizeBytes: Long,
    /** Declared unpacked size; install refuses archives that expand beyond it (zip-bomb guard). Mandatory. */
    val unpackedBytes: Long,
    val sha256: String,
    val license: String,
    val licenseUrl: String?,
    val attribution: String?,
)

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
            packs += VoicePack(
                id = o.getString("id"), name = o.getString("name"), description = o.optString("description"),
                engine = o.getString("engine"), language = o.optString("language"), version = o.optInt("version", 1),
                url = o.getString("url"), sizeBytes = o.getLong("sizeBytes"),
                unpackedBytes = o.getLong("unpackedBytes"),
                sha256 = o.getString("sha256").lowercase(), license = o.getString("license"),
                licenseUrl = o.optString("licenseUrl").ifEmpty { null }, attribution = o.optString("attribution").ifEmpty { null },
            )
        }
        return CatalogResult(packs, rejected)
    }

    private fun validate(o: JSONObject): String? {
        if (!idPattern.matches(o.optString("id"))) return "bad id"
        if (o.optString("name").isBlank()) return "missing name"
        if (o.optString("engine").isBlank()) return "missing engine"
        if (!o.optString("url").startsWith("https://")) return "url must be https"
        if (o.optLong("sizeBytes") <= 0) return "missing sizeBytes"
        if (o.optLong("unpackedBytes") <= 0) return "missing unpackedBytes"
        if (!shaPattern.matches(o.optString("sha256").lowercase())) return "missing or malformed sha256"
        if (o.optString("license").isBlank()) return "missing license"
        return null
    }
}
