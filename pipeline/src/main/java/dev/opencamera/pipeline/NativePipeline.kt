/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline

import java.nio.ByteBuffer

/**
 * JNI entry points. Must stay public: Kotlin mangles the JVM names of internal functions
 * (name$module), which then no longer match the exported C symbols.
 */
object NativePipeline {
    init {
        System.loadLibrary("opencamera_pipeline")
    }

    fun interface ProgressListener {
        fun onProgress(fraction: Float)
    }

    external fun nativeVersion(): String

    external fun nativeCreateBurst(width: Int, height: Int, capacity: Int): Long

    external fun nativeAddFrame(
        handle: Long,
        y: ByteBuffer,
        yRowStride: Int,
        u: ByteBuffer,
        v: ByteBuffer,
        uvRowStride: Int,
        uvPixelStride: Int,
    ): Boolean

    external fun nativeFrameCount(handle: Long): Int

    external fun nativeExportFrame(handle: Long, index: Int, outNv21: ByteArray): Boolean

    external fun nativeProcess(
        handle: Long,
        mode: Int,
        toneEvStep: Float,
        reference: Int,
        outNv21: ByteArray,
        listener: ProgressListener?,
    ): DoubleArray?

    external fun nativeStack(
        handle: Long,
        op: Int,
        reference: Int,
        align: Boolean,
        params: FloatArray,
        outNv21: ByteArray,
    ): DoubleArray?

    external fun nativeRankSharpness(handle: Long): IntArray?

    external fun nativeAddNv21(handle: Long, nv21: ByteArray): Boolean

    external fun nativeAddRawFrame(
        handle: Long,
        raw: ByteBuffer,
        rowStride: Int,
        params: FloatArray,
        lensShading: FloatArray?,
        lensShadingWidth: Int,
        lensShadingHeight: Int,
    ): Boolean

    external fun nativeExportToneBase(handle: Long): FloatArray?

    external fun nativeExportLuma(handle: Long, index: Int, factor: Int, out: ByteArray): Boolean

    external fun nativeProcessCrop(
        handle: Long,
        mode: Int,
        toneEvStep: Float,
        reference: Int,
        crop: IntArray?,
        outNv21: ByteArray,
        listener: ProgressListener?,
    ): DoubleArray?

    external fun nativeReleaseBurst(handle: Long)
}
