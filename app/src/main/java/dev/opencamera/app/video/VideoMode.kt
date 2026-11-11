/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.video

import androidx.annotation.StringRes
import dev.opencamera.app.R

/** Extra capture modes hosted by [VideoModesActivity]. */
enum class VideoMode(@StringRes val label: Int) {
    PRO_VIDEO(R.string.video_mode_pro_video),
    SLOW_MOTION(R.string.video_mode_slow_motion),
    TIME_LAPSE(R.string.video_mode_time_lapse),
    HYPERLAPSE(R.string.video_mode_hyperlapse),
    SHORT_VIDEO(R.string.video_mode_short_video),
    DUAL_VIDEO(R.string.video_mode_dual_video),
    SLOW_SHUTTER(R.string.video_mode_slow_shutter),
    TIME_FREEZE(R.string.video_mode_time_freeze),
    BOKEH_VIDEO(R.string.video_mode_bokeh_video),
    PANORAMA(R.string.video_mode_panorama),
    DOCUMENT(R.string.video_mode_document),
}

/** What the device offers; probed once per camera by [VideoCapsProbe]. */
data class VideoModeCaps(
    val canRecord: Boolean,
    val canAnalyze: Boolean,
    /** Highest constrained high-speed rate with a recordable quality, or 0. */
    val highSpeedFps: Int,
    /** Highest fixed regular frame rate (e.g. 60), used for re-timed slow motion. */
    val maxRegularFps: Int,
    val hasGyro: Boolean,
    val hasConcurrentFrontBack: Boolean,
    val openCv: Boolean,
    val hasSegmenter: Boolean,
)

/** How slow motion is produced on this device. */
sealed interface SlowMotionPlan {
    data class HighSpeed(val fps: Int) : SlowMotionPlan
    data class Retimed(val captureFps: Int, val factor: Int) : SlowMotionPlan
    data object Unsupported : SlowMotionPlan
}

object VideoModes {
    fun slowMotionPlan(caps: VideoModeCaps): SlowMotionPlan = when {
        !caps.canRecord -> SlowMotionPlan.Unsupported
        caps.highSpeedFps >= 120 -> SlowMotionPlan.HighSpeed(caps.highSpeedFps)
        caps.maxRegularFps >= 60 -> SlowMotionPlan.Retimed(caps.maxRegularFps, caps.maxRegularFps / 30)
        else -> SlowMotionPlan.Unsupported
    }

    fun isSupported(mode: VideoMode, caps: VideoModeCaps): Boolean = when (mode) {
        VideoMode.PRO_VIDEO, VideoMode.SHORT_VIDEO -> caps.canRecord
        VideoMode.SLOW_MOTION -> slowMotionPlan(caps) != SlowMotionPlan.Unsupported
        VideoMode.TIME_LAPSE, VideoMode.SLOW_SHUTTER, VideoMode.TIME_FREEZE -> caps.canAnalyze
        VideoMode.HYPERLAPSE -> caps.canAnalyze && caps.hasGyro
        VideoMode.DUAL_VIDEO -> caps.canRecord && caps.hasConcurrentFrontBack
        VideoMode.BOKEH_VIDEO -> caps.canAnalyze && caps.hasSegmenter
        VideoMode.PANORAMA -> caps.canAnalyze && caps.hasGyro && caps.openCv
        VideoMode.DOCUMENT -> caps.canAnalyze && caps.openCv
    }

    fun supported(caps: VideoModeCaps) = VideoMode.entries.filter { isSupported(it, caps) }
}
