/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.multiframe

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToLong

/**
 * Tags of a demosaiced (LinearRaw) DNG. Matrices are row-major 3x3 as Camera2 reports them:
 * [colorMatrix1] maps XYZ to reference camera RGB, [forwardMatrix1] white-balanced camera RGB
 * to XYZ D50. [asShotNeutral] is the camera RGB of a neutral, as SENSOR_NEUTRAL_COLOR_POINT.
 */
class LinearDngInfo(
    val width: Int,
    val height: Int,
    val make: String,
    val model: String,
    val software: String,
    /** TIFF orientation (1 normal, 6 rotate 90 clockwise, 3 rotate 180, 8 rotate 270). */
    val orientation: Int,
    val colorMatrix1: FloatArray,
    val illuminant1: Int,
    val colorMatrix2: FloatArray? = null,
    val illuminant2: Int? = null,
    val forwardMatrix1: FloatArray? = null,
    val forwardMatrix2: FloatArray? = null,
    val cameraCalibration1: FloatArray? = null,
    val cameraCalibration2: FloatArray? = null,
    val asShotNeutral: FloatArray? = null,
    val baselineExposure: Float = 0f,
    val exposureTimeNs: Long? = null,
    val iso: Int? = null,
    val fNumber: Float? = null,
    val focalLengthMm: Float? = null,
    /** "yyyy:MM:dd HH:mm:ss" */
    val dateTime: String? = null,
    val description: String? = null,
) {
    init {
        require(width > 0 && height > 0) { "Empty image" }
        require(orientation in 1..8) { "Bad orientation $orientation" }
        require(colorMatrix1.size == 9) { "ColorMatrix1 needs 9 values" }
        for (m in listOf(colorMatrix2, forwardMatrix1, forwardMatrix2, cameraCalibration1, cameraCalibration2)) {
            require(m == null || m.size == 9) { "Matrices need 9 values" }
        }
        require(asShotNeutral == null || (asShotNeutral.size == 3 && asShotNeutral.all { it > 0f })) {
            "AsShotNeutral needs 3 positive values"
        }
        require(colorMatrix2 == null || illuminant2 != null) { "ColorMatrix2 without an illuminant" }
    }
}

/**
 * Writes an uncompressed little-endian DNG 1.4 with one LinearRaw IFD: 3 samples of 16 bits per
 * pixel, black 0, white 65535. Image data is streamed in strips, so a full-size frame never has
 * to be held as one array. Written from the TIFF 6.0 and DNG 1.6 specifications.
 */
object LinearDngWriter {
    const val PHOTOMETRIC_LINEAR_RAW = 34892
    const val DEFAULT_ROWS_PER_STRIP = 64

    private const val BYTE = 1
    private const val ASCII = 2
    private const val SHORT = 3
    private const val LONG = 4
    private const val RATIONAL = 5
    private const val SRATIONAL = 10

    private class Entry(val tag: Int, val type: Int, val count: Int, val data: ByteArray)

