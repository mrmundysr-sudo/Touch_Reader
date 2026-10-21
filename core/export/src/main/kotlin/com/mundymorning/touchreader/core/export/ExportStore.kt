package com.mundymorning.touchreader.core.export

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Saved description of an audiobook in progress; lets rendering stop and continue later. */
data class ExportManifest(
    val docId: Long,
    val title: String,
    val author: String?,
    /** Identifies the text this was rendered from; a changed document means the old audio no longer matches. */
    val fingerprint: String,
    val settings: ExportSettings,
    val createdAt: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("docId", docId).put("title", title).put("author", author ?: JSONObject.NULL)
        .put("fingerprint", fingerprint).put("createdAt", createdAt)
        .put(
            "settings",
            JSONObject().put("voice", settings.voice).put("steps", settings.steps).put("bitrateKbps", settings.bitrateKbps)
                .put("chapters", JSONArray(settings.chapters)).put("announceChapters", settings.announceChapters)
                .put("speed", settings.speed.toDouble())
                .put("cloneEngine", settings.cloneEngine).put("refStartSeconds", settings.refStartSeconds).put("pacePercent", settings.pacePercent).put("flow", settings.flow),
        )

    companion object {
        fun fromJson(o: JSONObject): ExportManifest {
            val s = o.getJSONObject("settings")
            val ch = s.getJSONArray("chapters")
            return ExportManifest(
                o.getLong("docId"), o.getString("title"), if (o.isNull("author")) null else o.getString("author"),
                o.getString("fingerprint"),
                ExportSettings(
                    s.getString("voice"), s.getInt("steps"), s.getInt("bitrateKbps"),
                    List(ch.length()) { ch.getInt(it) }, s.optBoolean("announceChapters", true), s.optDouble("speed", 1.0).toFloat(),
                    s.optString("cloneEngine", ExportSettings.CLONE_OPENVOICE), s.optInt("refStartSeconds", 0), s.optInt("pacePercent", 100), s.optBoolean("flow", false),
                ),
                o.getLong("createdAt"),
            )
        }
    }
}

/**
 * Files of one audiobook, in one folder. A piece (chunk) only counts as finished once its audio file has
 * been renamed into place, so stopping at any moment, or the app being killed, never leaves a half-written
 * piece behind. When every chunk of a chapter exists they are joined into one chapter file.
 */
class ExportStore(val root: File, cleanTemp: Boolean = true) {
    private val chunks = File(root, "chunks")
    private val chapters = File(root, "chapters")
    private val manifestFile = File(root, "manifest.json")

    /** Pass [cleanTemp] = false when only reading while a render may be running: its half-written files are not garbage. */
    init { if (cleanTemp) clearTemp() }

    fun readManifest(): ExportManifest? =
        runCatching { ExportManifest.fromJson(JSONObject(manifestFile.readText())) }.getOrNull()

    fun writeManifest(m: ExportManifest) = atomicWrite(manifestFile, m.toJson().toString().toByteArray())

    fun chunkAudio(spec: ChunkSpec) = File(chunks, spec.key + ".aac")
    private fun chunkTimes(spec: ChunkSpec) = File(chunks, spec.key + ".json")
    fun chapterAudio(chapter: Int) = File(chapters, "ch%04d.aac".format(chapter))
    fun chapterTimes(chapter: Int) = File(chapters, "ch%04d.json".format(chapter))
    val finalFile: File get() = File(root, "audiobook.m4b")

    fun isChapterDone(chapter: Int) = chapterAudio(chapter).isFile
    fun isChunkDone(spec: ChunkSpec) = isChapterDone(spec.chapter) || chunkAudio(spec).isFile

    fun pending(plan: List<ChunkSpec>): List<ChunkSpec> = plan.filterNot { isChunkDone(it) }

    fun doneChapters(plan: List<ChunkSpec>): List<Int> =
        plan.map { it.chapter }.distinct().filter { isChapterDone(it) }

    /**
     * Saves a finished chunk. [sentenceStartsMs] gives, for each sentence index in the chunk (and -1 for the
     * spoken title), where it starts inside the chunk's audio.
     */
    fun commitChunk(spec: ChunkSpec, adts: ByteArray, sentenceStartsMs: Map<Int, Long>) {
        val t = JSONObject()
        for ((k, v) in sentenceStartsMs) t.put(k.toString(), v)
        atomicWrite(chunkTimes(spec), t.toString().toByteArray())
        atomicWrite(chunkAudio(spec), adts) // the audio file is the commit point
    }

    /** Joins the chunks of [chapter] into its chapter file once all of them exist. Returns true if the chapter is now complete. */
    fun assembleChapter(plan: List<ChunkSpec>, chapter: Int): Boolean {
        if (isChapterDone(chapter)) return true
        val mine = plan.filter { it.chapter == chapter }.sortedBy { it.index }
        if (mine.isEmpty() || !mine.all { chunkAudio(it).isFile }) return false
        val times = JSONObject()
        var offsetMs = 0L
        chapters.mkdirs()
        val tmp = File(chapters, "ch%04d.aac.tmp".format(chapter))
        tmp.outputStream().buffered().use { out ->
            for (spec in mine) {
                val ms = Adts.scan(chunkAudio(spec)).durationMs
                runCatching { JSONObject(chunkTimes(spec).readText()) }.getOrNull()?.let { o ->
                    for (k in o.keys()) times.put(k, o.getLong(k) + offsetMs)
                }
                chunkAudio(spec).inputStream().use { it.copyTo(out) }
                offsetMs += ms
            }
        }
        times.put("durationMs", offsetMs)
        atomicWrite(chapterTimes(chapter), times.toString().toByteArray())
        if (!tmp.renameTo(chapterAudio(chapter))) { tmp.delete(); return false }
        for (spec in mine) { chunkAudio(spec).delete(); chunkTimes(spec).delete() }
        return true
    }

    /** Start of sentence [sentence] (or -1 for the spoken title) in the chapter's audio, if known. */
    fun sentenceStartMs(chapter: Int, sentence: Int): Long? =
        runCatching { JSONObject(chapterTimes(chapter).readText()) }.getOrNull()?.takeIf { it.has(sentence.toString()) }?.getLong(sentence.toString())

    fun chapterDurationMs(chapter: Int): Long? =
        runCatching { JSONObject(chapterTimes(chapter).readText()).getLong("durationMs") }.getOrNull()

    fun bytesUsed(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun deleteAll() { root.deleteRecursively() }

    /** Removes half-written files left by a crash. */
    private fun clearTemp() {
        if (!root.exists()) return
        root.walkTopDown().filter { it.isFile && it.name.endsWith(".tmp") }.forEach { it.delete() }
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) { target.delete(); if (!tmp.renameTo(target)) { tmp.delete(); error("Could not save ${target.name}") } }
    }

    companion object {
        /** A short fingerprint of the readable text, so audio is never matched with a different document. */
        fun fingerprint(book: com.mundymorning.touchreader.core.reader.ReaderBook): String {
            var h = 1125899906842597L
            for (c in book.chapters) {
                h = 31 * h + c.sentences.size
                for (i in c.sentences.indices) { val t = c.text(i); h = 31 * h + t.length; h = 31 * h + t.hashCode() }
            }
            return java.lang.Long.toHexString(h) + "-" + book.totalSentences
        }
    }
}
