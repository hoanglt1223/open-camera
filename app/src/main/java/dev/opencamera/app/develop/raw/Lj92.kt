/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.develop.raw

class RawFormatException(message: String) : IllegalArgumentException(message)

/**
 * Lossless JPEG (ITU T.81 process 14, "SOF3") decoder, as used by DNG compression 7.
 * Written from the T.81 specification: Huffman-coded differences against one of seven
 * predictors, optional restart intervals and point transform.
 */
object Lj92 {
    private const val LOOKAHEAD = 9

    class Frame(val width: Int, val height: Int, val components: Int, val precision: Int, val data: CharArray)

    private class Huffman(counts: IntArray, val symbols: IntArray) {
        val maxCode = IntArray(18) { -1 }
        val valPtr = IntArray(17)
        val minCode = IntArray(17)
        val lut = IntArray(1 shl LOOKAHEAD)

        init {
            var code = 0
            var k = 0
            for (len in 1..16) {
                valPtr[len] = k
                minCode[len] = code
                code += counts[len - 1]
                k += counts[len - 1]
                maxCode[len] = if (counts[len - 1] > 0) code - 1 else -1
                code = code shl 1
            }
            maxCode[17] = Int.MAX_VALUE
            // 9-bit lookahead: (length shl 8) or symbol for short codes, 0 for longer ones.
            var c = 0
            var idx = 0
            for (len in 1..LOOKAHEAD) {
                for (k in 0 until counts[len - 1]) {
                    val sym = symbols[idx++]
                    val first = c shl (LOOKAHEAD - len)
                    for (j in 0 until (1 shl (LOOKAHEAD - len))) lut[first + j] = (len shl 8) or sym
                    c++
                }
                c = c shl 1
            }
        }
    }

    private class Bits(val b: ByteArray, var pos: Int, val end: Int) {
        private var acc = 0L
        private var n = 0
        var hitMarker = false

        private fun fill() {
            while (n <= 48) {
                var byte = 0
                if (!hitMarker && pos < end) {
                    byte = b[pos].toInt() and 0xFF
                    if (byte == 0xFF) {
                        val next = if (pos + 1 < end) b[pos + 1].toInt() and 0xFF else 0
                        if (next == 0x00) pos += 2 else { hitMarker = true; byte = 0 }
                    } else pos++
                }
                acc = (acc shl 8) or byte.toLong()
                n += 8
            }
        }

        fun peek(count: Int): Int {
            if (n < count) fill()
            return ((acc ushr (n - count)) and ((1L shl count) - 1)).toInt()
        }

        fun skip(count: Int) {
            n -= count
        }

        fun bit(): Int {
            if (n == 0) fill()
            n--
            return ((acc ushr n) and 1L).toInt()
        }

        fun bits(count: Int): Int {
            if (count == 0) return 0
            if (n < count) fill()
            n -= count
            return ((acc ushr n) and ((1L shl count) - 1)).toInt()
        }

        /** Skips to the byte after the next RSTn marker. */
        fun restart() {
            n = 0; acc = 0; hitMarker = false
            while (pos + 1 < end) {
                if ((b[pos].toInt() and 0xFF) == 0xFF && (b[pos + 1].toInt() and 0xFF) in 0xD0..0xD7) { pos += 2; return }
                pos++
            }
        }
    }

