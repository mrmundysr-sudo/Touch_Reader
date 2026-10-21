package com.mundymorning.touchreader.core.pockettts

import java.io.ByteArrayOutputStream

/**
 * A SentencePiece *unigram* tokenizer read straight from a `.model` file (protobuf), enough for Pocket TTS:
 * identity normalisation, a dummy "▁" prefix, spaces written as "▁", and byte fallback for unknown characters.
 * Anything else in the file (a normalisation table, other model types) is refused instead of silently mis-tokenising.
 */
class SentencePieceModel private constructor(
    private val pieces: List<String>,
    private val scores: FloatArray,
    private val types: IntArray,
    private val addDummyPrefix: Boolean,
) {
    private val index = HashMap<String, Int>()
    private val byteIds = IntArray(256) { -1 }
    private val maxPieceChars: Int
    private val unkScore: Float

    init {
        var maxLen = 1
        var minScore = Float.MAX_VALUE
        for (i in pieces.indices) when (types[i]) {
            TYPE_NORMAL, TYPE_USER_DEFINED -> {
                index.putIfAbsent(pieces[i], i)
                maxLen = maxOf(maxLen, pieces[i].codePointCount(0, pieces[i].length))
                if (types[i] == TYPE_NORMAL) minScore = minOf(minScore, scores[i])
            }
            TYPE_BYTE -> {
                val m = BYTE_PIECE.matchEntire(pieces[i])
                if (m != null) byteIds[m.groupValues[1].toInt(16)] = i
            }
        }
        maxPieceChars = maxLen
        unkScore = (if (minScore == Float.MAX_VALUE) 0f else minScore) - UNK_PENALTY
    }

    val size: Int get() = pieces.size

    fun idOf(piece: String): Int? = index[piece]

    /** Token ids for [text], the way `SentencePieceProcessor.Encode` returns them. */
    fun encode(text: String): IntArray {
        if (text.isEmpty()) return IntArray(0)
        val normalised = (if (addDummyPrefix) " $text" else text).replace(' ', WS)
        val cps = normalised.codePoints().toArray()
        val n = cps.size
        val best = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
        val prev = IntArray(n + 1)
        val id = IntArray(n + 1) // piece id that ends here, or -1 for an unknown character
        best[0] = 0.0
        for (i in 0 until n) {
            val here = best[i]
            if (here == Double.NEGATIVE_INFINITY) continue
            var hasSingle = false
            val longest = minOf(maxPieceChars, n - i)
            for (len in 1..longest) {
                val pid = index[String(cps, i, len)] ?: continue
                if (len == 1) hasSingle = true
                val s = here + scores[pid]
                if (s > best[i + len]) { best[i + len] = s; prev[i + len] = i; id[i + len] = pid }
            }
            if (!hasSingle) {
                val s = here + unkScore
                if (s > best[i + 1]) { best[i + 1] = s; prev[i + 1] = i; id[i + 1] = -1 }
            }
        }
        val out = ArrayList<Int>()
        var pos = n
        while (pos > 0) {
            val start = prev[pos]
            if (id[pos] >= 0) out += id[pos]
            else {
                // unknown character: its UTF-8 bytes as byte pieces (stored reversed because we walk backwards)
                val bytes = String(cps, start, pos - start).toByteArray(Charsets.UTF_8)
                for (k in bytes.indices.reversed()) {
                    val b = byteIds[bytes[k].toInt() and 0xFF]
                    require(b >= 0) { "This tokenizer has no byte fallback piece for a character in the text" }
                    out += b
                }
            }
            pos = start
        }
        out.reverse()
        return out.toIntArray()
    }

    /** Text for [ids], the way `SentencePieceProcessor.Decode` returns it. */
    fun decode(ids: List<Int>): String {
        val sb = StringBuilder()
        val pending = ByteArrayOutputStream()
        fun flush() { if (pending.size() > 0) { sb.append(String(pending.toByteArray(), Charsets.UTF_8)); pending.reset() } }
        for (i in ids) {
            if (i !in pieces.indices) continue
            when (types[i]) {
                TYPE_BYTE -> BYTE_PIECE.matchEntire(pieces[i])?.let { pending.write(it.groupValues[1].toInt(16)) }
                TYPE_CONTROL -> { }
                else -> { flush(); sb.append(pieces[i]) }
            }
        }
        flush()
        val text = sb.toString().replace(WS, ' ')
        return if (addDummyPrefix && text.startsWith(" ")) text.substring(1) else text
    }

    companion object {
        const val WS = '▁'
        private const val TYPE_NORMAL = 1
        private const val TYPE_CONTROL = 3
        private const val TYPE_USER_DEFINED = 4
        private const val TYPE_BYTE = 6
        private const val UNK_PENALTY = 10f
        private val BYTE_PIECE = Regex("<0x([0-9A-Fa-f]{2})>")

        fun parse(bytes: ByteArray): SentencePieceModel {
            val pieces = ArrayList<String>()
            val scores = ArrayList<Float>()
            val types = ArrayList<Int>()
            var addDummyPrefix = true
            var charsmap = 0
            var modelType = 1
            val r = Reader(bytes)
            while (!r.done()) {
                val (field, wire) = r.tag()
                if (wire != 2) { r.skip(wire); continue }
                val body = r.bytes()
                when (field) {
                    1 -> {
                        var piece = ""; var score = 0f; var type = TYPE_NORMAL
                        val p = Reader(body)
                        while (!p.done()) {
                            val (f, w) = p.tag()
                            when {
                                f == 1 && w == 2 -> piece = String(p.bytes(), Charsets.UTF_8)
                                f == 2 && w == 5 -> score = java.lang.Float.intBitsToFloat(p.fixed32())
                                f == 3 && w == 0 -> type = p.varint().toInt()
                                else -> p.skip(w)
                            }
                        }
                        pieces += piece; scores += score; types += type
                    }
                    2 -> { // trainer_spec: model_type is field 3
                        val t = Reader(body)
                        while (!t.done()) { val (f, w) = t.tag(); if (f == 3 && w == 0) modelType = t.varint().toInt() else t.skip(w) }
                    }
                    3 -> { // normalizer_spec: precompiled_charsmap = 2, add_dummy_prefix = 3
                        val nrm = Reader(body)
                        while (!nrm.done()) {
                            val (f, w) = nrm.tag()
                            when {
                                f == 2 && w == 2 -> charsmap = nrm.bytes().size
                                f == 3 && w == 0 -> addDummyPrefix = nrm.varint() != 0L
                                else -> nrm.skip(w)
                            }
                        }
                    }
                }
            }
            require(modelType == 1) { "Only unigram SentencePiece models are supported (model_type=$modelType)" }
            require(charsmap == 0) { "This tokenizer uses a normalisation table, which this reader does not support" }
            require(pieces.isNotEmpty()) { "No pieces found: not a SentencePiece model" }
            return SentencePieceModel(pieces, scores.toFloatArray(), types.toIntArray(), addDummyPrefix)
        }
    }

    /** Minimal protobuf wire reader. */
    private class Reader(private val b: ByteArray) {
        private var p = 0
        fun done() = p >= b.size
        fun varint(): Long {
            var shift = 0; var v = 0L
            while (true) {
                val x = b[p++].toInt() and 0xFF
                v = v or ((x and 0x7F).toLong() shl shift)
                if (x and 0x80 == 0) return v
                shift += 7
            }
        }
        fun tag(): Pair<Int, Int> { val t = varint(); return (t shr 3).toInt() to (t and 7).toInt() }
        fun bytes(): ByteArray { val n = varint().toInt(); val out = b.copyOfRange(p, p + n); p += n; return out }
        fun fixed32(): Int {
            val v = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or ((b[p + 2].toInt() and 0xFF) shl 16) or ((b[p + 3].toInt() and 0xFF) shl 24)
            p += 4; return v
        }
        fun skip(wire: Int) {
            when (wire) { 0 -> varint(); 1 -> p += 8; 2 -> { val n = varint().toInt(); p += n }; 5 -> p += 4; else -> error("Unsupported protobuf wire type $wire") }
        }
    }
}
