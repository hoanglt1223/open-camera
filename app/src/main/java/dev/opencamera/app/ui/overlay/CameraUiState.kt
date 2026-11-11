/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.ui.overlay

import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.opencamera.app.models.CameraMode
import dev.opencamera.app.models.CameraState
import dev.opencamera.app.models.FlashMode
import dev.opencamera.app.models.GridMode
import dev.opencamera.app.models.TimerMode
import dev.opencamera.app.modes.CreativeMode
import dev.opencamera.app.pro.ProCapabilities
import dev.opencamera.app.pro.ProSettings
import dev.opencamera.app.video.VideoMode
import dev.opencamera.capture.MultiFrameSetting

/** Entries of the mode carousel. Night, Portrait and Pro are Photo with a feature switched on. */
enum class UiMode { MORE, NIGHT, PORTRAIT, PHOTO, VIDEO, PRO, QR }

object UiModes {
    fun derive(mode: CameraMode, pro: Boolean, portrait: Boolean, multiFrame: MultiFrameSetting) = when (mode) {
        CameraMode.VIDEO -> UiMode.VIDEO
        CameraMode.QR -> UiMode.QR
        CameraMode.PHOTO -> when {
            pro -> UiMode.PRO
            portrait -> UiMode.PORTRAIT
            multiFrame == MultiFrameSetting.NIGHT -> UiMode.NIGHT
            else -> UiMode.PHOTO
        }
    }

    fun available(proAvailable: Boolean, videoAvailable: Boolean, moreAvailable: Boolean) = UiMode.entries.filter {
        when (it) {
            UiMode.PRO -> proAvailable
            UiMode.VIDEO -> videoAvailable
            UiMode.MORE -> moreAvailable
            else -> true
        }
    }

    /**
     * Mode reached by a swipe; [forward] means towards the end of the list in reading order.
     * More is never entered by a swipe, it opens a sheet.
     */
    fun swipe(current: UiMode, forward: Boolean, modes: List<UiMode>): UiMode? {
        val list = modes.filter { it != UiMode.MORE }
        val i = list.indexOf(current)
        if (i < 0) return null
        return list.getOrNull(if (forward) i + 1 else i - 1)
    }
}

/** A control whose logic still lives in the classic View layout; the overlay mirrors it. */
data class LegacyControl(val label: String, val enabled: Boolean, val visible: Boolean)

/** Ids of the mirrored controls (secondary top bar of the View layout). */
enum class Control {
    ASPECT, EFFECT, LOOK, VIDEO_QUALITY, VIDEO_FPS, VIDEO_DYNAMIC_RANGE, MIC, TIMER, PORTRAIT,
    MULTI_FRAME, CREATIVE, MOTION_PHOTO, PRE_SHOT, GRID,
}

data class LensChipUi(val label: String, val selected: Boolean)

data class LookItem(val id: String, val name: String)

data class ProUiState(
    val enabled: Boolean,
    val available: Boolean,
    val caps: ProCapabilities?,
    val settings: ProSettings,
    val afLocked: Boolean,
    val formatLabel: String?,
)

/**
 * Values that change many times a second (zoom while pinching, the recording clock). They live
 * in snapshot state outside [CameraUiState], so a change only recomposes or redraws the few
 * places that read them instead of the whole overlay.
 */
@androidx.compose.runtime.Stable
class LiveUiValues {
    var zoomRatio by androidx.compose.runtime.mutableFloatStateOf(1f)
    var recordingNanos by androidx.compose.runtime.mutableLongStateOf(0L)
}

val LocalLiveUi = androidx.compose.runtime.staticCompositionLocalOf { LiveUiValues() }

@androidx.compose.runtime.Immutable
data class CameraUiState(
    val cameraMode: CameraMode = CameraMode.PHOTO,
    val uiMode: UiMode = UiMode.PHOTO,
    val modes: List<UiMode> = UiMode.entries.toList(),
    val cameraState: CameraState = CameraState.IDLE,
    val shutterEnabled: Boolean = true,
    /** Increments on every shutter press, also from volume keys, to drive the press animation. */
    val shutterPresses: Int = 0,
    val singleCapture: Boolean = false,
    val flash: FlashMode = FlashMode.OFF,
    val flashVisible: Boolean = true,
    val flashEnabled: Boolean = true,
    val grid: GridMode = GridMode.OFF,
    val timer: TimerMode = TimerMode.OFF,
    val multiFrame: MultiFrameSetting = MultiFrameSetting.HDR_AUTO,
    val creative: CreativeMode = CreativeMode.OFF,
    val motionPhoto: Boolean = false,
    val portrait: Boolean = false,
    val controls: Map<Control, LegacyControl> = emptyMap(),
    val lensChips: List<LensChipUi> = emptyList(),
    val minZoom: Float = 1f,
    val maxZoom: Float = 1f,
    /** Glyph rotation in degrees that keeps icons upright (0, 90, 180, 270). */
    val glyphRotation: Int = 0,
    val galleryUri: Uri? = null,
    val galleryLocked: Boolean = false,
    val captures: Int = 0,
    val canFlip: Boolean = true,
    val googleLens: Boolean = false,
    val looks: List<LookItem> = emptyList(),
    val lookId: String = "",
    val recordingPaused: Boolean = false,
    val pro: ProUiState? = null,
    /** The user's Glass blur switch (the device may still draw less). */
    val blurEnabled: Boolean = true,
    val moreModes: List<VideoMode> = VideoMode.entries.toList(),
    /** Preview bounds in the overlay's pixels, for the rounded corner masks. */
    val previewRect: RectF? = null,
    val insetTop: Int = 0,
    val insetBottom: Int = 0,
)

/** Everything the overlay can ask the camera to do; implemented by CameraActivity. */
interface CameraUiActions {
    fun shutter()
    fun selectMode(mode: UiMode)
    fun openMoreMode(mode: VideoMode)
    fun setCreative(mode: CreativeMode)
    fun setMultiFrame(setting: MultiFrameSetting)
    fun colorMatch()
    fun flash()
    fun flashLong()
    fun control(control: Control)
    fun lensChip(index: Int)
    fun zoomTo(ratio: Float)
    fun flip()
    fun gallery()
    fun pauseResume()
    fun selectLook(id: String)
    fun allSettings()
    fun googleLens()
    fun proSettings(settings: ProSettings)
    fun proAfLock(locked: Boolean)
    fun proFormat()
    fun proReset()
    fun setSkin(id: String)
    fun setBlur(enabled: Boolean)

    /** A downscaled preview frame for a sheet backdrop, or null. */
    suspend fun previewSnapshot(): Bitmap?
}
