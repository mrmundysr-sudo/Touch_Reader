package com.mundymorning.touchreader.core.document

/** Text heuristics shared by parsers (used by the Android PDF parser). */
object TextCleanup {
    private val digits = Regex("\\d+")
    private val pageNumberOnly = Regex("^(page\\s+)?[-–—\\[(]?\\s*(\\d{1,4}|[ivxlcdm]{1,7})\\s*[-–—\\])]?(\\s+of\\s+\\d+)?$", RegexOption.IGNORE_CASE)

    private fun key(line: String) = digits.replace(line.trim().lowercase(), "#").replace(Regex("\\s+"), " ")

    /**
     * Removes lines that repeat at the top/bottom of many pages (running headers/footers)
     * and stand-alone page numbers. [pages] is a list of pages, each a list of lines.
     */
    fun stripHeadersAndFooters(pages: List<List<String>>, edgeLines: Int = 2, threshold: Double = 0.4): List<List<String>> {
        if (pages.size < 4) return pages.map { p -> p.filterNot { pageNumberOnly.matches(it.trim()) } }
        val counts = HashMap<String, Int>()
        for (p in pages) {
            val edges = (p.take(edgeLines) + p.takeLast(edgeLines)).map(::key).filter { it.isNotEmpty() }.toSet()
            for (k in edges) counts.merge(k, 1, Int::plus)
        }
        val repeating = counts.filterValues { it >= pages.size * threshold }.keys
        return pages.map { p ->
            p.filterIndexed { i, line ->
                val atEdge = i < edgeLines || i >= p.size - edgeLines
                val t = line.trim()
                !(t.isEmpty() || pageNumberOnly.matches(t) || (atEdge && key(t) in repeating))
            }
        }
    }

    /**
     * Rebuilds paragraphs from visual lines: joins wrapped lines, repairs end-of-line hyphenation,
     * and starts a new paragraph after a sentence end followed by a short line.
     */
    fun paragraphsFromLines(lines: List<String>, options: CleanupOptions = CleanupOptions()): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        val maxLen = lines.maxOfOrNull { it.length } ?: 0
        fun flush() { if (cur.isNotBlank()) out += TextUtil.collapseSpaces(cur.toString()); cur.setLength(0) }
        for ((i, raw) in lines.withIndex()) {
            val line = raw.trim()
            if (line.isEmpty()) { flush(); continue }
            if (cur.isNotEmpty() && options.unwrapLines) {
                val prev = lines.getOrNull(i - 1)?.trim().orEmpty()
                val endsSentence = prev.lastOrNull()?.let { it in ".!?:\"”’" } == true
                val shortPrev = prev.length < maxLen * 0.7
                if (endsSentence && shortPrev) flush()
            } else if (cur.isNotEmpty()) flush()
            if (cur.isNotEmpty()) {
                if (options.joinHyphenatedWords && cur.endsWith("-") && cur.length > 1 &&
                    cur[cur.length - 2].isLetter() && line.first().isLowerCase()
                ) cur.setLength(cur.length - 1) else cur.append(' ')
            }
            cur.append(line)
        }
        flush()
        return out
    }
}