    fun decode(b: ByteArray, offset: Int = 0, length: Int = b.size - offset): Frame {
        val end = offset + length
        var pos = offset
        fun u8() = b[pos++].toInt() and 0xFF
        fun u16() = (u8() shl 8) or u8()
        if (length < 4 || u16() != 0xFFD8) throw RawFormatException("LJ92: missing SOI")
        val tables = arrayOfNulls<Huffman>(4)
        var width = 0; var height = 0; var comps = 0; var precision = 0
        val compIds = IntArray(4)
        var restartInterval = 0
        while (pos + 4 <= end) {
            if (u8() != 0xFF) continue
            var marker = u8()
            while (marker == 0xFF && pos < end) marker = u8()
            val segLen = u16()
            val segEnd = pos + segLen - 2
            if (segEnd > end) throw RawFormatException("LJ92: truncated segment")
            when (marker) {
                0xC4 -> while (pos < segEnd) {
                    val tc = u8()
                    val counts = IntArray(16) { u8() }
                    val total = counts.sum()
                    if (total > 256 || pos + total > segEnd) throw RawFormatException("LJ92: bad DHT")
                    tables[tc and 3] = Huffman(counts, IntArray(total) { u8() })
                }
                0xC3 -> {
                    precision = u8(); height = u16(); width = u16(); comps = u8()
                    if (comps !in 1..4 || precision !in 2..16 || width <= 0 || height <= 0) {
                        throw RawFormatException("LJ92: unsupported frame $width x $height x $comps @ $precision")
                    }
                    for (c in 0 until comps) { compIds[c] = u8(); u8(); u8() }
                }
                0xDD -> restartInterval = u16()
                0xDA -> {
                    val ns = u8()
                    if (ns != comps) throw RawFormatException("LJ92: scan has $ns of $comps components")
                    val tab = arrayOfNulls<Huffman>(ns)
                    for (c in 0 until ns) {
                        u8()
                        tab[c] = tables[(u8() shr 4) and 3] ?: throw RawFormatException("LJ92: missing Huffman table")
                    }
                    val predictor = u8(); u8()
                    val pt = u8() and 0x0F
                    pos = segEnd
                    if (predictor !in 1..7) throw RawFormatException("LJ92: predictor $predictor")
                    return Frame(width, height, comps, precision,
                        scan(Bits(b, pos, end), width, height, comps, precision, pt, predictor, tab.requireNoNulls(), restartInterval))
                }
                0xC0, 0xC1, 0xC2, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF ->
                    throw RawFormatException("LJ92: not a lossless JPEG (SOF${marker - 0xC0})")
            }
            pos = segEnd
        }
        throw RawFormatException("LJ92: no scan")
    }

    private fun decodeSymbol(bits: Bits, h: Huffman): Int {
        val e = h.lut[bits.peek(LOOKAHEAD)]
        if (e != 0) {
            bits.skip(e shr 8)
            return e and 0xFF
        }
        var code = bits.bit()
        var len = 1
        while (code > h.maxCode[len]) {
            code = (code shl 1) or bits.bit()
            len++
            if (len > 16) throw RawFormatException("LJ92: bad Huffman code")
        }
        return h.symbols[h.valPtr[len] + code - h.minCode[len]]
    }

    private fun scan(
        bits: Bits, w: Int, h: Int, nc: Int, precision: Int, pt: Int, predictor: Int,
        tab: Array<Huffman>, restart: Int,
    ): CharArray {
        val stride = w * nc
        val out = CharArray(stride * h)
        val initial = 1 shl (precision - pt - 1)
        var mcus = 0
        var restartRow = true
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (restart > 0 && mcus > 0 && mcus % restart == 0) {
                    bits.restart()
                    restartRow = true
                }
                for (c in 0 until nc) {
                    val s = decodeSymbol(bits, tab[c])
                    val diff = when (s) {
                        0 -> 0
                        16 -> 32768
                        else -> {
                            val v = bits.bits(s)
                            if (v < (1 shl (s - 1))) v - (1 shl s) + 1 else v
                        }
                    }
                    val i = y * stride + x * nc + c
                    val pred = when {
                        restartRow && x == 0 && (y == 0 || restart > 0) -> initial
                        y == 0 || restartRow -> if (x == 0) out[i - stride].code else out[i - nc].code
                        x == 0 -> out[i - stride].code
                        else -> {
                            val ra = out[i - nc].code
                            val rb = out[i - stride].code
                            val rc = out[i - stride - nc].code
                            when (predictor) {
                                1 -> ra
                                2 -> rb
                                3 -> rc
                                4 -> ra + rb - rc
                                5 -> ra + ((rb - rc) shr 1)
                                6 -> rb + ((ra - rc) shr 1)
                                else -> (ra + rb) shr 1
                            }
                        }
                    }
                    out[i] = ((pred + diff) and 0xFFFF).toChar()
                }
                mcus++
                if (x == w - 1) restartRow = false
            }
        }
        if (pt > 0) for (i in out.indices) out[i] = ((out[i].code shl pt) and 0xFFFF).toChar()
        return out
    }
}