    /**
     * [fillRows] receives (firstRow, rowCount, buffer) and must fill rowCount x width x 3
     * little-endian uint16 values (R, G, B interleaved) into the start of buffer.
     */
    fun write(
        out: OutputStream,
        info: LinearDngInfo,
        rowsPerStrip: Int = DEFAULT_ROWS_PER_STRIP,
        fillRows: (y0: Int, rows: Int, buffer: ByteArray) -> Unit,
    ) {
        require(rowsPerStrip > 0)
        val rowBytes = info.width.toLong() * 6
        val strips = (info.height + rowsPerStrip - 1) / rowsPerStrip
        val stripRows = IntArray(strips) { minOf(rowsPerStrip, info.height - it * rowsPerStrip) }
        val total = rowBytes * info.height
        require(total < Int.MAX_VALUE.toLong() - (1L shl 20)) { "Image too large for a classic TIFF" }

        val entries = entries(info, rowsPerStrip, stripRows.map { (it * rowBytes).toInt() }).sortedBy { it.tag }
        val ifdSize = 2 + entries.size * 12 + 4
        val extraSize = entries.sumOf { e -> if (e.data.size > 4) (e.data.size + 1) and 1.inv() else 0 }
        // Strip offsets are not known until the header size is; they are the only values patched in.
        val dataStart = 8 + ifdSize + extraSize
        val header = ByteBuffer.allocate(dataStart).order(ByteOrder.LITTLE_ENDIAN)
        header.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(8)
        header.putShort(entries.size.toShort())
        var extra = 8 + ifdSize
        val extraBlobs = ArrayList<ByteArray>()
        for (e in entries) {
            val data = if (e.tag == TAG_STRIP_OFFSETS) {
                le(strips * 4) { b -> var off = dataStart; stripRows.forEach { b.putInt(off); off += (it * rowBytes).toInt() } }
            } else e.data
            header.putShort(e.tag.toShort()).putShort(e.type.toShort()).putInt(e.count)
            if (data.size <= 4) {
                header.put(data)
                repeat(4 - data.size) { header.put(0) }
            } else {
                header.putInt(extra)
                extraBlobs += data
                extra += (data.size + 1) and 1.inv()
            }
        }
        header.putInt(0)
        for (blob in extraBlobs) {
            header.put(blob)
            if (blob.size and 1 != 0) header.put(0)
        }
        check(header.position() == dataStart)
        out.write(header.array())

        val buffer = ByteArray((rowsPerStrip * rowBytes).toInt())
        var y = 0
        for (rows in stripRows) {
            fillRows(y, rows, buffer)
            out.write(buffer, 0, (rows * rowBytes).toInt())
            y += rows
        }
        out.flush()
    }

    private fun entries(info: LinearDngInfo, rowsPerStrip: Int, stripBytes: List<Int>): List<Entry> {
        val list = ArrayList<Entry>()
        fun add(tag: Int, type: Int, count: Int, data: ByteArray) { list += Entry(tag, type, count, data) }
        fun longs(tag: Int, vararg v: Long) = add(tag, LONG, v.size, le(v.size * 4) { b -> v.forEach { b.putInt(it.toInt()) } })
        fun shorts(tag: Int, vararg v: Int) = add(tag, SHORT, v.size, le(v.size * 2) { b -> v.forEach { b.putShort(it.toShort()) } })
        fun bytes(tag: Int, vararg v: Int) = add(tag, BYTE, v.size, ByteArray(v.size) { v[it].toByte() })
        fun ascii(tag: Int, s: String) {
            val clean = s.filter { it.code in 0x20..0x7E }.toByteArray(Charsets.US_ASCII) + 0
            add(tag, ASCII, clean.size, clean)
        }
        fun rationals(tag: Int, signed: Boolean, v: FloatArray) = add(
            tag, if (signed) SRATIONAL else RATIONAL, v.size,
            le(v.size * 8) { b -> v.forEach { x -> b.putInt((x * DENOMINATOR).roundToLong().toInt()); b.putInt(DENOMINATOR) } },
        )

        longs(TAG_NEW_SUBFILE_TYPE, 0)
        longs(TAG_WIDTH, info.width.toLong())
        longs(TAG_HEIGHT, info.height.toLong())
        shorts(TAG_BITS, 16, 16, 16)
        shorts(TAG_COMPRESSION, 1)
        shorts(TAG_PHOTOMETRIC, PHOTOMETRIC_LINEAR_RAW)
        info.description?.let { ascii(TAG_DESCRIPTION, it) }
        ascii(TAG_MAKE, info.make)
        ascii(TAG_MODEL, info.model)
        // Placeholder of the right size; write() fills in the offsets.
        longs(TAG_STRIP_OFFSETS, *LongArray(stripBytes.size))
        shorts(TAG_ORIENTATION, info.orientation)
        shorts(TAG_SAMPLES_PER_PIXEL, 3)
        longs(TAG_ROWS_PER_STRIP, rowsPerStrip.toLong())
        longs(TAG_STRIP_BYTE_COUNTS, *stripBytes.map { it.toLong() }.toLongArray())
        shorts(TAG_PLANAR, 1)
        ascii(TAG_SOFTWARE, info.software)
        info.dateTime?.let { ascii(TAG_DATETIME, it); ascii(TAG_DATETIME_ORIGINAL, it) }
        info.exposureTimeNs?.takeIf { it > 0 }?.let { ns ->
            // Microseconds over a million keeps 30 s exposures inside 32 bits.
            add(TAG_EXPOSURE_TIME, RATIONAL, 1, le(8) { it.putInt((ns / 1000).toInt().coerceAtLeast(1)).putInt(1_000_000) })
        }
        info.fNumber?.takeIf { it > 0f }?.let { rationals(TAG_F_NUMBER, false, floatArrayOf(it)) }
        info.iso?.takeIf { it > 0 }?.let { shorts(TAG_ISO, it.coerceAtMost(65535)) }
        info.focalLengthMm?.takeIf { it > 0f }?.let { rationals(TAG_FOCAL_LENGTH, false, floatArrayOf(it)) }
        bytes(TAG_DNG_VERSION, 1, 4, 0, 0)
        bytes(TAG_DNG_BACKWARD_VERSION, 1, 1, 0, 0)
        ascii(TAG_UNIQUE_CAMERA_MODEL, "${info.make} ${info.model}")
        shorts(TAG_BLACK_LEVEL, 0, 0, 0)
        longs(TAG_WHITE_LEVEL, 65535, 65535, 65535)
        rationals(TAG_COLOR_MATRIX1, true, info.colorMatrix1)
        info.colorMatrix2?.let { rationals(TAG_COLOR_MATRIX2, true, it) }
        info.cameraCalibration1?.let { rationals(TAG_CAMERA_CALIBRATION1, true, it) }
        info.cameraCalibration2?.let { rationals(TAG_CAMERA_CALIBRATION2, true, it) }
        info.asShotNeutral?.let { rationals(TAG_AS_SHOT_NEUTRAL, false, it) }
        rationals(TAG_BASELINE_EXPOSURE, true, floatArrayOf(info.baselineExposure))
        shorts(TAG_ILLUMINANT1, info.illuminant1)
        info.illuminant2?.takeIf { info.colorMatrix2 != null }?.let { shorts(TAG_ILLUMINANT2, it) }
        info.forwardMatrix1?.let { rationals(TAG_FORWARD_MATRIX1, true, it) }
        info.forwardMatrix2?.takeIf { info.colorMatrix2 != null }?.let { rationals(TAG_FORWARD_MATRIX2, true, it) }
        return list
    }

