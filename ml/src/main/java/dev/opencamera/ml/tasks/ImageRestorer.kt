/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.ml.tasks

import android.graphics.Bitmap
import android.os.SystemClock
import dev.opencamera.ml.MlException
import dev.opencamera.ml.MlRuntime
import dev.opencamera.ml.Tiling

/**
 * Tiled image-to-image models: NAFNet denoise (256 px windows) and Real-ESRGAN general x4
 * (128 px windows). Both throw [MlException] when the model is missing, fails or runs past
 * [budgetMs]; callers then use their classical path.
 */
class ImageRestorer(private val runtime: MlRuntime) {
    val denoiseAvailable get() = runtime.isAvailable(DENOISE)
    val superResAvailable get() = runtime.isAvailable(SUPER_RES)

    fun denoise(src: Bitmap, budgetMs: Long): Bitmap = tiled(DENOISE, src, 256, 16, 1, budgetMs, 1)

    /** 4x model upscale, written out at [outScale] (2 or 4) per tile to bound memory. */
    fun upscale4x(src: Bitmap, budgetMs: Long, outScale: Int = 4): Bitmap = tiled(SUPER_RES, src, 128, 8, 4, budgetMs, outScale)

    private fun tiled(model: String, src: Bitmap, tile: Int, margin: Int, scale: Int, budgetMs: Long, outScale: Int): Bitmap {
        if (!runtime.isAvailable(model)) throw MlException(MlException.Reason.MISSING, "$model not downloaded")
        val start = SystemClock.elapsedRealtime()
        val deadline = start + budgetMs
        val tiles = Tiling.plan(src.width, src.height, tile, margin).size
        var done = 0
        return try {
            Tiling.runBitmap(src, tile, margin, scale, deadline, outScale) { t ->
                val left = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1)
                val out = runtime.run(model, listOf(t), left).first()
                // Give up after the first tile when the whole image clearly cannot finish in time.
                if (++done == 1 && (SystemClock.elapsedRealtime() - start) * tiles > budgetMs) {
                    throw MlException(MlException.Reason.TIMEOUT, "$model: $tiles tiles would exceed $budgetMs ms")
                }
                out
            }
        } catch (e: OutOfMemoryError) {
            throw MlException(MlException.Reason.OUT_OF_MEMORY, "$model: output too large", e)
        }
    }

    companion object {
        const val DENOISE = "nafnet_sidd"
        const val SUPER_RES = "realesr_general_x4"
    }
}
