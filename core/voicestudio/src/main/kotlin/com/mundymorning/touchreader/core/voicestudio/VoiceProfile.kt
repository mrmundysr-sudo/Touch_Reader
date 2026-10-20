package com.mundymorning.touchreader.core.voicestudio

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

/** Which engine a derived voice file belongs to, so profiles survive model changes without guessing. */
data class EngineData(val engine: String, val version: String, val file: String, val createdAt: Long)

data class VoiceProfile(
    val id: String,
    val name: String,
    val description: String,
    val createdAt: Long,
    /** When the user confirmed they own the recording or have permission to use the voice. */
    val consentAcknowledgedAt: Long,
    val sampleFile: String?,
    val sampleSeconds: Double,
    val qualityVerdict: Verdict,
    val engineData: List<EngineData>,
) {
    val hasSample get() = sampleFile != null
}

class ConsentRequiredException : IllegalStateException("The ownership/permission acknowledgement is required.")

/**
 * Voice profiles live under `<root>/<id>/` in private app storage: `profile.json`, `sample.wav` (the cleaned reference)
 * and optional per-engine data files. Nothing leaves the device. Writes go to a temp folder and are moved into place.
 */
class VoiceProfileStore(private val root: File, private val clock: () -> Long = System::currentTimeMillis) {
    init { root.mkdirs() }

    fun create(name: String, description: String, sample: PreparedSample, consentAcknowledged: Boolean): VoiceProfile {
        if (!consentAcknowledged) throw ConsentRequiredException()
        require(!sample.report.blocking) { "This recording is not good enough to create a voice." }
        val cleanName = name.trim().ifEmpty { "My voice" }.take(60)
        val id = UUID.randomUUID().toString()
        val now = clock()
        val tmp = File(root, ".tmp-$id").also { it.mkdirs() }
        try {
            File(tmp, SAMPLE).writeBytes(WavIo.write16(sample.audio))
            val p = VoiceProfile(id, cleanName, description.trim().take(200), now, now, SAMPLE, sample.audio.seconds, sample.report.verdict, emptyList())
            File(tmp, META).writeText(toJson(p).toString())
            if (!tmp.renameTo(File(root, id))) throw IOException("Could not save the voice.")
            return p
        } finally { tmp.deleteRecursively() }
    }

    fun list(): List<VoiceProfile> =
        root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") && File(f, META).isFile }.orEmpty()
            .mapNotNull { runCatching { fromJson(JSONObject(File(it, META).readText())) }.getOrNull() }
            .sortedByDescending { it.createdAt }

    fun get(id: String): VoiceProfile? = list().firstOrNull { it.id == id }

    fun sampleFile(id: String): File? = get(id)?.sampleFile?.let { File(File(root, id), it) }?.takeIf { it.isFile }

    fun rename(id: String, name: String): Boolean = update(id) { it.copy(name = name.trim().ifEmpty { it.name }.take(60)) }

    fun setDescription(id: String, description: String): Boolean = update(id) { it.copy(description = description.trim().take(200)) }

    /** Deletes the profile and every file belonging to it (recording and derived data). */
    fun delete(id: String): Boolean {
        val dir = File(root, id)
        if (!dir.isDirectory || dir.parentFile?.canonicalPath != root.canonicalPath) return false
        // Overwrite the recording before unlinking so a casual undelete tool finds nothing useful.
        dir.walkTopDown().filter { it.isFile && it.name.endsWith(".wav") }.forEach { f ->
            runCatching { f.outputStream().use { o -> o.write(ByteArray(minOf(f.length(), 1L shl 20).toInt())) } }
        }
        return dir.deleteRecursively()
    }

    /** Removes only the recording, keeping engine data that was already derived from it. */
    fun deleteSample(id: String): Boolean {
        val f = sampleFile(id) ?: return false
        runCatching { f.outputStream().use { o -> o.write(ByteArray(minOf(f.length(), 1L shl 20).toInt())) } }
        f.delete()
        return update(id) { it.copy(sampleFile = null) }
    }

    fun saveEngineData(id: String, engine: String, version: String, bytes: ByteArray): Boolean {
        val file = "$engine-$version.bin"
        val dir = File(root, id).takeIf { it.isDirectory } ?: return false
        File(dir, file).writeBytes(bytes)
        return update(id) { p -> p.copy(engineData = p.engineData.filterNot { it.engine == engine } + EngineData(engine, version, file, clock())) }
    }

    fun engineData(id: String, engine: String, version: String): ByteArray? {
        val d = get(id)?.engineData?.firstOrNull { it.engine == engine && it.version == version } ?: return null
        return File(File(root, id), d.file).takeIf { it.isFile }?.readBytes()
    }

    private fun update(id: String, change: (VoiceProfile) -> VoiceProfile): Boolean {
        val dir = File(root, id)
        val cur = get(id) ?: return false
        val tmp = File(dir, "$META.tmp")
        tmp.writeText(toJson(change(cur)).toString())
        return tmp.renameTo(File(dir, META)) || (File(dir, META).delete() && tmp.renameTo(File(dir, META)))
    }

    private fun toJson(p: VoiceProfile) = JSONObject()
        .put("id", p.id).put("name", p.name).put("description", p.description).put("createdAt", p.createdAt)
        .put("consentAcknowledgedAt", p.consentAcknowledgedAt).put("sampleFile", p.sampleFile ?: JSONObject.NULL)
        .put("sampleSeconds", p.sampleSeconds).put("quality", p.qualityVerdict.name)
        .put("engineData", JSONArray().also { a -> p.engineData.forEach { a.put(JSONObject().put("engine", it.engine).put("version", it.version).put("file", it.file).put("createdAt", it.createdAt)) } })

    private fun fromJson(o: JSONObject) = VoiceProfile(
        o.getString("id"), o.getString("name"), o.optString("description"), o.getLong("createdAt"), o.getLong("consentAcknowledgedAt"),
        if (o.isNull("sampleFile")) null else o.getString("sampleFile"), o.optDouble("sampleSeconds", 0.0),
        Verdict.valueOf(o.optString("quality", "USABLE")),
        o.optJSONArray("engineData")?.let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { EngineData(it.getString("engine"), it.getString("version"), it.getString("file"), it.getLong("createdAt")) } } }.orEmpty(),
    )

    private companion object { const val META = "profile.json"; const val SAMPLE = "sample.wav" }
}
