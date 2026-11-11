/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline

import java.nio.ByteBuffer

object NativePipeline {
    init {
        System.loadLibrary("opencamera_pipeline")
    }

    fun interface ProgressListener {
        fun onProgress(fraction: Float)
    }

    external fun nativeVersion(): String

    internal external fun nativeCreateBurst(width: Int, height: Int, capacity: Int): Long

    internal external fun nativeAddFrame(
        handle: Long,
        y: ByteBuffer,
        yRowStride: Int,
        u: ByteBuffer,
        v: ByteBuffer,
        uvRowStride: Int,
        uvPixelStride: Int,
    ): Boolean

    internal external fun nativeFrameCount(handle: Long): Int

    internal external fun nativeExportFrame(handle: Long, index: Int, outNv21: ByteArray): Boolean

    internal external fun nativeProcess(
        handle: Long,
        mode: Int,
        toneEvStep: Float,
        reference: Int,
        outNv21: ByteArray,
        listener: ProgressListener?,
    ): DoubleArray?

    internal external fun nativeReleaseBurst(handle: Long)
}
