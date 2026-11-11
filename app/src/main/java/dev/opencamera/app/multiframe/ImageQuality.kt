/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.multiframe

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import dev.opencamera.app.R
import dev.opencamera.capture.BurstSource
import kotlin.math.roundToInt

/**
 * "Image quality" of the full-resolution photo session, stored as the [BurstSource] preference:
 * Auto (largest output, RAW when it is clearly larger), Maximum (RAW) and Fast (YUV).
 * Megapixels come from the camera's stream configurations.
 */
object ImageQuality {
    const val KEY = "multi_frame_source"

    data class Megapixels(val raw: Int?, val yuv: Int) {
        /** Same 20 % margin as the session policy uses to prefer RAW in Auto. */
        val auto: Int get() = if (raw != null && raw * 5 > yuv * 6) raw else yuv
    }

    /** [aspect] (long side / short side) is the photo aspect: the session picks the largest size of it. */
    fun megapixels(manager: CameraManager, cameraId: String, aspect: Float = 4f / 3f): Megapixels? = runCatching {
        val map = manager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        fun mp(format: Int): Int? {
            val sizes = map.getOutputSizes(format) ?: return null
            val matching = sizes.filter {
                kotlin.math.abs(maxOf(it.width, it.height).toFloat() / minOf(it.width, it.height) - aspect) < 0.02f
            }.ifEmpty { sizes.toList() }
            return matching.maxOfOrNull { it.width.toLong() * it.height }?.let { (it / 1_000_000f).roundToInt() }
        }
        val yuv = mp(ImageFormat.YUV_420_888) ?: return null
        Megapixels(mp(ImageFormat.RAW_SENSOR), yuv)
    }.getOrNull()

    fun firstBackCamera(manager: CameraManager): String? = runCatching {
        manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        }
    }.getOrNull()

    /** Choices for the camera; Maximum is left out without RAW. */
    fun choices(mp: Megapixels?): List<BurstSource> =
        if (mp?.raw != null) listOf(BurstSource.AUTO, BurstSource.RAW, BurstSource.YUV)
        else listOf(BurstSource.AUTO, BurstSource.YUV)

    fun label(context: Context, source: BurstSource, mp: Megapixels?): String = when {
        mp == null -> context.getString(
            when (source) {
                BurstSource.AUTO -> R.string.image_quality_auto_plain
                BurstSource.RAW -> R.string.image_quality_max_plain
                BurstSource.YUV -> R.string.image_quality_fast_plain
            }
        )
        source == BurstSource.RAW && mp.raw != null -> context.getString(R.string.image_quality_max, mp.raw)
        source == BurstSource.YUV -> context.getString(R.string.image_quality_fast, mp.yuv)
        else -> context.getString(R.string.image_quality_auto, mp.auto)
    }

    /** The choice after [current], for the quick toggle. */
    fun next(current: BurstSource, mp: Megapixels?): BurstSource {
        val all = choices(mp)
        return all[(all.indexOf(current).coerceAtLeast(0) + 1) % all.size]
    }
}
