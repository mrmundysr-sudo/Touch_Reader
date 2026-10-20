package com.mundymorning.touchreader.core.supertonic

import java.text.Normalizer
import java.util.regex.Pattern

object SupertonicLanguages {
    val AVAILABLE = listOf(
        "en", "ko", "ja", "ar", "bg", "cs", "da", "de", "el", "es", "et", "fi", "fr", "hi", "hr", "hu", "id", "it",
        "lt", "lv", "nl", "pl", "pt", "ro", "ru", "sk", "sl", "sv", "tr", "uk", "vi",
        "na", // language-agnostic
    )

    fun isValid(lang: String) = lang in AVAILABLE
}

/**
 * Text frontend. Ported from the MIT-licensed Supertonic Java sample (ExampleONNX/Helper): the model reads
 * Unicode characters directly, so there is no phonemizer (and no espeak-ng) involved.
 */
object SupertonicText {
    const val DEFAULT_MAX_CHUNK = 300
    private const val CJK_MAX_CHUNK = 120

    private val abbreviations = listOf(
        "Dr.", "Mr.", "Mrs.", "Ms.", "Prof.", "Sr.", "Jr.", "St.", "Ave.", "Rd.", "Blvd.", "Dept.", "Inc.", "Ltd.",
        "Co.", "Corp.", "etc.", "vs.", "i.e.", "e.g.", "Ph.D.",
    )

    private val symbolReplacements = linkedMapOf(
        "–" to "-", "‑" to "-", "—" to "-", "_" to " ",
        "“" to "\"", "”" to "\"", "‘" to "'", "’" to "'", "´" to "'", "`" to "'",
        "[" to " ", "]" to " ", "|" to " ", "/" to " ", "#" to " ", "→" to " ", "←" to " ",
    )
    private val expressionReplacements = linkedMapOf("@" to " at ", "e.g.," to "for example, ", "i.e.," to "that is, ")
    private val removedSymbols = Regex("[♥☆♡©\\\\]")
    private val endsWithPunctuation = Regex(".*[.!?;:,'\"\\u201C\\u201D\\u2018\\u2019)\\]}…。」』】〉》›»]$")
    private val sentenceSplit: Pattern = Pattern.compile(
        "(?<!(?:" + abbreviations.joinToString("|") { Pattern.quote(it) } + "))(?<=[.!?])\\s+"
    )

    private val emojiRanges = listOf(
        0x1F600..0x1F64F, 0x1F300..0x1F5FF, 0x1F680..0x1F6FF, 0x1F700..0x1F77F, 0x1F780..0x1F7FF,
        0x1F800..0x1F8FF, 0x1F900..0x1F9FF, 0x1FA00..0x1FA6F, 0x1FA70..0x1FAFF, 0x2600..0x26FF,
        0x2700..0x27BF, 0x1F1E6..0x1F1FF,
    )

    private fun removeEmojis(text: String): String {
        val sb = StringBuilder()
        text.codePoints().forEach { cp -> if (emojiRanges.none { cp in it }) sb.appendCodePoint(cp) }
        return sb.toString()
    }

    /** Normalizes [text] and wraps it in language tags, exactly as the model expects. */
    fun preprocess(text: String, lang: String): String {
        require(SupertonicLanguages.isValid(lang)) { "Unsupported language: $lang" }
        var t = Normalizer.normalize(text, Normalizer.Form.NFKD)
        t = removeEmojis(t)
        symbolReplacements.forEach { (k, v) -> t = t.replace(k, v) }
        t = removedSymbols.replace(t, "")
        expressionReplacements.forEach { (k, v) -> t = t.replace(k, v) }
        for (p in listOf(" ,", " .", " !", " ?", " ;", " :", " '")) t = t.replace(p, p.trim())
        while (t.contains("\"\"")) t = t.replace("\"\"", "\"")
        while (t.contains("''")) t = t.replace("''", "'")
        while (t.contains("``")) t = t.replace("``", "`")
        t = t.replace(Regex("\\s+"), " ").trim()
        if (!endsWithPunctuation.matches(t)) t += "."
        return "<$lang>$t</$lang>"
    }

    fun maxChunkFor(lang: String) = if (lang == "ko" || lang == "ja") CJK_MAX_CHUNK else DEFAULT_MAX_CHUNK

    /** Splits long text on paragraphs, sentences, commas, then words so no chunk exceeds [maxLen] characters. */
    fun chunk(text: String, maxLen: Int = DEFAULT_MAX_CHUNK): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return listOf("")
        val chunks = mutableListOf<String>()
        for (rawPara in trimmed.split(Regex("\\n\\s*\\n"))) {
            val para = rawPara.trim()
            if (para.isEmpty()) continue
            if (para.length <= maxLen) { chunks += para; continue }
            val current = StringBuilder()
            fun flush() { if (current.isNotEmpty()) { chunks += current.toString().trim(); current.setLength(0) } }
            for (rawSentence in sentenceSplit.split(para)) {
                val sentence = rawSentence.trim()
                if (sentence.isEmpty()) continue
                if (sentence.length > maxLen) {
                    flush()
                    for (rawPart in sentence.split(",")) {
                        val part = rawPart.trim()
                        if (part.isEmpty()) continue
                        if (part.length > maxLen) {
                            val words = StringBuilder()
                            for (word in part.split(Regex("\\s+"))) {
                                if (words.length + word.length + 1 > maxLen && words.isNotEmpty()) {
                                    chunks += words.toString().trim(); words.setLength(0)
                                }
                                if (words.isNotEmpty()) words.append(' ')
                                words.append(word)
                            }
                            if (words.isNotEmpty()) chunks += words.toString().trim()
                        } else {
                            if (current.length + part.length + 1 > maxLen && current.isNotEmpty()) flush()
                            if (current.isNotEmpty()) current.append(", ")
                            current.append(part)
                        }
                    }
                    continue
                }
                if (current.length + sentence.length + 1 > maxLen && current.isNotEmpty()) flush()
                if (current.isNotEmpty()) current.append(' ')
                current.append(sentence)
            }
            flush()
        }
        return chunks.ifEmpty { listOf("") }
    }
}

/** Maps Unicode code points to model token ids using the pack's unicode_indexer.json. */
class UnicodeIndexer(private val table: LongArray) {
    /** Characters outside the table (or mapped to a negative id) are dropped instead of crashing. */
    fun encode(text: String): LongArray {
        val out = ArrayList<Long>(text.length)
        text.codePoints().forEach { cp ->
            if (cp < table.size && table[cp] >= 0) out += table[cp]
        }
        return out.toLongArray()
    }
}
