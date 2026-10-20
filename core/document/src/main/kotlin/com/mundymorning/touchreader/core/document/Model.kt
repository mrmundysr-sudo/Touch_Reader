package com.mundymorning.touchreader.core.document

/** A paragraph-level unit of text. [level] is 0 for body text, 1..6 for headings. */
data class Block(val text: String, val level: Int = 0) {
    val isHeading: Boolean get() = level > 0
}

data class Chapter(val title: String, val blocks: List<Block>)

data class ParsedDocument(
    val title: String,
    val author: String?,
    val chapters: List<Chapter>,
) {
    val characterCount: Int get() = chapters.sumOf { c -> c.blocks.sumOf { it.text.length } }
}

/** User-adjustable text cleanup applied while importing. */
data class CleanupOptions(
    val removeHeadersAndFooters: Boolean = true,
    val joinHyphenatedWords: Boolean = true,
    val unwrapLines: Boolean = true,
)

class DocumentParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Thrown for image-only documents; OCR is not implemented, so we say so instead of faking support. */
class NoTextLayerException(message: String) : Exception(message)
