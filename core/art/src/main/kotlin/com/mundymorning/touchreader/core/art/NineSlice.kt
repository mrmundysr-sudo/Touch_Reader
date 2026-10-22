package com.mundymorning.touchreader.core.art

/** A rectangle in pixels: left, top, width, height. */
data class PxRect(val x: Int, val y: Int, val w: Int, val h: Int)

/** One piece of a stretchable frame: take [src] from the picture and draw it into [dst]. */
data class SlicePiece(val src: PxRect, val dst: PxRect)

/**
 * Splits a frame picture into nine pieces so it can be drawn at any size without stretching its corners or edges:
 * the four corners keep their shape, the four edges stretch along one direction, and the middle stretches both ways.
 */
object NineSlice {
    /**
     * [slice] is the width of the corners in the source picture, in pixels. [corner] is how large the corners are drawn
     * (the artwork is made for a 3x screen, so on other screens the corners are scaled to match). The corners are drawn
     * smaller if the target is too small to fit two of them. Returns nothing when there is nothing sensible to draw.
     */
    fun pieces(srcW: Int, srcH: Int, slice: Int, dstW: Int, dstH: Int, corner: Float): List<SlicePiece> {
        if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return emptyList()
        val s = slice.coerceIn(1, minOf(srcW, srcH) / 2)
        var c = corner.coerceAtLeast(1f)
        c = minOf(c, dstW / 2f, dstH / 2f)
        val ci = c.toInt().coerceAtLeast(1)
        val sx = intArrayOf(0, s, srcW - s, srcW)
        val sy = intArrayOf(0, s, srcH - s, srcH)
        val dx = intArrayOf(0, ci, dstW - ci, dstW)
        val dy = intArrayOf(0, ci, dstH - ci, dstH)
        val out = ArrayList<SlicePiece>(9)
        for (row in 0 until 3) for (col in 0 until 3) {
            val src = PxRect(sx[col], sy[row], sx[col + 1] - sx[col], sy[row + 1] - sy[row])
            val dst = PxRect(dx[col], dy[row], dx[col + 1] - dx[col], dy[row + 1] - dy[row])
            if (src.w > 0 && src.h > 0 && dst.w > 0 && dst.h > 0) out += SlicePiece(src, dst)
        }
        return out
    }
}

/** The pictures that are stretchable frames: their authored size and corner width (kept equal to art/slots.json by a test). */
object ArtFrames {
    data class Spec(val width: Int, val height: Int, val slice: Int)

    val all: Map<String, Spec> = mapOf(
        "global_card_frame" to Spec(192, 192, 60),
        "global_button_frame" to Spec(192, 96, 40),
        "global_button_frame_pressed" to Spec(192, 96, 40),
        "global_button_frame_selected" to Spec(192, 96, 40),
        "global_button_frame_disabled" to Spec(192, 96, 40),
        "player_bar" to Spec(384, 144, 48),
    )
}
