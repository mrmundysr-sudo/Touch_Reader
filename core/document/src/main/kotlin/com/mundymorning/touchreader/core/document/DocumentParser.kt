package com.mundymorning.touchreader.core.document

import java.io.File

/** Converts one file format into the neutral [ParsedDocument] model. */
interface DocumentParser {
    val formatName: String
    val extensions: Set<String>
    fun parse(file: File, fallbackTitle: String, options: CleanupOptions = CleanupOptions()): ParsedDocument
}

/** Registry so new formats (and the Android-only PDF parser) can be plugged in. */
class DocumentParsers(parsers: List<DocumentParser>) {
    private val all = parsers

    val supportedExtensions: Set<String> get() = all.flatMap { it.extensions }.toSet()

    fun forFileName(name: String): DocumentParser? {
        val ext = name.substringAfterLast('.', "").lowercase()
        return all.firstOrNull { ext in it.extensions }
    }

    companion object {
        /** Parsers that need only the JVM. */
        fun jvmDefaults() = listOf(
            TxtParser(), MarkdownParser(), HtmlParser(), EpubParser(), DocxParser(),
        )
    }
}

internal fun titleFromFileName(name: String): String =
    name.substringBeforeLast('.').replace('_', ' ').trim().ifEmpty { "Untitled" }
