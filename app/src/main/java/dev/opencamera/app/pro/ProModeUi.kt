/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.pro

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.FocusMeteringAction
import androidx.camera.view.PreviewView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.opencamera.app.R
import dev.opencamera.app.models.CameraConfiguration
import dev.opencamera.app.models.PhotoOutputFormat
import dev.opencamera.app.repositories.PreferencesRepository
import dev.opencamera.app.viewmodels.CameraViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns Pro mode: the toggle, the control panel, the preview overlay and the Camera2 options.
 * The activity only calls [attach] once and [bind] after every camera bind.
 */
@OptIn(ExperimentalCamera2Interop::class)
class ProModeUi(
    private val activity: AppCompatActivity,
    private val viewModel: CameraViewModel,
    private val preferencesRepository: PreferencesRepository,
    private val mainLayout: ConstraintLayout,
    private val viewFinder: PreviewView,
    private val panelBottomAnchorId: Int,
) : ProPanelView.Listener {
    private val store = ProSettingsStore(activity)
    private val density = activity.resources.displayMetrics.density

    private val panel = ProPanelView(activity).apply { id = View.generateViewId() }
    private val overlay = ProOverlayView(activity).apply { id = View.generateViewId() }
    private val toggle = TextView(activity).apply {
        id = View.generateViewId()
        text = activity.getString(R.string.pro_toggle)
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        gravity = Gravity.CENTER
        minHeight = (48 * density).toInt()
        val p = (14 * density).toInt()
        setPadding(p, 0, p, 0)
        setOnClickListener { setEnabled(!store.enabled) }
    }

    private var caps: ProCapabilities? = null
    private var settings = ProSettings()
    private var configuration: CameraConfiguration? = null
    private var control: Camera2CameraControl? = null
    private var baseOptions: CaptureRequestOptions? = null
    private var afLocked = false

    private val active get() = store.enabled && caps != null && configuration is CameraConfiguration.Photo

    fun attach() {
        mainLayout.addView(overlay, ConstraintLayout.LayoutParams(0, 0).apply {
            topToTop = viewFinder.id
            bottomToBottom = viewFinder.id
            startToStart = viewFinder.id
            endToEnd = viewFinder.id
        })
        mainLayout.addView(panel, ConstraintLayout.LayoutParams(0, ConstraintLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomToTop = panelBottomAnchorId
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
        })
        mainLayout.addView(toggle, ConstraintLayout.LayoutParams(
            ConstraintLayout.LayoutParams.WRAP_CONTENT, (36 * density).toInt(),
        ).apply {
            topToTop = viewFinder.id
            endToEnd = viewFinder.id
            val m = (12 * density).toInt()
            setMargins(m, m, m, m)
        })
        panel.listener = this

        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.cameraConfiguration.collect {
                        if (it?.camera?.cameraId != configuration?.camera?.cameraId) caps = null
                        configuration = it
                        render()
                    }
                }
                launch { analyzeLoop() }
            }
        }
    }

    /**
     * Applies the session's base options plus the Pro options. Called after each bind.
     */
    fun bind(
        cameraInfo: CameraInfo,
        camera2CameraControl: Camera2CameraControl,
        cameraConfiguration: CameraConfiguration,
        base: CaptureRequestOptions,
    ) {
        configuration = cameraConfiguration
        control = camera2CameraControl
        baseOptions = base
        afLocked = false
        caps = ProCapabilities.from(cameraInfo).also {
            settings = store.load(it.cameraId).clampTo(it.ranges)
            Log.d(LOG_TAG, "Camera ${it.cameraId}: manualSensor=${it.manualSensor} " +
                    "manualPost=${it.manualPostProcessing} raw=${it.raw} iso=${it.ranges.iso} " +
                    "exposure=${it.ranges.exposureNs} minFocus=${it.ranges.minFocusDiopters} " +
                    "cct=${it.cctMode} kelvin=${it.kelvin}")
        }
        applyOptions()
        render()
    }

    private fun setEnabled(enabled: Boolean) {
        store.enabled = enabled
        if (!enabled && afLocked) onAfLockChanged(false)
        applyOptions()
        render()
    }

    private fun applyOptions() {
        val control = control ?: return
        // set() drops keys from an earlier Pro state; add() layers the Pro keys on top.
        control.setCaptureRequestOptions(baseOptions ?: return)
        if (active) {
            control.addCaptureRequestOptions(
                CaptureRequestOptions.Builder().applyPro(settings, caps!!).build()
            )
        }
        Log.d(LOG_TAG, "Applied ${if (active) settings.toString() else "auto"}")
    }

    private fun render() {
        val photo = configuration is CameraConfiguration.Photo && caps != null
        toggle.isVisible = photo
        toggle.background = GradientDrawable().apply {
            cornerRadius = 18 * density
            setColor(if (store.enabled) 0xFF26A69A.toInt() else 0x66000000)
        }
        toggle.contentDescription = activity.getString(
            R.string.pro_value_description,
            activity.getString(R.string.pro_toggle_description),
            activity.getString(if (store.enabled) R.string.pro_state_on else R.string.pro_state_off),
        )
        panel.isVisible = active
        overlay.isVisible = active
        if (!active) overlay.show(null)
        caps?.takeIf { active }?.let { panel.bind(it, settings, afLocked, formatLabel()) }
    }

    private fun formatLabel(): String? {
        val photo = configuration as? CameraConfiguration.Photo ?: return null
        if (PhotoOutputFormat.RAW_JPEG !in photo.camera.supportedPhotoOutputFormats) return null
        return activity.getString(
            when (photo.photoOutputFormat) {
                PhotoOutputFormat.RAW -> R.string.pro_format_raw
                PhotoOutputFormat.RAW_JPEG -> R.string.pro_format_raw_jpeg
                else -> R.string.pro_format_jpeg
            }
        )
    }

    override fun onSettingsChanged(settings: ProSettings) {
        this.settings = settings
        caps?.let { store.save(it.cameraId, settings) }
        applyOptions()
        render()
    }

    override fun onAfLockChanged(locked: Boolean) {
        val cameraControl = viewModel.cameraController.cameraControl ?: return
        afLocked = locked
        if (locked) {
            val point = viewFinder.meteringPointFactory.createPoint(
                viewFinder.width / 2f, viewFinder.height / 2f,
            )
            cameraControl.startFocusAndMetering(
                FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                    .disableAutoCancel()
                    .build()
            )
        } else {
            cameraControl.cancelFocusAndMetering()
        }
        render()
    }

    // Changing these preferences makes the view model rebind, which calls bind() again.
    override fun onCycleFormat() {
        val photo = configuration as? CameraConfiguration.Photo ?: return
        val supported = photo.camera.supportedPhotoOutputFormats
        when (photo.photoOutputFormat) {
            PhotoOutputFormat.RAW_JPEG -> if (PhotoOutputFormat.RAW in supported) {
                preferencesRepository.disableJpegWithRaw.value = true
            } else {
                preferencesRepository.enableRawImageCapture.value = false
            }

            PhotoOutputFormat.RAW -> preferencesRepository.enableRawImageCapture.value = false

            else -> {
                preferencesRepository.disableJpegWithRaw.value = false
                preferencesRepository.enableRawImageCapture.value = true
            }
        }
    }

    override fun onReset() {
        val c = caps ?: return
        if (afLocked) onAfLockChanged(false)
        onSettingsChanged(ProSettings().clampTo(c.ranges))
    }

    private suspend fun analyzeLoop() {
        while (true) {
            delay(FRAME_INTERVAL_MS)
            val s = settings
            if (!active || !(s.peaking || s.zebra || s.histogram || s.manualExposure)) continue
            val full = viewFinder.bitmap ?: continue
            val frame = withContext(Dispatchers.Default) { analyze(full, s) }
            if (active) overlay.show(frame) else frame.mask?.recycle()
        }
    }

    private fun analyze(full: Bitmap, s: ProSettings): ProOverlayView.Frame {
        val w = ANALYSIS_WIDTH
        val h = (full.height.toLong() * w / full.width).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(full, w, h, true)
        full.recycle()
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        small.recycle()
        val luma = FrameAssists.lumaPlane(pixels)

        val mask = if (s.peaking || s.zebra) {
            val peaking = if (s.peaking) FrameAssists.peakingMask(luma, w, h, PEAKING_THRESHOLD) else null
            val zebra = if (s.zebra) FrameAssists.zebraMask(luma, w, ZEBRA_THRESHOLD) else null
            val out = IntArray(w * h) {
                when {
                    zebra?.get(it) == true -> ZEBRA_COLOR
                    peaking?.get(it) == true -> PEAKING_COLOR
                    else -> Color.TRANSPARENT
                }
            }
            Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
        } else {
            null
        }

        val meter = if (s.manualExposure) {
            val offset = ExposureMath.meterOffset(luma.average())
            activity.getString(R.string.pro_meter_reading, ExposureMath.formatEv(offset.toFloat()))
        } else {
            null
        }

        return ProOverlayView.Frame(
            mask = mask,
            histogram = if (s.histogram) FrameAssists.histogram(luma) else null,
            meterText = meter,
        )
    }

    private companion object {
        val LOG_TAG = ProModeUi::class.simpleName!!
        const val FRAME_INTERVAL_MS = 66L
        const val ANALYSIS_WIDTH = 320
        const val PEAKING_THRESHOLD = 220
        const val ZEBRA_THRESHOLD = 250
        const val PEAKING_COLOR = 0xFFFF3B30.toInt()
        const val ZEBRA_COLOR = 0xB0FFFFFF.toInt()
    }
}
