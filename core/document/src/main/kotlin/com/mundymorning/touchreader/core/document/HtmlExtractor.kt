package com.mundymorning.touchreader.core.document

import org.jsoup.nodes.Element

internal object HtmlExtractor {
    private val selector = "h1,h2,h3,h4,h5,h6,p,li,pre,blockquote,dt,dd,figcaption,td"
    private val skip = "script,style,nav,header,footer,aside,noscript,svg,head"

    /** Extracts readable blocks in document order, skipping scripts/navigation and nested duplicates. */
    fun blocks(root: Element): List<Block> {
        root.select(skip).remove()
        val out = mutableListOf<Block>()
        for (el in root.select(selector)) {
            // Only take the innermost block elements so text is not read twice.
            if (el.select(selector).size > 1 && el.tagName() !in setOf("p") &&
                el.children().any { it.`is`(selector) }
            ) continue
            val text = TextUtil.collapseSpaces(el.text())
            if (text.isEmpty()) continue
            val level = if (el.tagName().matches(Regex("h[1-6]"))) el.tagName()[1].digitToInt() else 0
            out += Block(text, level)
        }
        // Pages with bare text and no block tags.
        if (out.isEmpty()) {
            val t = TextUtil.collapseSpaces(root.text())
            if (t.isNotEmpty()) out += Block(t)
        }
        return out
    }
}
