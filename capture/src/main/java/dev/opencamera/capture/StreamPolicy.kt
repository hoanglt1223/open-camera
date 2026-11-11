/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.capture

/**
 * Photo session layouts.
 * - [STANDARD]: preview + JPEG capture (+ an analysis stream the HAL may keep small).
 * - [FULL_RES_YUV]: preview + full-resolution YUV, no JPEG stream. Bursts and single shots are
 *   taken from the YUV stream and encoded by the app.
 * - [FULL_RES_RAW]: preview + RAW_SENSOR capture + a small YUV stream for metering. Bursts are
 *   RAW frames developed by the app.
 */
enum class StreamConfig { STANDARD, FULL_RES_YUV, FULL_RES_RAW }

/**
 * User choice of burst source; AUTO uses RAW for Night/Astro when the camera has RAW, and for every
 * burst when RAW frames are larger than the biggest YUV stream next to preview.
 */
enum class BurstSource { AUTO, YUV, RAW }

object StreamPolicy {
    data class Inputs(
        val setting: MultiFrameSetting,
        val creativeActive: Boolean,
        val jpegOutput: Boolean,
        val noExtension: Boolean,
        val backCamera: Boolean,
        val singleCaptureIntent: Boolean,
        val flashOff: Boolean,
        val rawCapable: Boolean,
        val source: BurstSource,
        /** RAW_SENSOR has more pixels than the largest YUV size the HAL streams. */
        val rawLarger: Boolean = false,
    )

    fun choose(i: Inputs): StreamConfig {
        // Anything the app-encoded path cannot honour keeps the regular JPEG session.
        if (!i.jpegOutput || !i.noExtension || !i.backCamera || i.singleCaptureIntent || !i.flashOff) {
            return StreamConfig.STANDARD
        }
        if (i.creativeActive) return StreamConfig.FULL_RES_YUV
        // Pro manual exposure keeps the layout: a single frame of the full-resolution stream carries
        // the Pro request options, so moving a dial never reopens the session.
        if (i.setting == MultiFrameSetting.OFF) return StreamConfig.STANDARD
        val rawWanted = when (i.source) {
            BurstSource.RAW -> true
            BurstSource.YUV -> false
            BurstSource.AUTO -> i.rawLarger ||
                i.setting == MultiFrameSetting.NIGHT || i.setting == MultiFrameSetting.ASTRO
        }
        return if (rawWanted && i.rawCapable) StreamConfig.FULL_RES_RAW else StreamConfig.FULL_RES_YUV
    }

    /** Next configuration to try when [failed] cannot be bound or streams too small. */
    fun fallback(failed: StreamConfig): StreamConfig? = when (failed) {
        StreamConfig.FULL_RES_RAW -> StreamConfig.FULL_RES_YUV
        StreamConfig.FULL_RES_YUV -> StreamConfig.STANDARD
        StreamConfig.STANDARD -> null
    }
}
