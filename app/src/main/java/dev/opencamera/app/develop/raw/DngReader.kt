/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.develop.raw

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Gain map opcode (DNG 1.3+, opcode 9): lens shading gains for one CFA phase. */
class GainMap(
    val top: Int, val left: Int, val bottom: Int, val right: Int,
    val rowPitch: Int, val colPitch: Int,
    val pointsV: Int, val pointsH: Int,
    val spacingV: Double, val spacingH: Double,
    val originV: Double, val originH: Double,
    val gains: FloatArray,
)

/**
 * A raw image read from a DNG: the CFA mosaic (or 3-channel linear raw) inside the active
 * area, plus the colour tags needed to develop it.
 */
class DngImage(
    val width: Int,
    val height: Int,
    /** 1 for a CFA mosaic, 3 for LinearRaw. */
    val samples: Int,
    val data: CharArray,
    /** Colour (0 R, 1 G, 2 B) of each 2x2 CFA position, row-major. */
    val cfa: IntArray,
    val black: FloatArray,
    val white: Float,
    val asShotNeutral: FloatArray?,
    val colorMatrix1: FloatArray?,
    val colorMatrix2: FloatArray?,
    val forwardMatrix1: FloatArray?,
    val forwardMatrix2: FloatArray?,
    val illuminant1: Int,
    val illuminant2: Int,
    val baselineExposure: Float,
    val orientation: Int,
    val gainMaps: List<GainMap>,
    val cropX: Int,
    val cropY: Int,
    val cropW: Int,
    val cropH: Int,
)

/**
 * Reader for the common DNG subset: uncompressed (8/16 bit or bit-packed) and lossless-JPEG
 * (compression 7) strips or tiles, CFA (2x2 Bayer) and LinearRaw. Written from the DNG 1.6
 * specification. Anything else is rejected with [RawFormatException], never a crash.
 */
object DngReader {
    /** Storage layout of the last file read, for logs. */
    @Volatile var lastLayout = ""

    private class Entry(val tag: Int, val type: Int, val count: Int, val valueOffset: Int)

    private class Tiff(val buf: ByteBuffer) {
        fun u16(off: Int) = buf.getShort(off).toInt() and 0xFFFF
        fun u32(off: Int) = buf.getInt(off)

        fun ifd(off: Int): Map<Int, Entry> {
            if (off <= 0 || off + 2 > buf.limit()) throw RawFormatException("Bad IFD offset $off")
            val n = u16(off)
            if (n > 4096 || off + 2 + n * 12 > buf.limit()) throw RawFormatException("Bad IFD size")
            val map = HashMap<Int, Entry>(n)
            for (i in 0 until n) {
                val e = off + 2 + i * 12
                val type = u16(e + 2)
                val count = u32(e + 4)
                val size = typeSize(type).toLong() * count
                val value = if (size <= 4) e + 8 else u32(e + 8)
                if (count < 0 || (size > 4 && (value < 0 || value + size > buf.limit()))) continue
                map[u16(e)] = Entry(u16(e), type, count, value)
            }
            return map
        }

        fun nextIfd(off: Int): Int {
            val n = u16(off)
            val p = off + 2 + n * 12
            return if (p + 4 <= buf.limit()) u32(p) else 0
        }

        fun ints(e: Entry?): IntArray? = e?.let { en ->
            IntArray(en.count) { i ->
                when (en.type) {
                    1, 7 -> buf.get(en.valueOffset + i).toInt() and 0xFF
                    3 -> u16(en.valueOffset + i * 2)
                    4, 9 -> u32(en.valueOffset + i * 4)
                    else -> 0
                }
            }
        }

        fun int(e: Entry?, default: Int = 0) = ints(e)?.firstOrNull() ?: default

