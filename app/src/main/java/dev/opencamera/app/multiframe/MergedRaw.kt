/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.multiframe

import kotlin.coroutines.cancellation.CancellationException

/** Pro RAW setting. SINGLE is the camera's own RAW (CameraX); MERGED a merged multi-frame DNG. */
enum class RawMode { OFF, SINGLE, MERGED }

/** What a merged-RAW shot ended up saving, best first. A JPEG is saved in every case. */
enum class RawOutput { MERGED_DNG, SINGLE_DNG, JPEG_ONLY }

/** Why a merged-RAW shot fell back; each one is logged and noticed once. */
enum class RawFallbackReason {
    /** No RAW_SENSOR output, manual sensor control or Camera2 hand-over. */
    UNSUPPORTED,

    /** Not enough free memory for the merge (or even for a single DNG). */
    LOW_MEMORY,

    /** The burst failed or stopped before two frames arrived. */
    CAPTURE,

    /** Aligning, merging or writing the merged DNG failed. */
    MERGE,

    /** The platform DNG writer failed for the single frame. */
    SINGLE_DNG,
}

/** A step failure with its reason; other exceptions get the step's default reason. */
class RawStepException(val reason: RawFallbackReason, message: String, cause: Throwable? = null) :
    Exception(message, cause)

object MergedRawPolicy {
    const val MAX_FRAMES = 8
    const val MIN_MERGED_FRAMES = 2

    /** Total exposure budget of a hand-held merged burst. */
    const val MAX_BURST_NS = 2_000_000_000L

    /**
     * Native bytes per pixel at the peak of a merged shot: 4 (float sum) + 1 (block weights) +
     * 2 (copy of the reference for the single-DNG fallback) + 2 (merged mosaic) + 4 (float mosaic
     * of the JPEG development) + 1.5 (NV21) + pyramids and strip buffers.
     */
    const val MERGED_BYTES_PER_PIXEL = 15L

    /** Reference copy, JPEG development and NV21, plus the platform writer's own copy. */
    const val SINGLE_BYTES_PER_PIXEL = 10L

    /** Processing allowance per frame on top of the capture timeout; frames are merged as they arrive. */
    const val PROCESS_MS_PER_FRAME = 1500L

    data class Plan(val output: RawOutput, val frames: Int, val reason: RawFallbackReason?)

    /**
     * Picks the best output the memory allows and the frame count for [frameDurationNs]. A plan
     * for [RawOutput.JPEG_ONLY] takes no RAW burst at all.
     */
    fun plan(supported: Boolean, availableBytes: Long, width: Int, height: Int, frameDurationNs: Long): Plan {
        if (!supported || width <= 0 || height <= 0) return Plan(RawOutput.JPEG_ONLY, 0, RawFallbackReason.UNSUPPORTED)
        val pixels = width.toLong() * height
        if (availableBytes < pixels * SINGLE_BYTES_PER_PIXEL) return Plan(RawOutput.JPEG_ONLY, 0, RawFallbackReason.LOW_MEMORY)
        if (availableBytes < pixels * MERGED_BYTES_PER_PIXEL) return Plan(RawOutput.SINGLE_DNG, 1, RawFallbackReason.LOW_MEMORY)
        return Plan(RawOutput.MERGED_DNG, frameCount(frameDurationNs), null)
    }

    fun frameCount(frameDurationNs: Long): Int =
        (MAX_BURST_NS / frameDurationNs.coerceAtLeast(1L)).coerceIn(MIN_MERGED_FRAMES.toLong(), MAX_FRAMES.toLong()).toInt()

    /** Merged DNG needs at least two frames in the merge; fewer degrades to the single DNG. */
    fun start(plan: Plan, mergedFrames: Int, refCaptured: Boolean): RawOutput = when {
        !refCaptured -> RawOutput.JPEG_ONLY
        plan.output == RawOutput.MERGED_DNG && mergedFrames >= MIN_MERGED_FRAMES -> RawOutput.MERGED_DNG
        plan.output == RawOutput.JPEG_ONLY -> RawOutput.JPEG_ONLY
        else -> RawOutput.SINGLE_DNG
    }
}

/**
 * Merged DNG, else single-frame DNG, else JPEG only. Each failed step is reported with its
 * reason and the next one runs; the JPEG itself is saved by the caller whatever this returns.
 */
object RawFallbackChain {
    suspend fun run(
        start: RawOutput,
        mergedDng: suspend () -> Unit,
        singleDng: suspend () -> Unit,
        onFailure: (failed: RawOutput, reason: RawFallbackReason, error: Throwable) -> Unit,
    ): RawOutput {
        if (start == RawOutput.MERGED_DNG) {
            if (attempt(RawOutput.MERGED_DNG, RawFallbackReason.MERGE, mergedDng, onFailure)) return RawOutput.MERGED_DNG
        }
        if (start != RawOutput.JPEG_ONLY) {
            if (attempt(RawOutput.SINGLE_DNG, RawFallbackReason.SINGLE_DNG, singleDng, onFailure)) return RawOutput.SINGLE_DNG
        }
        return RawOutput.JPEG_ONLY
    }

    private suspend fun attempt(
        step: RawOutput,
        defaultReason: RawFallbackReason,
        block: suspend () -> Unit,
        onFailure: (RawOutput, RawFallbackReason, Throwable) -> Unit,
    ): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: OutOfMemoryError) {
        onFailure(step, RawFallbackReason.LOW_MEMORY, e)
        false
    } catch (e: Throwable) {
        onFailure(step, (e as? RawStepException)?.reason ?: defaultReason, e)
        false
    }
}
