package com.mundymorning.touchreader.data

import android.content.Context
import android.util.JsonReader
import android.util.JsonWriter
import com.mundymorning.touchreader.core.document.Block
import com.mundymorning.touchreader.core.document.Chapter
import com.mundymorning.touchreader.core.document.ParsedDocument
import java.io.File

/**
 * Keeps each imported document's extracted text as a private JSON file (streamed, so large
 * books do not need a second full copy in memory). The user's original file is never touched.
 */
class DocumentStore(context: Context) {
    private val dir = File(context.filesDir, "documents").apply { mkdirs() }

    private fun file(id: Long) = File(dir, "$id.json")

    fun save(id: Long, doc: ParsedDocument) {
        val tmp = File(dir, "$id.json.tmp")
        JsonWriter(tmp.bufferedWriter(Charsets.UTF_8)).use { w ->
            w.beginObject()
            w.name("title").value(doc.title)
            w.name("author").value(doc.author)
            w.name("chapters").beginArray()
            for (c in doc.chapters) {
                w.beginObject()
                w.name("title").value(c.title)
                w.name("blocks").beginArray()
                for (b in c.blocks) {
                    w.beginObject().name("t").value(b.text).name("l").value(b.level.toLong()).endObject()
                }
                w.endArray().endObject()
            }
            w.endArray().endObject()
        }
        if (!tmp.renameTo(file(id))) { tmp.delete(); error("Could not save document text") }
    }

    fun load(id: Long): ParsedDocument {
        JsonReader(file(id).bufferedReader(Charsets.UTF_8)).use { r ->
            var title = ""
            var author: String? = null
            val chapters = mutableListOf<Chapter>()
            r.beginObject()
            while (r.hasNext()) when (r.nextName()) {
                "title" -> title = r.nextString()
                "author" -> if (r.peek() == android.util.JsonToken.NULL) { r.nextNull() } else author = r.nextString()
                "chapters" -> {
                    r.beginArray()
                    while (r.hasNext()) {
                        var ct = ""
                        val blocks = mutableListOf<Block>()
                        r.beginObject()
                        while (r.hasNext()) when (r.nextName()) {
                            "title" -> ct = r.nextString()
                            "blocks" -> {
                                r.beginArray()
                                while (r.hasNext()) {
                                    var t = ""; var l = 0
                                    r.beginObject()
                                    while (r.hasNext()) when (r.nextName()) {
                                        "t" -> t = r.nextString()
                                        "l" -> l = r.nextInt()
                                        else -> r.skipValue()
                                    }
                                    r.endObject()
                                    blocks += Block(t, l)
                                }
                                r.endArray()
                            }
                            else -> r.skipValue()
                        }
                        r.endObject()
                        chapters += Chapter(ct, blocks)
                    }
                    r.endArray()
                }
                else -> r.skipValue()
            }
            r.endObject()
            return ParsedDocument(title, author, chapters)
        }
    }

    fun delete(id: Long) { file(id).delete() }
}