        fun floats(e: Entry?): FloatArray? = e?.let { en ->
            FloatArray(en.count) { i ->
                when (en.type) {
                    3 -> u16(en.valueOffset + i * 2).toFloat()
                    4 -> (u32(en.valueOffset + i * 4).toLong() and 0xFFFFFFFFL).toFloat()
                    5 -> (u32(en.valueOffset + i * 8).toLong() and 0xFFFFFFFFL).toFloat() /
                        (u32(en.valueOffset + i * 8 + 4).toLong() and 0xFFFFFFFFL).coerceAtLeast(1).toFloat()
                    10 -> u32(en.valueOffset + i * 8).toFloat() / u32(en.valueOffset + i * 8 + 4).let { if (it == 0) 1 else it }
                    11 -> buf.getFloat(en.valueOffset + i * 4)
                    12 -> buf.getDouble(en.valueOffset + i * 8).toFloat()
                    1 -> (buf.get(en.valueOffset + i).toInt() and 0xFF).toFloat()
                    else -> 0f
                }
            }
        }

        fun bytes(e: Entry?): ByteArray? = e?.let { ByteArray(it.count * typeSize(it.type)) { i -> buf.get(it.valueOffset + i) } }

        private fun typeSize(t: Int) = when (t) { 1, 2, 6, 7 -> 1; 3, 8 -> 2; 4, 9, 11 -> 4; 5, 10, 12 -> 8; else -> 1 }
    }

    fun isDng(head: ByteArray): Boolean = head.size >= 4 &&
        ((head[0] == 'I'.code.toByte() && head[1] == 'I'.code.toByte() && head[2] == 42.toByte()) ||
            (head[0] == 'M'.code.toByte() && head[1] == 'M'.code.toByte() && head[3] == 42.toByte()))

