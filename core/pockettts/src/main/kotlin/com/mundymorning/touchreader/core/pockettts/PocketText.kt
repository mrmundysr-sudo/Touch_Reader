package com.mundymorning.touchreader.core.pockettts

/** Text preparation and sentence splitting exactly as the reference implementation does it. */
class PocketText(private val tokenizer: SentencePieceModel, private val bundle: PocketBundle) {
    class Prepared(val text: String, val framesAfterEosGuess: Int)

    fun prepare(raw: String): Prepared {
        var text = raw.trim()
        require(text.isNotEmpty()) { "Text cannot be empty" }
        text = text.replace("\n", " ").replace("\r", " ").replace("  ", " ")
        if (bundle.removeSemicolons) text = text.replace(";", ",")
        val words = text.split(Regex("\\s+")).count { it.isNotEmpty() }
        val guess = if (words <= 4) 3 else 1
        if (!text[0].isUpperCase()) text = text[0].uppercaseChar() + text.substring(1)
        if (text.last().isLetterOrDigit()) text += "."
        if (bundle.padWithSpacesForShortInputs && text.split(Regex("\\s+")).count { it.isNotEmpty() } < 5) text = " ".repeat(8) + text
        return Prepared(text, guess)
    }

    fun tokenize(raw: String): LongArray = tokenizer.encode(prepare(raw).text).let { ids -> LongArray(ids.size) { ids[it].toLong() } }

    private fun boundaryIndices(tokens: IntArray, boundary: Set<Int>): List<Int> {
        val out = arrayListOf(0)
        var previousWasBoundary = false
        for ((i, t) in tokens.withIndex()) {
            if (t in boundary) previousWasBoundary = true
            else { if (previousWasBoundary) out += i; previousWasBoundary = false }
        }
        out += tokens.size
        return out
    }

    private fun segments(tokens: IntArray, b: List<Int>): List<Pair<Int, String>> =
        (0 until b.size - 1).map { i -> (b[i + 1] - b[i]) to tokenizer.decode(tokens.slice(b[i] until b[i + 1])) }

    /** Splits [raw] at sentence ends (and, for long sentences, at commas) into pieces of at most `max_token_per_chunk` tokens. */
    fun splitIntoChunks(raw: String): List<String> {
        val prepared = prepare(raw).text.trim()
        val tokens = tokenizer.encode(prepared)
        val eos = tokenizer.encode(".!...?").drop(1).toSet()
        val refined = ArrayList<Pair<Int, String>>()
        for ((count, segment) in segments(tokens, boundaryIndices(tokens, eos))) {
            if (count <= bundle.maxTokensPerChunk) { refined += count to segment; continue }
            val subTokens = tokenizer.encode(segment.trim())
            val fallback = tokenizer.encode(",;:").drop(1).toSet()
            val sub = segments(subTokens, boundaryIndices(subTokens, fallback))
            if (sub.size > 1) refined += sub else refined += count to segment
        }
        val chunks = ArrayList<String>()
        var current = ""
        var currentCount = 0
        for ((count, segment) in refined) {
            if (current.isEmpty()) { current = segment; currentCount = count; continue }
            if (currentCount + count > bundle.maxTokensPerChunk) { chunks += current.trim(); current = segment; currentCount = count }
            else { current += " $segment"; currentCount += count }
        }
        if (current.isNotEmpty()) chunks += current.trim()
        return chunks
    }
}
