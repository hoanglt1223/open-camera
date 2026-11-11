/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.capture

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** User facing multi-frame setting. */
enum class MultiFrameSetting { OFF, HDR_AUTO, HDR_ON, NIGHT, ASTRO }

enum class BurstKind { SINGLE, HDR, NIGHT, ASTRO, SUPER_RES }

/** Metering of the current preview, from the last capture result and the analysed frame. */
data class SceneMeter(
    val exposureTimeNs: Long,
    val iso: Int,
    /** Mean luma of the frame, 0..255. */
    val meanLuma: Float,
    /** Fraction of luma samples at or above 250. */
    val clippedFraction: Float,
    val aperture: Float = 1.8f,
) {
    /**
     * Scene illuminance estimate. Reflected-light meter equation L = K * N^2 / (t * S) with
     * K = 12.5 for a mid-grey frame, corrected by how far the frame is from mid-grey, then
     * E = pi * L / 0.18 for an 18% grey scene.
     */
    val luxEstimate: Float
        get() {
            val t = exposureTimeNs.coerceAtLeast(1) / 1e9f
            val s = iso.coerceAtLeast(1).toFloat()
            val luminance = 12.5f * aperture * aperture / (t * s)
            val greyRatio = (meanLuma.coerceIn(1f, 255f) / 118f).pow(2.2f)
            return (Math.PI.toFloat() * luminance * greyRatio / 0.18f)
        }
}

data class ExposureCaps(
    val minExposureNs: Long,
    val maxExposureNs: Long,
    val minIso: Int,
    val maxIso: Int,
)

data class MotionState(
    /** RMS angular velocity over the last second, rad/s. */
    val angularRms: Float,
    val onTripod: Boolean,
)

/**
 * A burst to capture. With [exposureTimeNs] == null the burst uses AE with [evCompensation]
 * stops; otherwise exposure and ISO are manual.
 */
data class BurstPlan(
    val kind: BurstKind,
    val frameCount: Int,
    val evCompensation: Float = 0f,
    val exposureTimeNs: Long? = null,
    val iso: Int? = null,
    val toneEvStep: Float = 0f,
) {
    val estimatedCaptureMs: Long
        get() = frameCount * max(33L, (exposureTimeNs ?: 33_000_000L) / 1_000_000L)
}

object ExposurePlanner {
    const val NIGHT_LUX = 10f
    const val ASTRO_LUX = 0.5f
    const val HIGH_MOTION_RAD_S = 0.2f

    private const val HANDHELD_MAX_EXPOSURE_NS = 333_000_000L
    private const val NIGHT_TRIPOD_MAX_EXPOSURE_NS = 1_000_000_000L
    private const val ASTRO_MAX_EXPOSURE_NS = 16_000_000_000L
    private const val ASTRO_MIN_EXPOSURE_NS = 4_000_000_000L
    private const val ASTRO_TOTAL_NS = 64_000_000_000L
    private const val ASTRO_MAX_ISO = 1600
    private const val MIN_NIGHT_EXPOSURE_NS = 33_000_000L

    /** Blur budget for handheld frames, in pixels of motion during one exposure. */
    private const val BLUR_BUDGET_PX = 4f

    fun plan(
        setting: MultiFrameSetting,
        scene: SceneMeter,
        motion: MotionState,
        caps: ExposureCaps,
        zoomRatio: Float = 1f,
        focalLengthPx: Float = 2400f,
    ): BurstPlan {
        val lux = scene.luxEstimate
        return when (setting) {
            MultiFrameSetting.OFF -> BurstPlan(BurstKind.SINGLE, 1)
            MultiFrameSetting.HDR_AUTO -> when {
                lux < NIGHT_LUX -> night(scene, motion, caps, focalLengthPx)
                zoomRatio >= 1.8f -> superRes(scene, motion)
                lux > 4000f && scene.clippedFraction < 0.002f -> BurstPlan(BurstKind.SINGLE, 1)
                else -> hdr(scene, motion, lux)
            }
            MultiFrameSetting.HDR_ON ->
                if (zoomRatio >= 1.8f) superRes(scene, motion) else hdr(scene, motion, lux)
            MultiFrameSetting.NIGHT -> night(scene, motion, caps, focalLengthPx)
            MultiFrameSetting.ASTRO ->
                if (motion.onTripod) astro(scene, caps) else night(scene, motion, caps, focalLengthPx)
        }
    }

