/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.creative

import kotlin.math.abs

/** Why a motion photo fell back to the plain still. */
enum class MotionFailure { NO_STREAM, TOO_FEW_FRAMES, LOW_MEMORY, ENCODE, WRITE }

object MotionPhotoPolicy {
    const val MIN_FRAMES = 8

    /** The failure that rules out a clip before encoding, or null when it can go ahead. */
    fun precheck(shutterTimestampNs: Long, framesInWindow: Int, lowMemory: Boolean): MotionFailure? = when {
        lowMemory -> MotionFailure.LOW_MEMORY
        shutterTimestampNs == 0L -> MotionFailure.NO_STREAM
        framesInWindow < MIN_FRAMES -> MotionFailure.TOO_FEW_FRAMES
        else -> null
    }

    /** Frames the ring may hold: [maxFrames], lowered so they fit in [budgetBytes]. */
    fun ringCapacity(frameBytes: Int, maxFrames: Int, budgetBytes: Long): Int =
        if (frameBytes <= 0) maxFrames else minOf(maxFrames.toLong(), budgetBytes / frameBytes).toInt().coerceAtLeast(1)

    /** Indices of [timestampsNs] inside [shutterNs] - [preNs] .. [shutterNs] + [postNs]. */
    fun window(timestampsNs: List<Long>, shutterNs: Long, preNs: Long, postNs: Long): List<Int> =
        timestampsNs.indices.filter { timestampsNs[it] in (shutterNs - preNs)..(shutterNs + postNs) }

    /** Index of the timestamp closest to [shutterNs]. */
    fun closest(timestampsNs: List<Long>, shutterNs: Long): Int =
        timestampsNs.indices.minByOrNull { abs(timestampsNs[it] - shutterNs) } ?: -1
}

/** Best-take choice among buffered frames: luma sharpness, weighted by eye openness when known. */
object BestTake {
    /** Sharpest take must beat the shutter frame by this factor to be offered. */
    const val MIN_GAIN = 1.15

    /**
     * Variance of the 4-neighbour Laplacian on an 8-bit luma plane ([stride] bytes per row),
     * sampled every [step] pixels. Higher is sharper.
     */
    fun sharpness(y: ByteArray, width: Int, height: Int, stride: Int = width, step: Int = 2): Double {
        require(width > 2 && height > 2 && stride >= width && y.size >= stride * (height - 1) + width)
        var n = 0L
        var sum = 0.0
        var sumSq = 0.0
        var r = 1
        while (r < height - 1) {
            val row = r * stride
            var c = 1
            while (c < width - 1) {
                val i = row + c
                val lap = (y[i - 1].toInt() and 0xff) + (y[i + 1].toInt() and 0xff) +
                    (y[i - stride].toInt() and 0xff) + (y[i + stride].toInt() and 0xff) - 4 * (y[i].toInt() and 0xff)
                sum += lap
                sumSq += lap.toDouble() * lap
                n++
                c += step
            }
            r += step
        }
        if (n == 0L) return 0.0
        val mean = sum / n
        return sumSq / n - mean * mean
    }

    /** Eye aspect ratio (lid gap / eye width) to openness 0 (closed) .. 1 (open). */
    fun eyeOpenness(aspectRatio: Float): Float = ((aspectRatio - EAR_CLOSED) / (EAR_OPEN - EAR_CLOSED)).coerceIn(0f, 1f)

    /** [eyes] is the least-open eye over all faces (0..1), or null when unknown or no face. */
    class Candidate(val index: Int, val sharpness: Double, val eyes: Float? = null)

    fun score(c: Candidate): Double = c.sharpness * (0.25 + 0.75 * (c.eyes ?: 1f))

    /** Indices of the [n] sharpest values, sharpest first. */
    fun sharpest(values: DoubleArray, n: Int): List<Int> = values.indices.sortedByDescending { values[it] }.take(n)

    /**
     * The candidate to offer instead of the shutter frame [shutterIndex], or null when none
     * scores clearly better than it.
     */
    fun pick(candidates: List<Candidate>, shutterIndex: Int): Candidate? {
        val shutter = candidates.firstOrNull { it.index == shutterIndex } ?: return null
        val best = candidates.maxByOrNull(::score) ?: return null
        if (best.index == shutterIndex) return null
        return best.takeIf { score(it) > score(shutter) * MIN_GAIN && score(it) > 0.0 }
    }

    private const val EAR_CLOSED = 0.12f
    private const val EAR_OPEN = 0.24f
}
