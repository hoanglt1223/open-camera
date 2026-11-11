/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.capture

import kotlin.math.pow

/** Manual exposure of one still in a burst sequence. */
data class FrameExposure(
    val exposureTimeNs: Long,
    val iso: Int,
    val frameDurationNs: Long,
)

/**
 * Turns a [BurstPlan] into the per-frame manual requests of one Camera2 burst. Every frame is
 * manual, so the sequence never waits for auto exposure in a freshly opened session.
 */
object BurstSequence {
    /** Session start, first-frame latency and result delivery on top of the frame times. */
    private const val OVERHEAD_MS = 2500L
    private const val PER_FRAME_SLACK_MS = 250L

    /**
     * [meteredExposureNs]/[meteredIso] are the preview's current exposure, used for plans that
     * rely on auto exposure (HDR, Super Res). Returns an empty list when no exposure is known.
     */
    fun frames(
        plan: BurstPlan,
        caps: ExposureCaps,
        meteredExposureNs: Long?,
        meteredIso: Int?,
        minFrameDurationNs: Long = 0L,
    ): List<FrameExposure> {
        if (plan.frameCount < 1) return emptyList()
        val (t, iso) = when {
            plan.exposureTimeNs != null && plan.iso != null -> plan.exposureTimeNs to plan.iso
            meteredExposureNs != null && meteredIso != null && meteredExposureNs > 0 && meteredIso > 0 -> {
                // Exposure compensation is applied to the time; the gain stays where AE put it
                // unless the time would leave the sensor range.
                val scale = 2.0.pow(plan.evCompensation.toDouble())
                val product = meteredExposureNs * meteredIso * scale
                val time = (meteredExposureNs * scale).toLong()
                    .coerceIn(caps.minExposureNs, maxOf(caps.minExposureNs, minOf(caps.maxExposureNs, meteredExposureNs)))
                time to (product / time).toInt()
            }
            else -> return emptyList()
        }
        val time = t.coerceIn(caps.minExposureNs, caps.maxExposureNs)
        val gain = iso.coerceIn(caps.minIso, maxOf(caps.minIso, caps.maxIso))
        val frame = FrameExposure(time, gain, maxOf(time, minFrameDurationNs))
        return List(plan.frameCount) { frame }
    }

    /** Upper bound for the whole sequence: frame times plus a fixed allowance, never minutes of slack. */
    fun timeoutMs(frames: List<FrameExposure>): Long =
        frames.sumOf { f ->
            val ms = f.frameDurationNs / 1_000_000L
            // Long exposures run ~5 % over their nominal frame time on gauguin (16 s frames every 16.7 s).
            ms + maxOf(PER_FRAME_SLACK_MS, ms / 8)
        } + OVERHEAD_MS

    /** Expected capture time of the sequence, for progress and logs. */
    fun expectedMs(frames: List<FrameExposure>): Long = frames.sumOf { it.frameDurationNs } / 1_000_000L
}