    fun read(bytes: ByteArray): DngImage {
        if (!isDng(bytes)) throw RawFormatException("Not a TIFF/DNG file")
        val order = if (bytes[0] == 'I'.code.toByte()) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val t = Tiff(ByteBuffer.wrap(bytes).order(order))
        val ifd0Off = t.u32(4)
        val ifd0 = t.ifd(ifd0Off)
        if (ifd0[TAG_DNG_VERSION] == null) throw RawFormatException("TIFF without DNGVersion")
        // Find the full-resolution raw IFD (NewSubFileType 0, CFA or LinearRaw) among IFD0, its
        // SubIFDs and the IFD chain.
        val candidates = ArrayList<Map<Int, Entry>>()
        val seen = HashSet<Int>()
        fun visit(off: Int, depth: Int) {
            if (off <= 0 || depth > 4 || !seen.add(off)) return
            val d = runCatching { t.ifd(off) }.getOrNull() ?: return
            candidates += d
            t.ints(d[TAG_SUB_IFDS])?.forEach { visit(it, depth + 1) }
            visit(runCatching { t.nextIfd(off) }.getOrDefault(0), depth + 1)
        }
        visit(ifd0Off, 0)
        val raw = candidates.firstOrNull { d ->
            t.int(d[TAG_NEW_SUBFILE_TYPE]) == 0 && t.int(d[TAG_PHOTOMETRIC]) in setOf(PHOTO_CFA, PHOTO_LINEAR_RAW)
        } ?: throw RawFormatException("No raw image in DNG")

        val width = t.int(raw[TAG_WIDTH])
        val height = t.int(raw[TAG_HEIGHT])
        val spp = t.int(raw[TAG_SAMPLES_PER_PIXEL], 1)
        val bps = t.int(raw[TAG_BITS], 16)
        val compression = t.int(raw[TAG_COMPRESSION], 1)
        val photometric = t.int(raw[TAG_PHOTOMETRIC])
        if (width <= 0 || height <= 0 || width.toLong() * height > MAX_PIXELS) throw RawFormatException("Unsupported size $width x $height")
        if (photometric == PHOTO_CFA && spp != 1) throw RawFormatException("CFA with $spp samples")
        if (photometric == PHOTO_LINEAR_RAW && spp != 3) throw RawFormatException("LinearRaw with $spp samples")
        if (compression != 1 && compression != 7) throw RawFormatException("Compression $compression is not supported")
        val data = CharArray(width * height * spp)

        val tiled = raw[TAG_TILE_OFFSETS] != null
        val offsets = t.ints(if (tiled) raw[TAG_TILE_OFFSETS] else raw[TAG_STRIP_OFFSETS]) ?: throw RawFormatException("No image data")
        val counts = t.ints(if (tiled) raw[TAG_TILE_BYTE_COUNTS] else raw[TAG_STRIP_BYTE_COUNTS])
            ?: throw RawFormatException("No byte counts")
        val tw = if (tiled) t.int(raw[TAG_TILE_WIDTH]) else width
        val th = if (tiled) t.int(raw[TAG_TILE_LENGTH]) else t.int(raw[TAG_ROWS_PER_STRIP], height).coerceAtMost(height)
        if (tw <= 0 || th <= 0) throw RawFormatException("Bad tile size")
        val across = (width + tw - 1) / tw
        // Tiles and strips are independent and write disjoint areas: decode them in parallel.
        offsets.indices.toList().parallelStream().forEach { i ->
            val off = offsets[i]
            val len = counts.getOrElse(i) { 0 }
            if (off < 0 || len <= 0 || off.toLong() + len > bytes.size) throw RawFormatException("Tile $i outside file")
            val x0 = (i % across) * tw
            val y0 = (i / across) * th
            if (compression == 7) {
                val f = Lj92.decode(bytes, off, len)
                val fw = f.width * f.components / spp
                for (y in 0 until minOf(f.height, height - y0)) for (x in 0 until minOf(fw, width - x0)) for (s in 0 until spp) {
                    data[((y0 + y) * width + x0 + x) * spp + s] = f.data[(y * fw + x) * spp + s]
                }
            } else {
                unpack(bytes, off, len, bps, order, tw, th, spp, x0, y0, width, height, data)
            }
        }
        lastLayout = "compression=$compression bps=$bps ${if (tiled) "tiles" else "strips"}=${offsets.size} ${tw}x$th"

        // LinearizationTable: stored values index a curve (e.g. 8-bit Leica DNGs).
        t.ints(raw[TAG_LINEARIZATION])?.takeIf { it.size >= 2 }?.let { lut ->
            val last = lut.size - 1
            for (i in data.indices) data[i] = lut[minOf(data[i].code, last)].toChar()
        }

        // CFA pattern.
        val cfa = IntArray(4) { intArrayOf(0, 1, 1, 2)[it] }
        if (photometric == PHOTO_CFA) {
            val dim = t.ints(raw[TAG_CFA_REPEAT_DIM]) ?: intArrayOf(2, 2)
            if (dim.size < 2 || dim[0] != 2 || dim[1] != 2) throw RawFormatException("Only 2x2 CFA patterns are supported")
            val pat = t.ints(raw[TAG_CFA_PATTERN]) ?: throw RawFormatException("Missing CFAPattern")
            if (pat.size < 4 || pat.any { it !in 0..2 }) throw RawFormatException("Unsupported CFA colours")
            for (i in 0..3) cfa[i] = pat[i]
        }

        val blackDim = t.ints(raw[TAG_BLACK_REPEAT_DIM]) ?: intArrayOf(1, 1)
        val blackVals = t.floats(raw[TAG_BLACK_LEVEL]) ?: floatArrayOf(0f)
        val black = FloatArray(4) { i ->
            val by = (i / 2) % blackDim[0].coerceAtLeast(1)
            val bx = (i % 2) % blackDim.getOrElse(1) { 1 }.coerceAtLeast(1)
            blackVals.getOrElse(by * blackDim.getOrElse(1) { 1 }.coerceAtLeast(1) + bx) { blackVals[0] }
        }
        val white = t.floats(raw[TAG_WHITE_LEVEL])?.firstOrNull()
            ?: t.ints(raw[TAG_LINEARIZATION])?.lastOrNull()?.toFloat() ?: ((1 shl bps) - 1).toFloat()
        val active = t.ints(raw[TAG_ACTIVE_AREA])?.takeIf { it.size == 4 } ?: intArrayOf(0, 0, height, width)
        val cropOrigin = t.floats(raw[TAG_DEFAULT_CROP_ORIGIN]) ?: floatArrayOf(0f, 0f)
        val cropSize = t.floats(raw[TAG_DEFAULT_CROP_SIZE])
        val aw = active[3] - active[1]
        val ah = active[2] - active[0]
        val gainMaps = parseOpcodes(t.bytes(raw[TAG_OPCODE_LIST2]))

        // Crop the active area out of the stored data.
        val actData = if (active[0] == 0 && active[1] == 0 && aw == width && ah == height) data else {
            CharArray(aw * ah * spp).also { d ->
                for (y in 0 until ah) System.arraycopy(data, ((active[0] + y) * width + active[1]) * spp, d, y * aw * spp, aw * spp)
            }
        }
        // The CFA pattern is relative to the active area origin per the spec.
        return DngImage(
            width = aw, height = ah, samples = spp, data = actData, cfa = cfa, black = black, white = white,
            asShotNeutral = t.floats(ifd0[TAG_AS_SHOT_NEUTRAL] ?: raw[TAG_AS_SHOT_NEUTRAL]),
            colorMatrix1 = t.floats(ifd0[TAG_COLOR_MATRIX1] ?: raw[TAG_COLOR_MATRIX1]),
            colorMatrix2 = t.floats(ifd0[TAG_COLOR_MATRIX2] ?: raw[TAG_COLOR_MATRIX2]),
            forwardMatrix1 = t.floats(ifd0[TAG_FORWARD_MATRIX1] ?: raw[TAG_FORWARD_MATRIX1]),
            forwardMatrix2 = t.floats(ifd0[TAG_FORWARD_MATRIX2] ?: raw[TAG_FORWARD_MATRIX2]),
            illuminant1 = t.int(ifd0[TAG_ILLUMINANT1] ?: raw[TAG_ILLUMINANT1], 21),
            illuminant2 = t.int(ifd0[TAG_ILLUMINANT2] ?: raw[TAG_ILLUMINANT2], 0),
            baselineExposure = t.floats(ifd0[TAG_BASELINE_EXPOSURE])?.firstOrNull() ?: 0f,
            orientation = t.int(ifd0[TAG_ORIENTATION], 1),
            gainMaps = gainMaps,
            cropX = cropOrigin[0].toInt().coerceIn(0, aw - 1),
            cropY = cropOrigin.getOrElse(1) { 0f }.toInt().coerceIn(0, ah - 1),
            cropW = (cropSize?.getOrNull(0)?.toInt() ?: aw).coerceIn(1, aw),
            cropH = (cropSize?.getOrNull(1)?.toInt() ?: ah).coerceIn(1, ah),
        )
    }

