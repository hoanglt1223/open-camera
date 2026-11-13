/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Temporal merge of RAW_SENSOR frames into one 16-bit linear mosaic (black 0, white 65535, lens
 * shading corrected). The first frame is the reference; later frames are aligned to it and
 * accumulated as they arrive, so memory stays ~5 bytes per pixel whatever the frame count.
 * Every call throws [IllegalStateException] when the native side rejects the input.
 */
class RawMerger private constructor(private var handle: Long) : AutoCloseable {
    val width: Int
    val height: Int

    init {
        val info = checkNotNull(NativePipeline.nativeRawMergeInfo(handle)) { "No merge info" }
        width = info[0].toInt()
        height = info[1].toInt()
    }

    val frameCount: Int
        get() = NativePipeline.nativeRawMergeInfo(live())?.get(2)?.toInt() ?: 0

    /** Noise of the reference frame's binned luma (8-bit, square-root encoded). */
    val noiseSigma: Float
        get() = NativePipeline.nativeRawMergeInfo(live())?.get(3) ?: 0f

    internal fun nativeHandle(): Long = live()

    /** Aligns and adds a frame of the same size; returns its mean merge weight (0..1). */
    fun add(raw: ByteBuffer, rowStride: Int, black: FloatArray): Float {
        require(black.size == 4)
        val weight = NativePipeline.nativeRawMergeAdd(live(), raw, rowStride, black)
        check(weight >= 0f) { "Frame rejected by the raw merge" }
        return weight
    }

    fun finish() = check(NativePipeline.nativeRawMergeFinish(live())) { "Raw merge did not finish" }

    /**
     * Fills [out] (a direct buffer of at least rows x width x 6 bytes) with rows [y0, y0 + rows) as
     * interleaved linear camera RGB, unsigned 16-bit little endian. Only after [finish].
     */
    fun readRgbRows(y0: Int, rows: Int, out: ByteBuffer) {
        check(out.isDirect && out.order() == ByteOrder.nativeOrder() && ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) {
            "Need a direct little-endian buffer"
        }
        check(NativePipeline.nativeRawMergeRows(live(), y0, rows, out)) { "Rows $y0+$rows not available" }
    }

    override fun close() {
        if (handle != 0L) {
            NativePipeline.nativeRawMergeRelease(handle)
            handle = 0L
        }
    }

    private fun live(): Long = handle.also { check(it != 0L) { "Raw merge closed" } }

    companion object {
        /**
         * Starts a merge with the reference frame ([rowStride] in bytes). [params] gives the CFA,
         * black and white levels and the lens shading map; its colour fields are not used here.
         */
        fun begin(width: Int, height: Int, raw: ByteBuffer, rowStride: Int, params: RawDevelopParams): RawMerger {
            val p = FloatArray(6).also {
                it[0] = params.cfa.toFloat()
                it[1] = params.white
                params.black.copyInto(it, 2)
            }
            val handle = NativePipeline.nativeRawMergeBegin(
                width, height, raw, rowStride, p,
                params.lensShading, params.lensShadingWidth, params.lensShadingHeight,
            )
            check(handle != 0L) { "Raw merge could not start (unsupported frame or out of memory)" }
            return RawMerger(handle)
        }
    }
}
