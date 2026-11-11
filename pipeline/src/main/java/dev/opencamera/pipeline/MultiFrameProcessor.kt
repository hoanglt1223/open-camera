/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

enum class MultiFrameMode(val nativeId: Int) {
    HDR(0),
    NIGHT(1),
    ASTRO(2),
    SUPER_RES(3),
}

/**
 * Native-backed burst: frames are copied into native memory as they arrive, then merged.
 * Not thread safe; use from one thread at a time and always [close].
 */
class Burst(width: Int, height: Int, val capacity: Int) : AutoCloseable {
    val width = width and 1.inv()
    val height = height and 1.inv()
    private var handle = NativePipeline.nativeCreateBurst(width, height, capacity)

    init {
        check(handle != 0L) { "Cannot allocate burst ${width}x$height x$capacity" }
    }

    val frameCount: Int
        get() = if (handle != 0L) NativePipeline.nativeFrameCount(handle) else 0

    /** Adds a YUV_420_888 frame given its planes. Returns false when full or on error. */
    fun addFrame(
        y: ByteBuffer, yRowStride: Int,
        u: ByteBuffer, v: ByteBuffer, uvRowStride: Int, uvPixelStride: Int,
    ): Boolean {
        check(handle != 0L) { "Burst closed" }
        return NativePipeline.nativeAddFrame(
            handle, y, yRowStride, u, v, uvRowStride, uvPixelStride
        )
    }

    /** Merges the burst; returns null if the native pipeline rejected it. */
    fun process(
        mode: MultiFrameMode,
        toneEvStep: Float,
        reference: Int = -1,
        progress: NativePipeline.ProgressListener? = null,
    ): MergedImage? {
        check(handle != 0L) { "Burst closed" }
        val out = ByteArray(width * height * 3 / 2)
        val stats = NativePipeline.nativeProcess(
            handle, mode.nativeId, toneEvStep, reference, out, progress
        ) ?: return null
        return MergedImage(
            width, height, out,
            referenceIndex = stats[0].toInt(),
            noiseSigma = stats[1].toFloat(),
            meanWeight = stats[2].toFloat(),
            msAlign = stats[3].toLong(),
            msMerge = stats[4].toLong(),
            msFinish = stats[5].toLong(),
            frameCount = frameCount,
        )
    }

    /** Combines the burst with a creative [op]; returns null if the native side rejected it. */
    fun stack(op: StackOp, params: StackParams = StackParams()): MergedImage? {
        check(handle != 0L) { "Burst closed" }
        val out = ByteArray(width * height * 3 / 2)
        val p = floatArrayOf(
            params.keepFraction, params.rlIterations.toFloat(), params.rlSigma, params.sharpen, params.strength,
        )
        val stats = NativePipeline.nativeStack(handle, op.nativeId, params.reference, params.align, p, out)
            ?: return null
        return MergedImage(
            width, height, out,
            referenceIndex = stats[0].toInt(),
            msMerge = stats[2].toLong(),
            frameCount = stats[1].toInt(),
        )
    }

    /** Frame indices from sharpest to softest; empty on failure. */
    fun rankBySharpness(): IntArray {
        check(handle != 0L) { "Burst closed" }
        return NativePipeline.nativeRankSharpness(handle) ?: IntArray(0)
    }

    /** Single frame copy, used as the fallback when merging fails. */
    fun exportFrame(index: Int): MergedImage? {
        check(handle != 0L) { "Burst closed" }
        val out = ByteArray(width * height * 3 / 2)
        if (!NativePipeline.nativeExportFrame(handle, index, out)) return null
        return MergedImage(width, height, out, referenceIndex = index, frameCount = 1)
    }

    override fun close() {
        if (handle != 0L) {
            NativePipeline.nativeReleaseBurst(handle)
            handle = 0L
        }
    }
}

/** Creative stacking operations; ids are shared with `stack/stack.h`. */
enum class StackOp(val nativeId: Int) {
    /** Simulated long exposure (silky water): aligned average. */
    MEAN(0),

    /** Light trails / light painting: lighten blend. */
    MAX(1),

    /** Passer-by removal: per-pixel median. */
    MEDIAN(2),

    /** Focus stacking: sharpest source per pixel, soft weights. */
    FOCUS(3),

    /** Moon: lucky imaging, average, mild Richardson-Lucy and unsharp mask. No synthesized detail. */
    MOON(4),
}

data class StackParams(
    val reference: Int = -1,
    val align: Boolean = true,
    val keepFraction: Float = 0.4f,
    val rlIterations: Int = 8,
    val rlSigma: Float = 1.2f,
    val sharpen: Float = 0.5f,
    val strength: Float = 1f,
)

class MergedImage(
    val width: Int,
    val height: Int,
    val nv21: ByteArray,
    val referenceIndex: Int,
    val noiseSigma: Float = 0f,
    val meanWeight: Float = 0f,
    val msAlign: Long = 0,
    val msMerge: Long = 0,
    val msFinish: Long = 0,
    val frameCount: Int,
) {
    fun toJpeg(quality: Int = 95): ByteArray {
        val stream = ByteArrayOutputStream(width * height / 3)
        YuvImage(nv21, ImageFormat.NV21, width, height, null)
            .compressToJpeg(Rect(0, 0, width, height), quality, stream)
        return stream.toByteArray()
    }
}

object MultiFrameProcessor {
    private const val LOG_TAG = "MultiFrameProcessor"

    /**
     * Merges [burst]; on any failure falls back to the first frame so a capture is never lost.
     */
    fun processOrFallback(
        burst: Burst,
        mode: MultiFrameMode,
        toneEvStep: Float,
        progress: NativePipeline.ProgressListener? = null,
    ): MergedImage? {
        val merged = runCatching { burst.process(mode, toneEvStep, -1, progress) }
            .onFailure { Log.e(LOG_TAG, "Merge threw", it) }
            .getOrNull()
        if (merged != null) return merged
        Log.w(LOG_TAG, "Merge failed for ${burst.frameCount} frames, using a single frame")
        return burst.exportFrame(0)
    }

    /** Creative stack; on failure falls back to the sharpest single frame. */
    fun stackOrFallback(burst: Burst, op: StackOp, params: StackParams = StackParams()): MergedImage? {
        val stacked = runCatching { burst.stack(op, params) }
            .onFailure { Log.e(LOG_TAG, "Stack threw", it) }
            .getOrNull()
        if (stacked != null) return stacked
        Log.w(LOG_TAG, "Stack $op failed for ${burst.frameCount} frames, using a single frame")
        val best = runCatching { burst.rankBySharpness().firstOrNull() }.getOrNull() ?: 0
        return burst.exportFrame(best)
    }
}
