/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.looks

/**
 * A 3D colour lookup table. [data] holds RGB triplets with red varying fastest,
 * then green, then blue (the `.cube` order).
 */
class Lut3d(val size: Int, val data: FloatArray) {
    init {
        require(size in 2..129) { "LUT size $size out of range" }
        require(data.size == size * size * size * 3) { "LUT data has ${data.size} values, expected ${size * size * size * 3}" }
    }

    companion object {
        fun identity(size: Int): Lut3d {
            val data = FloatArray(size * size * size * 3)
            var i = 0
            for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
                data[i++] = r / (size - 1f)
                data[i++] = g / (size - 1f)
                data[i++] = b / (size - 1f)
            }
            return Lut3d(size, data)
        }
    }
}

class CubeParseException(message: String) : IllegalArgumentException(message)

/** Parser for Adobe/Resolve `.cube` 3D LUT files (domain 0..1 only). */
object CubeParser {
    fun parse(text: String): Lut3d {
        var size = 0
        var values: FloatArray? = null
        var count = 0
        text.lineSequence().forEachIndexed { index, raw ->
            val line = raw.trim()
            val lineNo = index + 1
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val parts = line.split(Regex("\\s+"))
            when (parts[0].uppercase()) {
                "TITLE" -> Unit
                "LUT_1D_SIZE" -> throw CubeParseException("Line $lineNo: 1D LUTs are not supported")
                "LUT_3D_SIZE" -> {
                    if (size != 0) throw CubeParseException("Line $lineNo: duplicate LUT_3D_SIZE")
                    size = parts.getOrNull(1)?.toIntOrNull()
                        ?: throw CubeParseException("Line $lineNo: invalid LUT_3D_SIZE")
                    if (size !in 2..129) throw CubeParseException("Line $lineNo: LUT_3D_SIZE $size out of range 2..129")
                    values = FloatArray(size * size * size * 3)
                }
                "DOMAIN_MIN", "DOMAIN_MAX" -> {
                    val expected = if (parts[0].uppercase() == "DOMAIN_MIN") 0f else 1f
                    val d = parts.drop(1).map { it.toFloatOrNull() }
                    if (d.size != 3 || d.any { it != expected }) {
                        throw CubeParseException("Line $lineNo: only domain 0..1 is supported")
                    }
                }
                else -> {
                    val buf = values ?: throw CubeParseException("Line $lineNo: data before LUT_3D_SIZE")
                    if (parts.size != 3) throw CubeParseException("Line $lineNo: expected 3 values")
                    if (count >= buf.size) throw CubeParseException("Line $lineNo: too many entries")
                    for (p in parts) {
                        val v = p.toFloatOrNull()
                        if (v == null || !v.isFinite()) throw CubeParseException("Line $lineNo: invalid number '$p'")
                        buf[count++] = v.coerceIn(0f, 1f)
                    }
                }
            }
        }
        val buf = values ?: throw CubeParseException("Missing LUT_3D_SIZE")
        if (count != buf.size) {
            throw CubeParseException("Expected ${buf.size / 3} entries, found ${count / 3}")
        }
        return Lut3d(size, buf)
    }
}

/** Writer for `.cube` 3D LUTs (red fastest), readable by Resolve, Lightroom and [CubeParser]. */
object CubeWriter {
    fun write(lut: Lut3d, title: String): String = buildString(lut.data.size * 10 + 128) {
        append("TITLE \"").append(title.replace("\"", "'").replace('\n', ' ')).append("\"\n")
        append("LUT_3D_SIZE ").append(lut.size).append('\n')
        append("DOMAIN_MIN 0.0 0.0 0.0\n")
        append("DOMAIN_MAX 1.0 1.0 1.0\n")
        val d = lut.data
        var i = 0
        while (i < d.size) {
            append(String.format(java.util.Locale.US, "%.6f %.6f %.6f\n", d[i], d[i + 1], d[i + 2]))
            i += 3
        }
    }
}