    /** True when Astro should be offered: steady on a tripod in a very dark scene. */
    fun suggestAstro(scene: SceneMeter, motion: MotionState) =
        motion.onTripod && scene.luxEstimate < ASTRO_LUX

    fun hdr(scene: SceneMeter, motion: MotionState, lux: Float = scene.luxEstimate): BurstPlan {
        var frames = when {
            lux >= 400f -> 6
            lux >= 50f -> 8
            else -> 10
        }
        if (motion.angularRms > HIGH_MOTION_RAD_S) frames = min(frames, 6)
        val ev = when {
            scene.clippedFraction > 0.03f -> -1.5f
            scene.clippedFraction > 0.005f -> -1f
            else -> -0.7f
        }
        return BurstPlan(BurstKind.HDR, frames, evCompensation = ev, toneEvStep = -ev)
    }

    fun superRes(scene: SceneMeter, motion: MotionState): BurstPlan {
        val ev = if (scene.clippedFraction > 0.005f) -0.7f else 0f
        // Natural hand tremor provides the sub-pixel shifts; on a tripod there is nothing to gain
        // beyond denoising, which fewer frames already give.
        val frames = if (motion.onTripod) 6 else 8
        return BurstPlan(BurstKind.SUPER_RES, frames, evCompensation = ev, toneEvStep = 0.7f)
    }

    fun night(
        scene: SceneMeter,
        motion: MotionState,
        caps: ExposureCaps,
        focalLengthPx: Float = 2400f,
    ): BurstPlan {
        val maxT = if (motion.onTripod) {
            NIGHT_TRIPOD_MAX_EXPOSURE_NS
        } else {
            val omega = max(motion.angularRms, 0.01f)
            val blurLimited = (BLUR_BUDGET_PX / (omega * focalLengthPx) * 1e9f).toLong()
            min(blurLimited, HANDHELD_MAX_EXPOSURE_NS)
        }
        val (t, iso) = manualExposure(
            scene, caps,
            maxExposureNs = maxT.coerceAtLeast(MIN_NIGHT_EXPOSURE_NS),
            maxIso = caps.maxIso,
            // Night aims slightly under mid-grey; local tone mapping lifts the rest.
            targetLuma = 100f,
        )
        val budgetNs = if (motion.onTripod) 6_000_000_000L else 4_000_000_000L
        val frames = ceil(budgetNs.toDouble() / t).toInt().coerceIn(6, 15)
        return BurstPlan(BurstKind.NIGHT, frames, exposureTimeNs = t, iso = iso, toneEvStep = 0.6f)
    }

    fun astro(scene: SceneMeter, caps: ExposureCaps): BurstPlan {
        val maxT = min(caps.maxExposureNs, ASTRO_MAX_EXPOSURE_NS)
        val (t, iso) = manualExposure(
            scene, caps,
            maxExposureNs = maxT,
            maxIso = min(caps.maxIso, ASTRO_MAX_ISO),
            targetLuma = 90f,
            minExposureNs = min(ASTRO_MIN_EXPOSURE_NS, maxT),
        )
        val frames = (ASTRO_TOTAL_NS / t).toInt().coerceIn(4, 16)
        return BurstPlan(BurstKind.ASTRO, frames, exposureTimeNs = t, iso = iso, toneEvStep = 0f)
    }

    /**
     * Picks exposure time and ISO reaching [targetLuma] (linear brightness scaled from the
     * current metering), preferring long exposure up to [maxExposureNs] and low ISO.
     */
    internal fun manualExposure(
        scene: SceneMeter,
        caps: ExposureCaps,
        maxExposureNs: Long,
        maxIso: Int,
        targetLuma: Float,
        minExposureNs: Long = caps.minExposureNs,
    ): Pair<Long, Int> {
        val boost = (targetLuma / scene.meanLuma.coerceIn(1f, 255f)).pow(2.2f).coerceIn(0.25f, 64f)
        val product = scene.exposureTimeNs.toDouble() * scene.iso * boost
        val tMax = min(maxExposureNs, caps.maxExposureNs).coerceAtLeast(caps.minExposureNs)
        val lower = min(max(minExposureNs, caps.minExposureNs), tMax)
        // Longest exposure that still keeps ISO at or above minimum.
        val t = (product / caps.minIso).toLong().coerceIn(lower, tMax)
        val iso = (product / t).toInt().coerceIn(caps.minIso, max(caps.minIso, min(maxIso, caps.maxIso)))
        return t to iso
    }
}
