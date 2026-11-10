/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.looks

import android.graphics.Bitmap

/**
 * Applies a look in place to an ARGB_8888 bitmap at full resolution. Work is split
 * into fixed-height strips pulled by a small thread pool, so no full-size
 * intermediate buffer is allocated.
 */
object NativeLookRenderer {
    private const val STRIP_ROWS = 64

    init {
        System.loadLibrary("opencamera_looks")
    }

    fun apply(bitmap: Bitmap, params: LookParams) {
        require(bitmap.config == Bitmap.Config.ARGB_8888 && bitmap.isMutable) {
            "Need a mutable ARGB_8888 bitmap"
        }
        val scalars = floatArrayOf(
            params.intensity,
            params.grainAmount,
            LookMath.grainCell(params, bitmap.width, bitmap.height),
            params.vignetteAmount,
            params.vignetteSoftness,
            0f,
        )
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        val rc = nativeApply(bitmap, params.lut.data, params.lut.size, params.tone, scalars, STRIP_ROWS, threads)
        check(rc == 0) { "Native look renderer failed: $rc" }
    }

    private external fun nativeApply(
        bitmap: Bitmap, lut: FloatArray, lutSize: Int, tone: FloatArray,
        scalars: FloatArray, stripRows: Int, threads: Int,
    ): Int
}
