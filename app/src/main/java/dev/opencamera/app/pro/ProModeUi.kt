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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.opencamera.app.R
import dev.opencamera.app.models.CameraConfiguration
import dev.opencamera.app.models.PhotoOutputFormat
import dev.opencamera.app.multiframe.RawMode
import dev.opencamera.app.repositories.PreferencesRepository
import dev.opencamera.app.ui.views.PreviewFrames
import dev.opencamera.app.viewmodels.CameraViewModel
import dev.opencamera.profiles.LensChip
import dev.opencamera.app.ui.overlay.ProUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        // Qualified: inside apply, a bare setEnabled() would resolve to View.setEnabled.
        setOnClickListener { this@ProModeUi.setEnabled(!store.enabled) }
    }

    private var caps: ProCapabilities? = null
    private var settings = ProSettings()
    private var configuration: CameraConfiguration? = null
    private var control: Camera2CameraControl? = null
    private var baseOptions: CaptureRequestOptions? = null
    private var afLocked = false
    private var lensChip: LensChip? = null

    private val active get() = store.enabled && caps != null && configuration is CameraConfiguration.Photo

    /** Exposure aids also run over the video preview; the manual controls stay photo-only. */
    private val aidsActive get() = store.enabled && caps != null &&
        (configuration is CameraConfiguration.Photo || configuration is CameraConfiguration.Video)

    /** Set once the GPU aids cannot run; they stay off for this activity. */
    private var aidsUnavailable: ExposureAids.Unavailable? =
        ExposureAids.gpuSupport(android.os.Build.VERSION.SDK_INT)?.also {
            Log.i(LOG_TAG, "Exposure aids: GPU path unavailable ($it)")
        }

    /** The skinned overlay draws the toggle and the dials; the View toggle and panel stay hidden. */
    var externalUi = false
        set(value) {
            field = value
            render()
        }

    private val _uiState = MutableStateFlow<ProUiState?>(null)
    val uiState: StateFlow<ProUiState?> = _uiState.asStateFlow()

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
        // The viewfinder starts under the status bar; keep the toggle out of the system gesture area.
        ViewCompat.setOnApplyWindowInsetsListener(toggle) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()).top
            v.updateLayoutParams<ConstraintLayout.LayoutParams> { topMargin = top + (12 * density).toInt() }
            insets
        }
        panel.listener = this
        overlay.onGpuFailure = { disableAids(it) }

        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.cameraConfiguration.collect {
                        if (it?.camera?.cameraId != configuration?.camera?.cameraId) caps = null
                        configuration = it
                        render()
                    }
                }
                launch {
                    viewModel.currentLensChip.collect { chip ->
                        if (chip == lensChip) return@collect
                        lensChip = chip
                        caps?.let {
                            settings = store.load(storeKey(it)).clampTo(it.ranges)
                            applyOptions()
                            render()
                        }
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
        appliedRequest = null
        basePending = true
        mergedRawSupported = cameraConfiguration is CameraConfiguration.Photo && viewModel.mergedRaw.supported(cameraInfo)
        caps = ProCapabilities.from(cameraInfo).also {
            settings = store.load(storeKey(it)).clampTo(it.ranges)
            Log.d(LOG_TAG, "Camera ${it.cameraId}: manualSensor=${it.manualSensor} " +
                    "manualPost=${it.manualPostProcessing} raw=${it.raw} iso=${it.ranges.iso} " +
                    "exposure=${it.ranges.exposureNs} minFocus=${it.ranges.minFocusDiopters} " +
                    "cct=${it.cctMode} kelvin=${it.kelvin}")
        }
        applyOptions()
        render()
    }

    fun setEnabled(enabled: Boolean) {
        store.enabled = enabled
        if (!enabled && afLocked) onAfLockChanged(false)
        applyOptions()
        render()
    }

    /** Profile lens id when the zoom selector knows it (logical-zoom stops share one camera). */
    private fun storeKey(c: ProCapabilities) =
        lensChip?.takeIf { it.cameraId == c.cameraId }?.lensId ?: c.cameraId

    /** Request-relevant Pro state last sent to [control]; null after a bind or while Pro is off. */
    private var appliedRequest: ProSettings? = null
    private var basePending = false

    private fun applyOptions() {
        // Only stops multi-frame plans; the session layout stays, so no dial ever rebinds.
        viewModel.multiFrame.manualExposureActive = active && settings.manualExposure
        viewModel.multiFrame.manualStill =
            if (active) dev.opencamera.app.multiframe.ManualStill.plan(settings.exposureNs, settings.iso) else null
        viewModel.mergedRaw.enabled = active && rawMode() == RawMode.MERGED
        val control = control ?: return
        val base = baseOptions ?: return
        val request = settings.copy(peaking = false, zebra = false, histogram = false).takeIf { active }
        // After a bind (or a Photo <-> Video switch in a shared session) the base options must be
        // sent even with Pro off: they carry the mode's frame rate and stabilization.
        if (request == appliedRequest && !basePending) return
        basePending = false
        val previous = appliedRequest
        appliedRequest = request
        if (request == null) {
            control.setCaptureRequestOptions(base)
        } else {
            val pro = CaptureRequestOptions.Builder().applyPro(settings, caps!!).build()
            // add() overwrites the Pro keys in one request update. A different key set (manual
            // exposure, manual focus or Kelvin switched) needs set() first to drop stale keys.
            if (previous == null || previous.keyShape() != request.keyShape()) control.setCaptureRequestOptions(base)
            control.addCaptureRequestOptions(pro)
        }
        Log.d(LOG_TAG, "Applied ${request ?: "auto"}")
    }

    private fun ProSettings.keyShape() = Triple(manualExposure, focusDiopters != null, wbMode == WbMode.KELVIN)

    private val saveHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingSave: Runnable? = null

    /** Dial drags change settings every few frames; the preferences file is written once they settle. */
    private fun scheduleSave(key: String, s: ProSettings) {
        pendingSave?.let(saveHandler::removeCallbacks)
        pendingSave = Runnable { store.save(key, s) }.also { saveHandler.postDelayed(it, SAVE_DELAY_MS) }
    }

    private fun render() {
        val photo = configuration is CameraConfiguration.Photo && caps != null
        val enabled = store.enabled
        val label = formatLabel()
        _uiState.value = ProUiState(enabled, photo, caps, settings, afLocked, label)
        overlay.isVisible = aidsActive
        if (!aidsActive) overlay.show(null)
        aidsUnavailable?.let { if (aidsActive && ExposureAids.suppressed(settings, it)) notifyAidsOff(it) }
        toggle.isVisible = photo && !externalUi
        panel.isVisible = active && !externalUi
        // The skinned overlay draws its own toggle and dials; the hidden View copies are not updated.
        if (externalUi) return
        toggle.background = GradientDrawable().apply {
            cornerRadius = 18 * density
            setColor(if (enabled) 0xFF26A69A.toInt() else 0x66000000)
        }
        toggle.contentDescription = activity.getString(
            R.string.pro_value_description,
            activity.getString(R.string.pro_toggle_description),
            activity.getString(if (enabled) R.string.pro_state_on else R.string.pro_state_off),
        )
        caps?.takeIf { active }?.let { panel.bind(it, settings, afLocked, label) }
    }

    private fun disableAids(reason: ExposureAids.Unavailable) {
        if (aidsUnavailable == reason) return
        Log.w(LOG_TAG, "Exposure aids disabled: $reason")
        aidsUnavailable = reason
        render()
    }

    private fun notifyAidsOff(reason: ExposureAids.Unavailable) {
        if (!store.firstAidsNotice(reason)) return
        android.widget.Toast.makeText(activity, R.string.pro_aids_unavailable, android.widget.Toast.LENGTH_LONG).show()
    }

    /** Merged RAW needs a RAW_SENSOR output with manual sensor control; checked once per bind. */
    private var mergedRawSupported = false

    private fun rawMode(): RawMode {
        val photo = configuration as? CameraConfiguration.Photo ?: return RawMode.OFF
        return when {
            store.rawMerged && mergedRawSupported -> RawMode.MERGED
            photo.photoOutputFormat == PhotoOutputFormat.RAW || photo.photoOutputFormat == PhotoOutputFormat.RAW_JPEG -> RawMode.SINGLE
            else -> RawMode.OFF
        }
    }

    private fun formatLabel(): String? {
        val photo = configuration as? CameraConfiguration.Photo ?: return null
        if (PhotoOutputFormat.RAW_JPEG !in photo.camera.supportedPhotoOutputFormats && !mergedRawSupported) return null
        return activity.getString(
            when {
                rawMode() == RawMode.MERGED -> R.string.pro_format_raw_merged
                photo.photoOutputFormat == PhotoOutputFormat.RAW -> R.string.pro_format_raw
                photo.photoOutputFormat == PhotoOutputFormat.RAW_JPEG -> R.string.pro_format_raw_jpeg
                else -> R.string.pro_format_jpeg
            }
        )
    }

    override fun onSettingsChanged(settings: ProSettings) {
        this.settings = settings
        caps?.let { scheduleSave(storeKey(it), settings) }
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

    // JPEG -> RAW+JPEG -> RAW -> RAW merged -> JPEG, skipping what the camera lacks. The camera's
    // own RAW options make the view model rebind, which calls bind() again; RAW merged keeps the
    // JPEG session and only changes what the shutter does.
    override fun onCycleFormat() {
        val photo = configuration as? CameraConfiguration.Photo ?: return
        val supported = photo.camera.supportedPhotoOutputFormats
        val merged = rawMode() == RawMode.MERGED
        when {
            merged -> {
                store.rawMerged = false
                preferencesRepository.enableRawImageCapture.value = false
            }

            photo.photoOutputFormat == PhotoOutputFormat.RAW_JPEG && PhotoOutputFormat.RAW in supported ->
                preferencesRepository.disableJpegWithRaw.value = true

            photo.photoOutputFormat == PhotoOutputFormat.RAW_JPEG || photo.photoOutputFormat == PhotoOutputFormat.RAW ->
                if (mergedRawSupported) {
                    store.rawMerged = true
                    preferencesRepository.enableRawImageCapture.value = false
                } else {
                    preferencesRepository.enableRawImageCapture.value = false
                }

            PhotoOutputFormat.RAW_JPEG in supported -> {
                preferencesRepository.disableJpegWithRaw.value = false
                preferencesRepository.enableRawImageCapture.value = true
            }

            mergedRawSupported -> store.rawMerged = true
        }
        applyOptions()
        render()
    }

    override fun onReset() {
        val c = caps ?: return
        if (afLocked) onAfLockChanged(false)
        onSettingsChanged(ProSettings().clampTo(c.ranges))
    }

    private suspend fun analyzeLoop() {
        // Two copies: the overlay draws one while the next preview frame lands in the other.
        val buffers = arrayOfNulls<Bitmap>(2)
        var next = 0
        var pixels = IntArray(0)
        val failures = ExposureAids.CopyFailures()
        while (true) {
            delay(FRAME_INTERVAL_MS)
            val s = settings
            val aids = ExposureAids.active(s, aidsUnavailable)
            val meter = active && s.manualExposure
            if (!aidsActive || !(aids.any || meter)) {
                failures.reset()
                continue
            }
            val (w, h) = PreviewFrames.scaledSize(viewFinder, COPY_LONG_SIDE) ?: continue
            val target = buffers[next]?.takeIf { it.width == w && it.height == h }
                ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { buffers[next] = it }
            val copied = PreviewFrames.copyInto(viewFinder, target)
            if (failures.record(copied)) {
                disableAids(ExposureAids.Unavailable.FRAME_COPY)
                continue
            }
            if (!copied) continue
            // A same-value write bumps the generation id so the GPU re-uploads the reused bitmap.
            target.setPixel(0, 0, target.getPixel(0, 0))
            next = 1 - next
            val needPixels = aids.histogram || meter
            if (needPixels && pixels.size != w * h) pixels = IntArray(w * h)
            val rect = surfaceRect()
            val frame = withContext(Dispatchers.Default) {
                if (needPixels) target.getPixels(pixels, 0, w, 0, 0, w, h)
                ProOverlayView.Frame(
                    source = target.takeIf { aids.shader },
                    sourceRect = rect,
                    aids = aids,
                    zebraPercent = s.zebraPercent,
                    histogram = if (aids.histogram) ExposureAids.histogram(pixels, w, HISTOGRAM_STEP) else null,
                    meterText = if (meter) meterText(pixels, w) else null,
                )
            }
            overlay.show(frame.takeIf { aidsActive })
        }
    }

    /** The preview surface's displayed bounds inside the viewfinder, or null to fill it. */
    private fun surfaceRect(): android.graphics.RectF? {
        val child = (0 until viewFinder.childCount).map(viewFinder::getChildAt)
            .firstOrNull { it is android.view.SurfaceView || it is android.view.TextureView } ?: return null
        if (child.width <= 0 || child.height <= 0) return null
        val r = android.graphics.RectF(0f, 0f, child.width.toFloat(), child.height.toFloat())
        child.matrix.mapRect(r)
        r.offset(child.left.toFloat(), child.top.toFloat())
        return r
    }

    private fun meterText(pixels: IntArray, width: Int): String {
        val hist = ExposureAids.histogram(pixels, width, HISTOGRAM_STEP, 256)
        val count = hist.sum().coerceAtLeast(1)
        val mean = hist.withIndex().sumOf { (v, n) -> v.toLong() * n } / count.toDouble()
        return activity.getString(R.string.pro_meter_reading, ExposureMath.formatEv(ExposureMath.meterOffset(mean).toFloat()))
    }

    private companion object {
        val LOG_TAG = ProModeUi::class.simpleName!!
        const val FRAME_INTERVAL_MS = 66L
        const val SAVE_DELAY_MS = 400L
        /** Long side of the preview copy the aids read; the shader upsamples it to the view. */
        const val COPY_LONG_SIDE = 640
        const val HISTOGRAM_STEP = 2
    }
}
