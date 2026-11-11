/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.ml.tasks

import android.graphics.Bitmap
import dev.opencamera.ml.ImageTensors
import dev.opencamera.ml.MlRuntime

/** Single-channel probability map in [0, 1], row-major. */
class Mask(val width: Int, val height: Int, val data: FloatArray = FloatArray(width * height)) {
    init {
        require(data.size == width * height) { "Mask ${width}x$height has ${data.size} values" }
    }

    /** Fraction of pixels above 0.5. */
    fun coverage(): Float = data.count { it > 0.5f }.toFloat() / data.size

    fun inverted() = Mask(width, height, FloatArray(data.size) { 1f - data[it] })

    /** Bilinear sample at normalized coordinates. */
    fun sample(u: Float, v: Float): Float {
        val x = (u * width - 0.5f).coerceIn(0f, width - 1f)
        val y = (v * height - 0.5f).coerceIn(0f, height - 1f)
        val x0 = x.toInt()
        val y0 = y.toInt()
        val x1 = minOf(x0 + 1, width - 1)
        val y1 = minOf(y0 + 1, height - 1)
        val fx = x - x0
        val fy = y - y0
        val a = data[y0 * width + x0] * (1 - fx) + data[y0 * width + x1] * fx
        val b = data[y1 * width + x0] * (1 - fx) + data[y1 * width + x1] * fx
        return a * (1 - fy) + b * fy
    }
}

/**
 * Person segmentation. Prefers the multiclass model (person, hair and skin layers) when it has
 * been downloaded and falls back to the small bundled selfie model; null when neither runs.
 */
class Segmenter(private val runtime: MlRuntime) {
    class Result(
        val person: Mask,
        val hair: Mask?,
        val faceSkin: Mask?,
        val bodySkin: Mask?,
        val model: String,
    )

    fun segment(image: Bitmap, preferMulticlass: Boolean = true, timeoutMs: Long? = null): Result? {
        val input = ImageTensors.scaled(image, SIZE, SIZE)
        val tensor = ImageTensors.argbToNhwc(ImageTensors.pixels(input), SIZE, SIZE)
        if (input !== image) input.recycle()
        if (preferMulticlass && runtime.isAvailable(MULTICLASS)) {
            runtime.runOrNull(MULTICLASS, listOf(tensor), timeoutMs)?.let { out ->
                return multiclass(out.first().data)
            }
        }
        if (!runtime.isAvailable(SELFIE)) return null
        val out = runtime.runOrNull(SELFIE, listOf(tensor), timeoutMs) ?: return null
        val person = Mask(SIZE, SIZE, out.first().data.copyOf(SIZE * SIZE))
        return Result(person, null, null, null, SELFIE)
    }

    private fun multiclass(d: FloatArray): Result {
        val n = SIZE * SIZE
        val person = FloatArray(n)
        val hair = FloatArray(n)
        val face = FloatArray(n)
        val body = FloatArray(n)
        for (i in 0 until n) {
            // Channels: background, hair, body skin, face skin, clothes, others (accessories).
            val o = i * CLASSES
            var sum = 0f
            for (c in 0 until CLASSES) sum += d[o + c]
            val s = if (sum > 0f) 1f / sum else 0f
            person[i] = 1f - d[o] * s
            hair[i] = d[o + 1] * s
            body[i] = d[o + 2] * s
            face[i] = d[o + 3] * s
        }
        return Result(Mask(SIZE, SIZE, person), Mask(SIZE, SIZE, hair), Mask(SIZE, SIZE, face), Mask(SIZE, SIZE, body), MULTICLASS)
    }

    companion object {
        const val SELFIE = "selfie_segmenter"
        const val MULTICLASS = "selfie_multiclass"
        const val SIZE = 256
        private const val CLASSES = 6
    }
}
