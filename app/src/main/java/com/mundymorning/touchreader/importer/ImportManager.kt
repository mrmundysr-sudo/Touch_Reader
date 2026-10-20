package com.mundymorning.touchreader.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.mundymorning.touchreader.core.document.*
import com.mundymorning.touchreader.data.AppDatabase
import com.mundymorning.touchreader.data.DocumentEntity
import com.mundymorning.touchreader.data.DocumentStore
import com.mundymorning.touchreader.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

sealed interface ImportResult {
    data class Success(val id: Long, val title: String) : ImportResult
    data class Failure(val fileName: String, val message: String) : ImportResult
}

class ImportManager(
    private val context: Context,
    private val db: AppDatabase,
    private val store: DocumentStore,
    private val settings: Settings,
) {
    private val parsers = DocumentParsers(DocumentParsers.jvmDefaults() + PdfParser())

    suspend fun import(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val name = displayName(uri)
        val parser = parsers.forFileName(name)
            ?: return@withContext ImportResult.Failure(
                name, "“$name” isn't a supported type yet. Supported: ${parsers.supportedExtensions.sorted().joinToString(", ")}."
            )
        val tmpDir = File(context.cacheDir, "import").apply { mkdirs() }
        val tmp = File(tmpDir, UUID.randomUUID().toString() + "." + name.substringAfterLast('.', "bin"))
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                ?: return@withContext ImportResult.Failure(name, "The file could not be opened.")
            val parsed = parser.parse(tmp, name, settings.cleanup.value)
            val now = System.currentTimeMillis()
            val id = db.documents().insert(
                DocumentEntity(
                    title = parsed.title, author = parsed.author, format = parser.formatName,
                    sourceName = name, addedAt = now, lastOpenedAt = now, speed = settings.defaultSpeed.value,
                )
            )
            try { store.save(id, parsed) } catch (e: Exception) {
                db.documents().delete(id)
                return@withContext ImportResult.Failure(name, "Not enough storage to save this document.")
            }
            ImportResult.Success(id, parsed.title)
        } catch (e: DocumentParseException) {
            ImportResult.Failure(name, e.message ?: "The document could not be read.")
        } catch (e: NoTextLayerException) {
            ImportResult.Failure(name, e.message ?: "No text found.")
        } catch (e: OutOfMemoryError) {
            ImportResult.Failure(name, "This document is too large for this device's memory.")
        } catch (e: Exception) {
            ImportResult.Failure(name, "The document could not be imported (${e.javaClass.simpleName}).")
        } finally {
            tmp.delete()
        }
    }

    private fun displayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { return it }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "document"
    }

    /** Removes the library entry and Touch Reader's private copy of the text. The original file is never touched. */
    suspend fun remove(id: Long) = withContext(Dispatchers.IO) {
        db.documents().delete(id)
        store.delete(id)
    }
}
