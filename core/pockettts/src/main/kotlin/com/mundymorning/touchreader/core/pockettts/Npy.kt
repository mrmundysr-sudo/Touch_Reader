package com.mundymorning.touchreader.core.pockettts

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reads a little-endian float32 `.npy` file. */
object Npy {
    class Array(val shape: IntArray, val data: FloatArray)

    fun readFloat32(bytes: ByteArray): Array {
        require(bytes.size > 10 && bytes[0] == 0x93.toByte() && String(bytes, 1, 5, Charsets.US_ASCII) == "NUMPY") { "Not a .npy file" }
        val major = bytes[6].toInt()
        val headerLen = if (major == 1) (bytes[8].toInt() and 0xFF) or ((bytes[9].toInt() and 0xFF) shl 8)
        else ByteBuffer.wrap(bytes, 8, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val start = (if (major == 1) 10 else 12)
        val header = String(bytes, start, headerLen, Charsets.US_ASCII)
        require("'<f4'" in header || "\"<f4\"" in header) { "Only float32 .npy files are supported: $header" }
        require("True" !in header.substringAfter("fortran_order").substringBefore(",")) { "Fortran-ordered arrays are not supported" }
        val shapeText = header.substringAfter("shape").substringAfter("(").substringBefore(")")
        val shape = shapeText.split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { it.toInt() }.toIntArray()
        val count = shape.fold(1) { a, b -> a * b }
        val buf = ByteBuffer.wrap(bytes, start + headerLen, bytes.size - start - headerLen).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val data = FloatArray(count)
        buf.get(data)
        return Array(shape, data)
    }
}