    private inline fun le(size: Int, fill: (ByteBuffer) -> Unit): ByteArray =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN).also(fill).array()

    private const val DENOMINATOR = 10000

    const val TAG_NEW_SUBFILE_TYPE = 254
    const val TAG_WIDTH = 256
    const val TAG_HEIGHT = 257
    const val TAG_BITS = 258
    const val TAG_COMPRESSION = 259
    const val TAG_PHOTOMETRIC = 262
    const val TAG_DESCRIPTION = 270
    const val TAG_MAKE = 271
    const val TAG_MODEL = 272
    const val TAG_STRIP_OFFSETS = 273
    const val TAG_ORIENTATION = 274
    const val TAG_SAMPLES_PER_PIXEL = 277
    const val TAG_ROWS_PER_STRIP = 278
    const val TAG_STRIP_BYTE_COUNTS = 279
    const val TAG_PLANAR = 284
    const val TAG_SOFTWARE = 305
    const val TAG_DATETIME = 306
    const val TAG_EXPOSURE_TIME = 33434
    const val TAG_F_NUMBER = 33437
    const val TAG_ISO = 34855
    const val TAG_DATETIME_ORIGINAL = 36867
    const val TAG_FOCAL_LENGTH = 37386
    const val TAG_DNG_VERSION = 50706
    const val TAG_DNG_BACKWARD_VERSION = 50707
    const val TAG_UNIQUE_CAMERA_MODEL = 50708
    const val TAG_BLACK_LEVEL = 50714
    const val TAG_WHITE_LEVEL = 50717
    const val TAG_COLOR_MATRIX1 = 50721
    const val TAG_COLOR_MATRIX2 = 50722
    const val TAG_CAMERA_CALIBRATION1 = 50723
    const val TAG_CAMERA_CALIBRATION2 = 50724
    const val TAG_AS_SHOT_NEUTRAL = 50728
    const val TAG_BASELINE_EXPOSURE = 50730
    const val TAG_ILLUMINANT1 = 50778
    const val TAG_ILLUMINANT2 = 50779
    const val TAG_FORWARD_MATRIX1 = 50964
    const val TAG_FORWARD_MATRIX2 = 50965
}