    /** Uncompressed samples: 8 or 16 bit in file byte order, other depths bit-packed MSB first. */
    private fun unpack(
        b: ByteArray, off: Int, len: Int, bps: Int, order: ByteOrder, tw: Int, th: Int, spp: Int,
        x0: Int, y0: Int, width: Int, height: Int, out: CharArray,
    ) {
        if (bps !in 1..16) throw RawFormatException("BitsPerSample $bps")
        val rowSamples = tw * spp
        val rowBytes = (rowSamples * bps + 7) / 8
        val le = order == ByteOrder.LITTLE_ENDIAN
        val cols = minOf(rowSamples, (width - x0) * spp)
        for (y in 0 until minOf(th, height - y0)) {
            val rowStart = off + y * rowBytes
            if (rowStart + rowBytes > off + len) break
            val dst = (y0 + y) * width * spp + x0 * spp
            // Fast paths for whole-byte samples.
            if (bps == 16) {
                ByteBuffer.wrap(b, rowStart, cols * 2).order(order).asCharBuffer().get(out, dst, cols)
                continue
            }
            if (bps == 8) {
                for (sx in 0 until cols) out[dst + sx] = (b[rowStart + sx].toInt() and 0xFF).toChar()
                continue
            }
            var bitPos = 0L
            for (sx in 0 until minOf(rowSamples, (width - x0) * spp)) {
                val v = when (bps) {
                    16 -> {
                        val p = rowStart + sx * 2
                        if (le) (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8)
                        else ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)
                    }
                    8 -> b[rowStart + sx].toInt() and 0xFF
                    else -> {
                        var v = 0
                        for (k in 0 until bps) {
                            val bp = bitPos + k
                            val byte = b[rowStart + (bp ushr 3).toInt()].toInt()
                            v = (v shl 1) or ((byte ushr (7 - (bp and 7).toInt())) and 1)
                        }
                        bitPos += bps
                        v
                    }
                }
                out[(y0 + y) * width * spp + x0 * spp + sx] = v.toChar()
            }
        }
    }

    /** Opcode lists are always big-endian. Only GainMap is used; others are ignored. */
    fun parseOpcodes(list: ByteArray?): List<GainMap> {
        if (list == null || list.size < 4) return emptyList()
        val bb = ByteBuffer.wrap(list).order(ByteOrder.BIG_ENDIAN)
        val n = bb.int
        val maps = ArrayList<GainMap>()
        repeat(n.coerceIn(0, 64)) {
            if (bb.remaining() < 16) return maps
            val id = bb.int; bb.int; bb.int
            val size = bb.int
            if (size < 0 || size > bb.remaining()) return maps
            val start = bb.position()
            if (id == 9 && size >= 76) {
                val top = bb.int; val left = bb.int; val bottom = bb.int; val right = bb.int
                bb.int; bb.int
                val rowPitch = bb.int; val colPitch = bb.int
                val pv = bb.int; val ph = bb.int
                val sv = bb.double; val sh = bb.double; val ov = bb.double; val oh = bb.double
                val planes = bb.int
                val count = pv.toLong() * ph * planes
                if (pv in 1..4096 && ph in 1..4096 && planes >= 1 && count * 4 <= size - 76) {
                    val gains = FloatArray(pv * ph) { i -> bb.getFloat(bb.position() + i * planes * 4) }
                    maps += GainMap(top, left, bottom, right, rowPitch.coerceAtLeast(1), colPitch.coerceAtLeast(1),
                        pv, ph, sv, sh, ov, oh, gains)
                }
            }
            bb.position(start + size)
        }
        return maps
    }

    private const val MAX_PIXELS = 400_000_000L
    private const val PHOTO_CFA = 32803
    private const val PHOTO_LINEAR_RAW = 34892
    private const val TAG_NEW_SUBFILE_TYPE = 254
    private const val TAG_WIDTH = 256
    private const val TAG_HEIGHT = 257
    private const val TAG_BITS = 258
    private const val TAG_COMPRESSION = 259
    private const val TAG_PHOTOMETRIC = 262
    private const val TAG_STRIP_OFFSETS = 273
    private const val TAG_ORIENTATION = 274
    private const val TAG_SAMPLES_PER_PIXEL = 277
    private const val TAG_ROWS_PER_STRIP = 278
    private const val TAG_STRIP_BYTE_COUNTS = 279
    private const val TAG_TILE_WIDTH = 322
    private const val TAG_TILE_LENGTH = 323
    private const val TAG_TILE_OFFSETS = 324
    private const val TAG_TILE_BYTE_COUNTS = 325
    private const val TAG_SUB_IFDS = 330
    private const val TAG_CFA_REPEAT_DIM = 33421
    private const val TAG_CFA_PATTERN = 33422
    private const val TAG_DNG_VERSION = 50706
    private const val TAG_LINEARIZATION = 50712
    private const val TAG_BLACK_REPEAT_DIM = 50713
    private const val TAG_BLACK_LEVEL = 50714
    private const val TAG_WHITE_LEVEL = 50717
    private const val TAG_DEFAULT_CROP_ORIGIN = 50719
    private const val TAG_DEFAULT_CROP_SIZE = 50720
    private const val TAG_COLOR_MATRIX1 = 50721
    private const val TAG_COLOR_MATRIX2 = 50722
    private const val TAG_AS_SHOT_NEUTRAL = 50728
    private const val TAG_BASELINE_EXPOSURE = 50730
    private const val TAG_ILLUMINANT1 = 50778
    private const val TAG_ILLUMINANT2 = 50779
    private const val TAG_ACTIVE_AREA = 50829
    private const val TAG_FORWARD_MATRIX1 = 50964
    private const val TAG_FORWARD_MATRIX2 = 50965
    private const val TAG_OPCODE_LIST2 = 51009
}
