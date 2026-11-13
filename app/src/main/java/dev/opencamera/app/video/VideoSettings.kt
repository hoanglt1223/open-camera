/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.video

import android.content.Context
import android.media.MediaRecorder

enum class Stabilization { OFF, HAL, GYRO }

enum class MicSource(val audioSource: Int) {
    CAMCORDER(MediaRecorder.AudioSource.CAMCORDER),
    MIC(MediaRecorder.AudioSource.MIC),
    UNPROCESSED(MediaRecorder.AudioSource.UNPROCESSED),
    VOICE(MediaRecorder.AudioSource.VOICE_RECOGNITION),
}

enum class BitrateLevel(val factor: Float) { LOW(0.6f), STANDARD(1f), HIGH(1.6f) }

/** User choices for recorded video modes. Indices into the probed lists are resolved at bind. */
data class VideoSettings(
    val qualityName: String = "FHD",
    val fps: Int = 30,
    val hevc: Boolean = false,
    val bitrate: BitrateLevel = BitrateLevel.STANDARD,
    val stabilization: Stabilization = Stabilization.OFF,
    val mic: MicSource = MicSource.CAMCORDER,
    val stereo: Boolean = true,
    val log: Boolean = false,
    val hdr: Boolean = false,
    val lapseIntervalMs: Long = 1000,
    val hyperlapseSpeed: Int = 8,
    val shortSeconds: Int = 15,
    val keepOriginalAudio: Boolean = false,
    val slowShutterLighten: Boolean = true,
    val slowShutterSeconds: Int = 4,
    val freezeSeconds: Int = 6,
    val documentEnhance: Int = 0,
    val logPreview: Boolean = true,
    val logBake: Boolean = true,
) {
    companion object {
        private const val PREFS = "video_modes"

        fun load(context: Context): VideoSettings {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val d = VideoSettings()
            return VideoSettings(
                qualityName = p.getString("quality", d.qualityName) ?: d.qualityName,
                fps = p.getInt("fps", d.fps),
                hevc = p.getBoolean("hevc", d.hevc),
                bitrate = enumOr(p.getString("bitrate", null), d.bitrate),
                stabilization = enumOr(p.getString("stab", null), d.stabilization),
                mic = enumOr(p.getString("mic", null), d.mic),
                stereo = p.getBoolean("stereo", d.stereo),
                log = p.getBoolean("log", d.log),
                hdr = p.getBoolean("hdr", d.hdr),
                lapseIntervalMs = p.getLong("lapse", d.lapseIntervalMs),
                hyperlapseSpeed = p.getInt("hyper", d.hyperlapseSpeed),
                shortSeconds = p.getInt("short", d.shortSeconds),
                keepOriginalAudio = p.getBoolean("orig_audio", d.keepOriginalAudio),
                slowShutterLighten = p.getBoolean("ss_lighten", d.slowShutterLighten),
                slowShutterSeconds = p.getInt("ss_seconds", d.slowShutterSeconds),
                freezeSeconds = p.getInt("freeze", d.freezeSeconds),
                documentEnhance = p.getInt("doc", d.documentEnhance),
                logPreview = p.getBoolean("log_preview", d.logPreview),
                logBake = p.getBoolean("log_bake", d.logBake),
            )
        }

        private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
            enumValues<E>().firstOrNull { it.name == name } ?: default
    }

    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("quality", qualityName).putInt("fps", fps).putBoolean("hevc", hevc)
            .putString("bitrate", bitrate.name).putString("stab", stabilization.name)
            .putString("mic", mic.name).putBoolean("stereo", stereo).putBoolean("log", log)
            .putBoolean("hdr", hdr).putLong("lapse", lapseIntervalMs).putInt("hyper", hyperlapseSpeed)
            .putInt("short", shortSeconds).putBoolean("orig_audio", keepOriginalAudio)
            .putBoolean("ss_lighten", slowShutterLighten).putInt("ss_seconds", slowShutterSeconds)
            .putInt("freeze", freezeSeconds).putInt("doc", documentEnhance)
            .putBoolean("log_preview", logPreview).putBoolean("log_bake", logBake)
            .apply()
    }
}

/** One recorder bind attempt; later attempts drop optional features. */
data class RecordAttempt(
    val qualityName: String?,
    val fps: Int,
    val hdr: Boolean,
    val log: Boolean,
    val halStabilization: Boolean,
    val hevc: Boolean,
)

/** Recorder-relevant camera abilities, as plain values. */
data class RecordCaps(
    val qualityNames: List<String>,
    val regularFps: List<Int>,
    val hdr: Boolean,
    val toneCurve: Boolean,
    val halStabilization: Boolean,
    val hevc: Boolean,
)

fun CameraFacts.recordCaps(hevc: Boolean) = RecordCaps(
    qualities.map { it.qualityName() }, regularFps, hdrRanges.isNotEmpty(), toneCurve, halStabilization, hevc,
)

object RecordPlan {
    /**
     * Attempts in order: as requested; without HDR/log; also 30 fps without HAL stabilization
     * and AVC; finally the recorder's default quality. Requested features the camera lacks are
     * dropped up front.
     */
    fun attempts(s: VideoSettings, caps: RecordCaps): List<RecordAttempt> {
        val quality = s.qualityName.takeIf { it in caps.qualityNames } ?: caps.qualityNames.firstOrNull()
        val fps = s.fps.takeIf { it in caps.regularFps } ?: 30
        val hdr = s.hdr && caps.hdr && !s.log
        val log = s.log && caps.toneCurve
        val hal = s.stabilization == Stabilization.HAL && caps.halStabilization
        val hevc = s.hevc && caps.hevc
        return listOf(
            RecordAttempt(quality, fps, hdr, log, hal, hevc),
            RecordAttempt(quality, fps, false, false, hal, hevc),
            RecordAttempt(quality, 30, false, false, false, false),
            RecordAttempt(null, 30, false, false, false, false),
        ).distinct()
    }
}

/** Short stable names for CameraX qualities (Quality has no public name accessor). */
fun androidx.camera.video.Quality.qualityName(): String = when (this) {
    androidx.camera.video.Quality.UHD -> "UHD"
    androidx.camera.video.Quality.FHD -> "FHD"
    androidx.camera.video.Quality.HD -> "HD"
    androidx.camera.video.Quality.SD -> "SD"
    else -> toString()
}

fun qualityFromName(name: String?): androidx.camera.video.Quality? = when (name) {
    "UHD" -> androidx.camera.video.Quality.UHD
    "FHD" -> androidx.camera.video.Quality.FHD
    "HD" -> androidx.camera.video.Quality.HD
    "SD" -> androidx.camera.video.Quality.SD
    else -> null
}
