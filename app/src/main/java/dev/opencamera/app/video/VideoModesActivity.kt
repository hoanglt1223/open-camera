/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.video

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Gravity
import android.view.OrientationEventListener
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.CompositionSettings
import androidx.camera.core.ConcurrentCamera
import androidx.camera.core.ExtendableBuilder
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.HighSpeedVideoSessionConfig
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.AudioStats
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.opencamera.app.R
import dev.opencamera.app.processing.LookPreferences
import dev.opencamera.app.processing.LooksRepository
import dev.opencamera.app.robustness.FeatureGuard
import dev.opencamera.capture.video.GyroPath
import dev.opencamera.capture.video.SweepGuide
import dev.opencamera.pipeline.document.DocumentProcessor
import dev.opencamera.pipeline.document.PdfExporter
import dev.opencamera.pipeline.looks.CubeWriter
import dev.opencamera.pipeline.looks.LookParams
import dev.opencamera.pipeline.looks.LookPreviewSurfaceProvider
import dev.opencamera.pipeline.looks.LookSurfaceProcessor
import dev.opencamera.pipeline.looks.Lut3d
import dev.opencamera.pipeline.looks.ToneTable
import dev.opencamera.pipeline.video.FrameAccumulator
import dev.opencamera.pipeline.video.VideoRetimer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Host for the extra modes (pro video, slow motion, time-lapse, hyperlapse, short video, dual
 * video, movie effects, panorama, document). It binds the camera itself through
 * [ProcessCameraProvider] so each mode can pick its own session type. Every mode is probed
 * first and hidden when the device lacks what it needs; a failed bind steps down through
 * simpler configurations and finally drops the mode instead of crashing.
 */
@OptIn(ExperimentalCamera2Interop::class)
class VideoModesActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_MODE = "mode"
        private const val TAG = "VideoModes"
        private const val OPTION_BIND_DEBOUNCE_MS = 200L

        @Volatile
        private var hevcCache: Boolean? = null
        private val highSpeedCache = java.util.concurrent.ConcurrentHashMap<String, List<HighSpeedConfig>>()

        /** Camera characteristics read once per camera id (binder call). */
        private fun highSpeedConfigs(context: android.content.Context, cameraId: String): List<HighSpeedConfig> =
            highSpeedCache.getOrPut(cameraId) { HighSpeedSession.configs(context, cameraId) }
        private const val DOC_DETECT_INTERVAL_NS = 300_000_000L
        private const val MAX_PAGE_SIDE = 2480
    }

    private lateinit var previewView: PreviewView
    private lateinit var overlay: GuideOverlayView
    private lateinit var optionsRow: LinearLayout
    private lateinit var modesRow: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var shutter: Button
    private lateinit var pauseButton: Button
    private lateinit var flipButton: Button

    private var provider: ProcessCameraProvider? = null
    private var back = true
    private var facts: CameraFacts? = null
    private var caps: VideoModeCaps? = null
    private var modes: List<VideoMode> = emptyList()
    private val failedModes = mutableSetOf<VideoMode>()
    private var mode = VideoMode.PRO_VIDEO
    private var settings = VideoSettings()

    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeAttempt: RecordAttempt? = null
    private var recording: Recording? = null
    private var timeline: RecordingTimeline? = null
    private var recordRotationDeg = 0
    private var slowPlan: SlowMotionPlan = SlowMotionPlan.Unsupported
    private var micWarned = false
    private var recordName: String? = null

    /** Log grade for the current look, built off the main thread; key is look id and intensity. */
    @Volatile
    private var grade: Pair<Pair<String, Float>, Lut3d>? = null
    private var logProcessor: LookSurfaceProcessor? = null
    private var logPreviewFailed = false

    private val gyroPath = GyroPath()
    private val sweepGuide = SweepGuide()
    private val gyro by lazy {
        GyroSensor(this) { t, x, y, z ->
            gyroPath.add(t, x, y, z)
            synchronized(sweepGuide) { sweepGuide.onGyro(t, x, y) }
        }
    }

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val analysisDispatcher = analysisExecutor.asCoroutineDispatcher()
    private var imageCapture: ImageCapture? = null

    @Volatile
    private var session: FrameSession? = null
    private var sessionTicker: Job? = null

    @Volatile
    private var lastDocDetect = 0L
    private var lastDocFrame: Bitmap? = null
    private val pages = mutableListOf<Bitmap>()
    private var musicUri: Uri? = null
    private var busy = false
    private var deviceRotation = Surface.ROTATION_0
    private var highSpeed: HighSpeedSession? = null
    private var directHighSpeedPaused = false
    private lateinit var textureView: TextureView

    private val pickMusic = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) musicUri = uri
        refreshOptions()
    }

    private val orientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                deviceRotation = when (orientation) {
                    in 45..134 -> Surface.ROTATION_270
                    in 135..224 -> Surface.ROTATION_180
                    in 225..314 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
            }
        }
    }

    @delegate:SuppressLint("NewApi")
    private val thermalListener by lazy {
        PowerManager.OnThermalStatusChangedListener { status -> onThermal(status) }
    }

    private fun onThermal(status: Int) {
        if (status >= PowerManager.THERMAL_STATUS_SEVERE && recording != null) {
            toast(R.string.video_modes_thermal_stop)
            recording?.stop()
        } else if (status == PowerManager.THERMAL_STATUS_MODERATE && recording != null) {
            toast(R.string.video_modes_thermal_warm)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildLayout())
        if (!granted(Manifest.permission.CAMERA)) {
            toast(R.string.video_modes_no_camera)
            finish()
            return
        }
        settings = VideoSettings.load(this)
        intent.getStringExtra(EXTRA_MODE)?.let { name ->
            VideoMode.entries.firstOrNull { it.name == name }?.let { mode = it }
        }
        lifecycleScope.launch {
            provider = FeatureGuard.attempt(TAG, "camera provider") {
                ProcessCameraProvider.getInstance(this@VideoModesActivity).await()
            }
            if (provider == null) {
                toast(R.string.video_modes_no_camera)
                finish()
                return@launch
            }
            setupCamera()
        }
        if (hevcCache == null) lifecycleScope.launch(Dispatchers.Default) { hevcAvailable() }
    }

    override fun onResume() {
        super.onResume()
        orientationListener.enable()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                getSystemService(PowerManager::class.java)
                    ?.addThermalStatusListener(ContextCompat.getMainExecutor(this), thermalListener)
            }
        }
        if (mode == VideoMode.HYPERLAPSE || mode == VideoMode.PANORAMA) gyro.start()
        // The Camera2 high-speed session is not lifecycle-bound: reopen it.
        if (directHighSpeedPaused) {
            directHighSpeedPaused = false
            lifecycleScope.launch {
                delay(500)
                if (mode == VideoMode.SLOW_MOTION) requestBind()
            }
        }
    }

    override fun onPause() {
        orientationListener.disable()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { getSystemService(PowerManager::class.java)?.removeThermalStatusListener(thermalListener) }
        }
        recording?.stop()
        if (session != null) finishSession()
        highSpeed?.let { s ->
            if (s.recording) toggleDirectRecording(s)
            directHighSpeedPaused = true
        }
        gyro.stop()
        super.onPause()
    }

    override fun onDestroy() {
        closeHighSpeed()
        logProcessor?.release()
        logProcessor = null
        super.onDestroy()
        analysisExecutor.execute {
            session?.cancel()
            lastDocFrame?.recycle()
            pages.forEach { it.recycle() }
        }
        analysisExecutor.shutdown()
    }

    // --- Camera setup and binding ---

    /** Probes the camera off the main thread (recorder capabilities, codecs), then binds. */
    private suspend fun setupCamera() {
        val p = provider ?: return
        bindJob?.cancel()
        val info = VideoCapsProbe.cameraInfo(p, back)
            ?: VideoCapsProbe.cameraInfo(p, !back)?.also { back = !back }
        val app = applicationContext
        val (f, c) = withContext(Dispatchers.Default) {
            val f = info?.let { FeatureGuard.attempt(TAG, "probe") { VideoCapsProbe.facts(it) } }
            f?.cameraId?.let { highSpeedConfigs(app, it) }
            hevcAvailable()
            f to VideoCapsProbe.caps(app, p, f)
        }
        facts = f
        caps = c
        modes = VideoModes.supported(c).filter { it !in failedModes }
        if (modes.isEmpty()) {
            toast(R.string.video_modes_none)
            finish()
            return
        }
        if (mode !in modes) mode = modes.first()
        buildModeRow()
        kotlinx.coroutines.android.awaitFrame()
        bindMode()
    }

    private var bindJob: Job? = null

    /**
     * Binds in a coroutine of its own after the tap has drawn; quick option changes within
     * [delayMs] collapse into one bind with the latest settings.
     */
    private fun requestBind(delayMs: Long = 0) {
        bindJob?.cancel()
        bindJob = lifecycleScope.launch {
            delay(delayMs)
            kotlinx.coroutines.android.awaitFrame()
            bindMode()
        }
    }

    private fun selector() = VideoCapsProbe.selector(back)

    private fun bindMode() {
        val p = provider ?: return
        resetModeState()
        runCatching { p.unbindAll() }
        val ok = when (mode) {
            VideoMode.PRO_VIDEO, VideoMode.SHORT_VIDEO -> bindRecorder(settings, withAudioOptions = true)
            VideoMode.SLOW_MOTION -> bindSlowMotion()
            VideoMode.DUAL_VIDEO -> bindDual()
            VideoMode.DOCUMENT -> bindAnalysis(wide = false, withCapture = true)
            VideoMode.PANORAMA, VideoMode.SLOW_SHUTTER -> bindAnalysis(wide = false, withCapture = false)
            VideoMode.TIME_LAPSE, VideoMode.HYPERLAPSE, VideoMode.TIME_FREEZE, VideoMode.BOKEH_VIDEO ->
                bindAnalysis(wide = true, withCapture = false)
        }
        if (!ok) {
            dropCurrentMode()
            return
        }
        if (mode == VideoMode.HYPERLAPSE || mode == VideoMode.PANORAMA) gyro.start() else gyro.stop()
        refreshOptions()
        updateUi()
    }

    private fun dropCurrentMode() {
        Log.w(TAG, "mode $mode could not bind, hiding it")
        toast(getString(R.string.video_modes_mode_unavailable, getString(mode.label)))
        failedModes += mode
        modes = modes - mode
        if (modes.isEmpty()) {
            finish()
            return
        }
        mode = modes.first()
        buildModeRow()
        // The next mode binds in a later frame, so a chain of failures never blocks one frame.
        requestBind()
    }

    private fun resetModeState() {
        recording?.stop()
        highSpeed?.let { s -> if (s.recording) toggleDirectRecording(s) }
        closeHighSpeed()
        session?.let { s -> session = null; analysisExecutor.execute { s.cancel() } }
        sessionTicker?.cancel()
        videoCapture = null
        imageCapture = null
        activeAttempt = null
        overlay.quad = null
        overlay.freezeLine = -1f
        overlay.sweep = null
        overlay.audioLevel = -1f
        overlay.refresh()
        statusText.text = ""
    }

    /** Codec list scan (20-100 ms); done once per process, normally off the main thread. */
    private fun hevcAvailable(): Boolean = hevcCache ?: runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true) }
        }
    }.getOrDefault(false).also { hevcCache = it }

    private fun bitrateFor(qualityName: String?, fps: Int, level: BitrateLevel): Int {
        val base = when (qualityName) {
            "UHD" -> 45_000_000
            "FHD" -> 17_000_000
            "HD" -> 8_000_000
            else -> 4_000_000
        }
        return (base * level.factor * max(1f, fps / 30f)).roundToInt()
    }

    private fun <T> applyLog(builder: ExtendableBuilder<T>) {
        val points = facts?.maxToneCurvePoints ?: return
        Camera2Interop.Extender(builder)
            .setCaptureRequestOption(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)
            .setCaptureRequestOption(CaptureRequest.TONEMAP_CURVE, LogProfile.tonemapCurve(points))
    }

    // setAudioSource is library-restricted but is CameraX's only way to pick the capture source;
    // CameraX is pinned, and a source the device rejects falls back to CAMCORDER (Status event).
    @SuppressLint("RestrictedApi")
    private fun buildRecorder(a: RecordAttempt, s: VideoSettings, audioOptions: Boolean): Recorder =
        Recorder.Builder().apply {
            qualityFromName(a.qualityName)?.let {
                setQualitySelector(QualitySelector.from(it, FallbackStrategy.lowerQualityOrHigherThan(it)))
            }
            if (a.hevc) setVideoMimeType(MediaFormat.MIMETYPE_VIDEO_HEVC)
            setTargetVideoEncodingBitRate(bitrateFor(a.qualityName, a.fps, s.bitrate))
            if (audioOptions) {
                setAudioSource(s.mic.audioSource)
                setTargetAudioChannelCount(if (s.stereo) 2 else 1)
            }
        }.build()

    private fun newPreview(builder: Preview.Builder = Preview.Builder(), graded: Boolean = false) =
        builder.build().also { it.surfaceProvider = if (graded) gradedPreview() ?: previewView.surfaceProvider else previewView.surfaceProvider }

    /** Blocking (parses the look LUT): the log grade for the saved look, cached per look. */
    private fun logGrade(): Lut3d {
        val prefs = LookPreferences(this)
        val key = prefs.lookId to prefs.intensity
        grade?.takeIf { it.first == key }?.let { return it.second }
        val look = runCatching { LooksRepository.get(this).params(key.first, key.second) }.getOrNull()
        return LogProfile.gradeLut(look?.lut, look?.intensity ?: 0f).also { grade = key to it }
    }

    /**
     * Viewfinder that shows log footage through its grade, like the finished clip. Null when GL is
     * unavailable; a renderer failure later drops to the flat log preview with a notice.
     */
    private fun gradedPreview(): Preview.SurfaceProvider? {
        if (logPreviewFailed) return null
        val p = logProcessor ?: FeatureGuard.attempt(TAG, "log preview GL") { LookSurfaceProcessor() }
            ?.also { logProcessor = it }
        if (p == null) {
            logPreviewFailed = true
            toast(R.string.video_modes_log_preview_off)
            return null
        }
        lifecycleScope.launch {
            val lut = withContext(Dispatchers.Default) { FeatureGuard.attempt(TAG, "log grade") { logGrade() } }
            if (lut != null) p.setLook(gradeParams(lut))
        }
        return LookPreviewSurfaceProvider(this, previewView.surfaceProvider, { logProcessor }) {
            logPreviewFailed = true
            toast(R.string.video_modes_log_preview_off)
        }
    }

    private fun gradeParams(lut: Lut3d) = LookParams(
        lut = lut,
        tone = ToneTable.build(listOf(0f to 0f, 1f to 1f), 0f),
        intensity = 1f,
        grainAmount = 0f,
        grainSize = 1f,
        vignetteAmount = 0f,
        vignetteSoftness = 0.5f,
    )

    /** Regular recording with the [RecordPlan] fallback chain. */
    private fun bindRecorder(s: VideoSettings, withAudioOptions: Boolean): Boolean {
        val p = provider ?: return false
        val f = facts ?: return false
        val attempts = RecordPlan.attempts(s, f.recordCaps(hevcAvailable()))
        attempts.forEachIndexed { i, a ->
            val bound = FeatureGuard.attempt(TAG, "record bind $a") {
                val vb = VideoCapture.Builder(buildRecorder(a, s, withAudioOptions))
                    .setTargetFrameRate(Range(a.fps, a.fps))
                    .setVideoStabilizationEnabled(a.halStabilization)
                if (a.hdr) f.hdrRanges.firstOrNull()?.let { vb.setDynamicRange(it) }
                val pb = Preview.Builder()
                if (a.halStabilization && f.previewStabilization) pb.setPreviewStabilizationEnabled(true)
                if (a.log) {
                    applyLog(vb)
                    applyLog(pb)
                }
                val vc = vb.build()
                p.bindToLifecycle(this, selector(), newPreview(pb, graded = a.log && s.logPreview), vc)
                videoCapture = vc
                activeAttempt = a
            }
            if (bound != null) {
                if (i > 0) toast(R.string.video_modes_reduced)
                return true
            }
            runCatching { p.unbindAll() }
        }
        return false
    }

    private fun bindSlowMotion(): Boolean {
        val p = provider ?: return false
        val f = facts ?: return false
        val c = caps ?: return false
        slowPlan = VideoModes.slowMotionPlan(c)
        val plan = slowPlan
        if (plan is SlowMotionPlan.HighSpeed) {
            val fps = f.highSpeedRanges.map { it.upper }.let { list -> list.firstOrNull { it == 120 } ?: list.maxOrNull() ?: 120 }
            val bound = if (f.highSpeedQualities.isEmpty()) null else FeatureGuard.attempt(TAG, "high speed bind $fps") {
                val q = f.highSpeedQualities.first()
                val recorder = Recorder.Builder()
                    .setVideoCapabilitiesSource(f.highSpeedSource)
                    .setQualitySelector(QualitySelector.from(q, FallbackStrategy.lowerQualityOrHigherThan(q)))
                    .build()
                val vc = VideoCapture.Builder(recorder).build()
                val config = HighSpeedVideoSessionConfig(vc, newPreview(), Range(fps, fps), true)
                p.bindToLifecycle(this, selector(), config)
                videoCapture = vc
                slowPlan = SlowMotionPlan.HighSpeed(fps)
            }
            if (bound != null) return true
            runCatching { p.unbindAll() }
            if (bindDirectHighSpeed()) return true
            slowPlan = if (c.maxRegularFps >= 60) {
                SlowMotionPlan.Retimed(c.maxRegularFps, c.maxRegularFps / 30)
            } else {
                SlowMotionPlan.Unsupported
            }
            toast(R.string.video_modes_high_speed_fallback)
        }
        val retimed = slowPlan as? SlowMotionPlan.Retimed ?: return false
        val ok = bindRecorder(
            settings.copy(fps = retimed.captureFps, log = false, hdr = false, stabilization = Stabilization.OFF),
            withAudioOptions = false,
        )
        // The chain may have stepped down to 30 fps, which is no slow motion at all.
        if (ok && (activeAttempt?.fps ?: 0) < 60) {
            runCatching { p.unbindAll() }
            return false
        }
        return ok
    }

    /** Camera2 constrained high-speed session; its open is async and drops the mode on failure. */
    private fun bindDirectHighSpeed(): Boolean {
        val id = facts?.cameraId ?: return false
        val config = highSpeedConfigs(applicationContext, id).firstOrNull() ?: return false
        val s = FeatureGuard.attempt(TAG, "high speed session") { HighSpeedSession(applicationContext, id, config) }
            ?: return false
        highSpeed = s
        slowPlan = SlowMotionPlan.HighSpeed(config.fps)
        previewView.visibility = View.INVISIBLE
        textureView.visibility = View.VISIBLE
        textureView.layoutParams = FrameLayout.LayoutParams(
            MATCH, resources.displayMetrics.widthPixels * config.size.width / config.size.height, Gravity.CENTER,
        )
        lifecycleScope.launch {
            try {
                s.open(textureSurface(config.size))
            } catch (e: Exception) {
                Log.w(TAG, "direct high speed failed", e)
                if (highSpeed === s) {
                    closeHighSpeed()
                    dropCurrentMode()
                }
            }
        }
        return true
    }

    private suspend fun textureSurface(size: Size): Surface {
        val texture = textureView.surfaceTexture ?: suspendCancellableCoroutine { cont ->
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                    if (cont.isActive) cont.resume(st)
                }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = Unit
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
            }
        }
        texture.setDefaultBufferSize(size.width, size.height)
        return Surface(texture)
    }

    private fun closeHighSpeed() {
        val s = highSpeed ?: return
        highSpeed = null
        s.close()
        textureView.visibility = View.GONE
        previewView.visibility = View.VISIBLE
    }

    private fun toggleDirectRecording(s: HighSpeedSession) {
        busy = true
        updateUi()
        lifecycleScope.launch {
            if (s.recording) {
                statusText.setText(R.string.video_modes_processing)
                val file = runCatching { s.stop() }.getOrNull()
                val message = if (file == null) {
                    getString(R.string.video_modes_failed)
                } else {
                    try {
                        retimeFile(file, s.config.fps / 30)
                    } catch (e: Exception) {
                        Log.w(TAG, "retime failed", e)
                        getString(R.string.video_modes_post_failed)
                    } finally {
                        file.delete()
                    }
                }
                statusText.text = ""
                toast(message)
            } else {
                val file = File(cacheDir, "highspeed-${System.nanoTime()}.mp4")
                val started = try {
                    s.start(file, rotationDegrees())
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "high speed start failed", e)
                    false
                }
                if (!started) toast(R.string.video_modes_failed)
                else statusText.text = getString(R.string.video_opt_high_speed, s.config.fps)
            }
            busy = false
            updateUi()
        }
    }

    private suspend fun retimeFile(file: File, factor: Int): String = withContext(Dispatchers.IO) {
        val out = File(cacheDir, "slowmo-${System.nanoTime()}.mp4")
        try {
            val ok = file.inputStream().use { VideoRetimer.retime(it.fd, out, factor) }
            if (!ok) return@withContext getString(R.string.video_modes_post_failed)
            MediaSaver.saveVideo(applicationContext, out, "SLOWMO")
            getString(R.string.video_modes_saved)
        } finally {
            out.delete()
        }
    }

    private fun rotationDegrees() = when (deviceRotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    private fun bindDual(): Boolean {
        val p = provider ?: return false
        return FeatureGuard.attempt(TAG, "dual bind") {
            val a = RecordAttempt("FHD", 30, false, false, false, false)
            val vc = VideoCapture.Builder(buildRecorder(a, settings, true)).build()
            val group = UseCaseGroup.Builder().addUseCase(newPreview()).addUseCase(vc).build()
            val main = CompositionSettings.Builder().setAlpha(1f).setOffset(0f, 0f).setScale(1f, 1f).build()
            val pip = CompositionSettings.Builder().setAlpha(1f).setOffset(-0.3f, -0.4f).setScale(0.3f, 0.3f).build()
            p.bindToLifecycle(
                listOf(
                    ConcurrentCamera.SingleCameraConfig(CameraSelector.DEFAULT_BACK_CAMERA, group, main, this),
                    ConcurrentCamera.SingleCameraConfig(CameraSelector.DEFAULT_FRONT_CAMERA, group, pip, this),
                ),
            )
            videoCapture = vc
        } != null
    }

    private fun bindAnalysis(wide: Boolean, withCapture: Boolean): Boolean {
        val p = provider ?: return false
        val aspect = if (wide) {
            AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
        } else {
            AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        }
        val target = when {
            mode == VideoMode.TIME_FREEZE -> Size(1280, 720)
            wide -> Size(1920, 1080)
            else -> Size(1440, 1080)
        }
        val analysisSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(aspect)
            .setResolutionStrategy(
                ResolutionStrategy(target, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
            )
            .build()
        val aspectOnly = ResolutionSelector.Builder().setAspectRatioStrategy(aspect).build()
        for (capture in listOf(withCapture, false).distinct()) {
            val bound = FeatureGuard.attempt(TAG, "analysis bind capture=$capture") {
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(analysisSelector)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    // CameraX rotates natively (libyuv), much faster than a Bitmap matrix copy.
                    .setOutputImageRotationEnabled(true)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor, ::analyze)
                val useCases = mutableListOf<UseCase>(
                    newPreview(Preview.Builder().setResolutionSelector(aspectOnly)), analysis,
                )
                val ic = if (capture) {
                    ImageCapture.Builder().setResolutionSelector(aspectOnly).build().also { useCases += it }
                } else {
                    null
                }
                p.bindToLifecycle(this, selector(), *useCases.toTypedArray())
                imageCapture = ic
            }
            if (bound != null) return true
            runCatching { p.unbindAll() }
        }
        return false
    }

    // --- Analysis-frame modes ---

    private fun analyze(image: ImageProxy) {
        try {
            val ts = image.imageInfo.timestamp
            val rotation = image.imageInfo.rotationDegrees
            val s = session
            if (mode == VideoMode.DOCUMENT) {
                if (ts - lastDocDetect >= DOC_DETECT_INTERVAL_NS) {
                    lastDocDetect = ts
                    val bmp = image.toBitmap().rotated(rotation)
                    val quad = DocumentProcessor.detect(bmp)
                    lastDocFrame?.recycle()
                    lastDocFrame = bmp
                    runOnUiThread {
                        overlay.frameWidth = bmp.width
                        overlay.frameHeight = bmp.height
                        overlay.quad = quad
                        overlay.refresh()
                    }
                }
            } else if (s != null && !s.complete && s.wantsFrame(ts)) {
                val bmp = image.toBitmap().rotated(rotation)
                if (s is TimeFreezeSession) {
                    runOnUiThread { overlay.frameWidth = bmp.width; overlay.frameHeight = bmp.height }
                }
                s.onFrame(bmp, ts)
                if (s.complete) runOnUiThread { if (session === s) finishSession() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "analysis failed in $mode", e)
            runOnUiThread { if (session != null) finishSession() }
        } finally {
            image.close()
        }
    }

    private fun startSession(s: FrameSession) {
        session = s
        sessionTicker = lifecycleScope.launch {
            while (isActive) {
                statusText.text = s.status
                when (s) {
                    is TimeFreezeSession -> overlay.freezeLine = s.progress
                    is PanoramaSession -> {
                        val advice = synchronized(sweepGuide) {
                            overlay.sweep = sweepGuide.sweptDeg / sweepGuide.maxSweepDeg
                            overlay.pitchDeg = sweepGuide.pitchDeg
                            sweepGuide.advice()
                        }
                        statusText.text = getString(
                            when (advice) {
                                SweepGuide.Advice.TOO_HIGH -> R.string.video_modes_pano_lower
                                SweepGuide.Advice.TOO_LOW -> R.string.video_modes_pano_raise
                                else -> R.string.video_modes_pano_turn
                            },
                        ) + " · " + s.status
                    }
                    else -> Unit
                }
                overlay.refresh()
                delay(100)
            }
        }
        updateUi()
    }

    private fun finishSession() {
        val s = session ?: return
        session = null
        sessionTicker?.cancel()
        busy = true
        statusText.setText(R.string.video_modes_processing)
        updateUi()
        lifecycleScope.launch {
            val message = withContext(analysisDispatcher) {
                try {
                    s.finish(applicationContext)
                } catch (e: Exception) {
                    Log.w(TAG, "finish failed", e)
                    s.cancel()
                    getString(R.string.video_modes_failed)
                }
            }
            overlay.freezeLine = -1f
            overlay.sweep = null
            overlay.refresh()
            busy = false
            statusText.text = ""
            toast(message)
            updateUi()
        }
    }

    private fun captureDocumentPage() {
        val ic = imageCapture
        busy = true
        updateUi()
        if (ic == null) {
            analysisExecutor.execute {
                val frame = lastDocFrame?.let { it.copy(Bitmap.Config.ARGB_8888, false) }
                addPage(frame)
            }
            return
        }
        ic.takePicture(analysisExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bmp = try {
                    image.toBitmap().rotated(image.imageInfo.rotationDegrees)
                } catch (e: Exception) {
                    Log.w(TAG, "page decode failed", e)
                    null
                } finally {
                    image.close()
                }
                addPage(bmp)
            }

            override fun onError(exception: ImageCaptureException) {
                Log.w(TAG, "page capture failed", exception)
                addPage(lastDocFrame?.copy(Bitmap.Config.ARGB_8888, false))
            }
        })
    }

    /** Runs on the analysis thread. */
    private fun addPage(frame: Bitmap?) {
        val result = frame?.let {
            FeatureGuard.attempt(TAG, "document page") {
                val enhance = DocumentProcessor.Enhance.entries[settings.documentEnhance]
                val page = DocumentProcessor.process(it, DocumentProcessor.detect(it), enhance)
                it.recycle()
                val s = MAX_PAGE_SIDE.toFloat() / max(page.width, page.height)
                if (s < 1f) {
                    Bitmap.createScaledBitmap(page, (page.width * s).roundToInt(), (page.height * s).roundToInt(), true)
                        .also { scaled -> if (scaled !== page) page.recycle() }
                } else {
                    page
                }
            }
        }
        if (result != null) pages += result
        val count = pages.size
        runOnUiThread {
            busy = false
            toast(if (result != null) getString(R.string.video_modes_page_added, count) else getString(R.string.video_modes_failed))
            refreshOptions()
            updateUi()
        }
    }

    private fun exportPdf() {
        if (busy) return
        busy = true
        updateUi()
        analysisExecutor.execute {
            val ok = pages.isNotEmpty() && FeatureGuard.attempt(TAG, "pdf export") {
                MediaSaver.savePdf(applicationContext, "SCAN") { PdfExporter.write(pages, it) }
            } != null
            if (ok) {
                pages.forEach { it.recycle() }
                pages.clear()
            }
            runOnUiThread {
                busy = false
                toast(if (ok) R.string.video_modes_pdf_saved else R.string.video_modes_failed)
                refreshOptions()
                updateUi()
            }
        }
    }

    // --- Recording ---

    private fun useGyroEis() = mode == VideoMode.PRO_VIDEO && settings.stabilization == Stabilization.GYRO &&
        caps?.hasGyro == true

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        val vc = videoCapture ?: return
        FeatureGuard.attempt(TAG, "target rotation") { vc.targetRotation = deviceRotation }
        recordRotationDeg = rotationDegrees()
        val prefix = when (mode) {
            VideoMode.SLOW_MOTION -> if (slowPlan is SlowMotionPlan.HighSpeed) "SLOWMO" else "SLOWMO_SRC"
            VideoMode.SHORT_VIDEO -> "SHORT"
            VideoMode.DUAL_VIDEO -> "DUAL"
            else -> "VID"
        }
        val name = MediaSaver.name(prefix).also { recordName = it }
        val options = MediaStoreOutputOptions.Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(MediaSaver.outputOptionsValues(name))
            .apply { if (mode == VideoMode.SHORT_VIDEO) setDurationLimitMillis(settings.shortSeconds * 1000L) }
            .build()
        micWarned = false
        val started = FeatureGuard.attempt(TAG, "start recording") {
            var pending = vc.output.prepareRecording(this, options)
            if (mode != VideoMode.SLOW_MOTION && granted(Manifest.permission.RECORD_AUDIO)) {
                pending = pending.withAudioEnabled()
            }
            if (useGyroEis()) {
                gyroPath.clear()
                gyro.start()
            }
            pending.start(ContextCompat.getMainExecutor(this), ::onRecordEvent)
        }
        if (started == null) {
            toast(R.string.video_modes_failed)
            return
        }
        recording = started
        pauseButton.tag = false
        pauseButton.setText(R.string.video_modes_pause)
        updateUi()
    }

    private fun onRecordEvent(event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Start -> timeline = RecordingTimeline(SystemClock.elapsedRealtimeNanos())
            is VideoRecordEvent.Pause -> {
                timeline?.onPause(SystemClock.elapsedRealtimeNanos())
                updateUi()
            }
            is VideoRecordEvent.Resume -> {
                timeline?.onResume(SystemClock.elapsedRealtimeNanos())
                updateUi()
            }
            is VideoRecordEvent.Status -> {
                val stats = event.recordingStats
                val seconds = stats.recordedDurationNanos / 1_000_000_000
                statusText.text = getString(R.string.video_modes_duration, seconds / 60, seconds % 60)
                val audio = stats.audioStats
                overlay.audioLevel = if (audio.hasAudio()) audio.audioAmplitude.toFloat() else -1f
                overlay.refresh()
                if (audio.audioState == AudioStats.AUDIO_STATE_SOURCE_ERROR && !micWarned) {
                    micWarned = true
                    toast(R.string.video_modes_mic_failed)
                    settings = settings.copy(mic = MicSource.CAMCORDER).also { it.save(this) }
                }
            }
            is VideoRecordEvent.Finalize -> {
                recording = null
                overlay.audioLevel = -1f
                overlay.refresh()
                statusText.text = ""

                val uri = event.outputResults.outputUri
                if (event.error == VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA || uri == Uri.EMPTY) {
                    toast(R.string.video_modes_failed)
                    updateUi()
                    return
                }
                if (activeAttempt?.log == true) recordName?.let { exportGradeCube(it) }
                postProcess(uri)
            }
        }
    }

    private fun postProcess(uri: Uri) {
        val job: suspend () -> String = when {
            mode == VideoMode.SLOW_MOTION && slowPlan is SlowMotionPlan.Retimed -> {
                { retime(uri, (slowPlan as SlowMotionPlan.Retimed).factor) }
            }
            mode == VideoMode.PRO_VIDEO && (LogOutput.bakes(activeAttempt?.log == true, settings.logBake) || useGyroEis()) -> {
                { runPost(uri, withContext(Dispatchers.IO) { proJob() }, "VID_POST") }
            }
            mode == VideoMode.SHORT_VIDEO && musicUri != null -> {
                { runPost(uri, VideoPostProcessor.Job(music = musicUri, keepOriginalAudio = settings.keepOriginalAudio), "SHORT_MUSIC") }
            }
            else -> {
                gyro.takeIf { mode != VideoMode.HYPERLAPSE && mode != VideoMode.PANORAMA }?.stop()
                toast(R.string.video_modes_saved)
                updateUi()
                return
            }
        }
        busy = true
        statusText.setText(R.string.video_modes_processing)
        updateUi()
        lifecycleScope.launch {
            val message = try {
                job()
            } catch (e: Exception) {
                Log.w(TAG, "post-processing failed", e)
                getString(R.string.video_modes_post_failed)
            }
            gyro.stop()
            busy = false
            statusText.text = ""
            toast(message)
            updateUi()
        }
    }

    /** Blocking: parses the look LUT; call off the main thread. */
    private fun proJob(): VideoPostProcessor.Job {
        val cube = if (LogOutput.bakes(activeAttempt?.log == true, settings.logBake)) {
            LogProfile.toColorCube(logGrade())
        } else {
            null
        }
        val eis = timeline?.takeIf { useGyroEis() && !gyroPath.isEmpty }?.let {
            GyroEisTransformation(gyroPath, it, facts?.focalOverSensor ?: 0.8f, recordRotationDeg, mirrored = !back)
        }
        return VideoPostProcessor.Job(lutCube = cube, eis = eis)
    }

    /** Writes the grade the viewfinder showed next to the log clip, for grading on a computer. */
    private fun exportGradeCube(videoName: String) {
        val app = applicationContext
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                FeatureGuard.attempt(TAG, "cube export") {
                    val lut = logGrade()
                    MediaSaver.saveDocument(app, LogProfile.sidecarName(videoName), "application/octet-stream") {
                        it.write(CubeWriter.write(lut, LogOutput.cubeTitle(videoName)).toByteArray())
                    }
                }
            }
            if (saved == null) toast(R.string.video_modes_cube_failed)
        }
    }

    private suspend fun runPost(input: Uri, job: VideoPostProcessor.Job, prefix: String): String {
        if (job.isEmpty) return getString(R.string.video_modes_saved)
        val out = File(cacheDir, "post-${System.nanoTime()}.mp4")
        try {
            val error = VideoPostProcessor.run(applicationContext, input, out, job)
            if (error != null) {
                Log.w(TAG, "post: $error")
                return getString(R.string.video_modes_post_failed)
            }
            withContext(Dispatchers.IO) { MediaSaver.saveVideo(applicationContext, out, prefix) }
            return getString(R.string.video_modes_saved_post)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { out.delete() }
        }
    }

    private suspend fun retime(source: Uri, factor: Int): String = withContext(Dispatchers.IO) {
        val out = File(cacheDir, "slowmo-${System.nanoTime()}.mp4")
        try {
            val ok = contentResolver.openFileDescriptor(source, "r")?.use {
                VideoRetimer.retime(it.fileDescriptor, out, factor)
            } == true
            if (!ok) return@withContext getString(R.string.video_modes_post_failed)
            MediaSaver.saveVideo(applicationContext, out, "SLOWMO")
            runCatching { contentResolver.delete(source, null, null) }
            getString(R.string.video_modes_saved)
        } finally {
            out.delete()
        }
    }

    // --- UI ---

    private fun onShutter() {
        if (busy) return
        when (mode) {
            VideoMode.SLOW_MOTION -> highSpeed?.let { toggleDirectRecording(it) }
                ?: if (recording != null) recording?.stop() else startRecording()
            VideoMode.PRO_VIDEO, VideoMode.SHORT_VIDEO, VideoMode.DUAL_VIDEO ->
                if (recording != null) recording?.stop() else startRecording()
            VideoMode.DOCUMENT -> captureDocumentPage()
            VideoMode.BOKEH_VIDEO -> toast(getString(R.string.video_modes_mode_unavailable, getString(mode.label)))
            else -> if (session != null) finishSession() else newSession()?.let { startSession(it) }
        }
        updateUi()
    }

    private fun newSession(): FrameSession? = FeatureGuard.attempt(TAG, "session $mode") {
        val focal = facts?.focalOverSensor ?: 0.8f
        when (mode) {
            VideoMode.TIME_LAPSE -> TimeLapseSession(cacheDir, settings.lapseIntervalMs)
            VideoMode.HYPERLAPSE -> {
                gyroPath.clear()
                HyperlapseSession(cacheDir, settings.hyperlapseSpeed, gyroPath, focal)
            }
            VideoMode.TIME_FREEZE -> TimeFreezeSession(cacheDir, settings.freezeSeconds * 1000L)
            VideoMode.SLOW_SHUTTER -> SlowShutterSession(
                if (settings.slowShutterLighten) FrameAccumulator.Mode.LIGHTEN else FrameAccumulator.Mode.AVERAGE,
                settings.slowShutterSeconds * 1000L,
            )
            VideoMode.PANORAMA -> {
                synchronized(sweepGuide) { sweepGuide.reset() }
                PanoramaSession(sweepGuide, focal)
            }
            else -> error("no frame session for $mode")
        }
    }

    private fun updateUi() {
        val recordingNow = recording != null || highSpeed?.recording == true
        val capturing = recordingNow || session != null
        shutter.text = getString(
            when {
                busy -> R.string.video_modes_busy
                mode == VideoMode.DOCUMENT -> R.string.video_modes_capture
                capturing -> R.string.video_modes_stop
                else -> R.string.video_modes_start
            },
        )
        shutter.background = pill(if (capturing) Color.RED else Color.WHITE, 40f)
        shutter.setTextColor(if (capturing) Color.WHITE else Color.BLACK)
        shutter.isEnabled = !busy
        pauseButton.visibility = if (recording != null && mode != VideoMode.SLOW_MOTION) View.VISIBLE else View.GONE
        flipButton.visibility = if (capturing || mode == VideoMode.DUAL_VIDEO) View.INVISIBLE else View.VISIBLE
        val locked = capturing || busy
        for (i in 0 until modesRow.childCount) modesRow.getChildAt(i).isEnabled = !locked
        for (i in 0 until optionsRow.childCount) optionsRow.getChildAt(i).isEnabled = !locked
    }

    private fun buildModeRow() {
        modesRow.removeAllViews()
        for (m in modes) {
            modesRow.addView(chip(getString(m.label), selected = m == mode) {
                if (m != mode) {
                    mode = m
                    buildModeRow()
                    requestBind()
                }
            })
        }
    }

    private fun refreshOptions() {
        optionsRow.removeAllViews()
        val f = facts
        val s = settings
        fun opt(text: String, action: () -> Unit) = optionsRow.addView(chip(text, false, action))
        fun update(rebind: Boolean, change: (VideoSettings) -> VideoSettings) {
            settings = change(settings).also { it.save(this) }
            if (rebind) requestBind(OPTION_BIND_DEBOUNCE_MS) else refreshOptions()
        }
        fun <T> next(list: List<T>, current: T) = list[(list.indexOf(current) + 1).mod(list.size)]

        when (mode) {
            VideoMode.PRO_VIDEO, VideoMode.SHORT_VIDEO -> {
                val qualities = f?.qualities?.map { it.qualityName() }.orEmpty()
                val a = activeAttempt
                if (qualities.size > 1) {
                    opt(getString(R.string.video_opt_quality, a?.qualityName ?: s.qualityName)) {
                        update(true) { it.copy(qualityName = next(qualities, a?.qualityName ?: it.qualityName)) }
                    }
                }
                if (mode == VideoMode.SHORT_VIDEO) {
                    opt(getString(R.string.video_opt_length, s.shortSeconds)) {
                        update(false) { it.copy(shortSeconds = next(listOf(10, 15, 30, 60), it.shortSeconds)) }
                    }
                    opt(getString(if (musicUri == null) R.string.video_opt_music_pick else R.string.video_opt_music_clear)) {
                        if (musicUri == null) {
                            FeatureGuard.attempt(TAG, "music picker") { pickMusic.launch(arrayOf("audio/*")) }
                                ?: toast(R.string.video_modes_failed)
                        } else {
                            musicUri = null
                            refreshOptions()
                        }
                    }
                    if (musicUri != null) {
                        opt(getString(if (s.keepOriginalAudio) R.string.video_opt_orig_on else R.string.video_opt_orig_off)) {
                            update(false) { it.copy(keepOriginalAudio = !it.keepOriginalAudio) }
                        }
                    }
                } else {
                    val fpsList = f?.regularFps.orEmpty().filter { it in setOf(24, 25, 30, 48, 50, 60) }
                    if (fpsList.size > 1) {
                        opt(getString(R.string.video_opt_fps, a?.fps ?: s.fps)) {
                            update(true) { it.copy(fps = next(fpsList, a?.fps ?: it.fps)) }
                        }
                    }
                    if (hevcAvailable()) {
                        opt(if (a?.hevc == true) "HEVC" else "AVC") { update(true) { it.copy(hevc = !(a?.hevc ?: it.hevc)) } }
                    }
                    opt(getString(R.string.video_opt_bitrate, getString(bitrateLabel(s.bitrate)))) {
                        update(true) { it.copy(bitrate = next(BitrateLevel.entries, it.bitrate)) }
                    }
                    val stabs = buildList {
                        add(Stabilization.OFF)
                        if (f?.halStabilization == true) add(Stabilization.HAL)
                        if (caps?.hasGyro == true) add(Stabilization.GYRO)
                    }
                    if (stabs.size > 1) {
                        opt(getString(R.string.video_opt_stab, getString(stabLabel(s.stabilization)))) {
                            update(true) { it.copy(stabilization = next(stabs, it.stabilization)) }
                        }
                    }
                    if (f?.toneCurve == true) {
                        opt(getString(if (a?.log == true) R.string.video_opt_log_on else R.string.video_opt_log_off)) {
                            update(true) { it.copy(log = !(a?.log ?: it.log), hdr = false) }
                        }
                        if (a?.log == true) {
                            opt(getString(if (s.logPreview) R.string.video_opt_lut_preview_on else R.string.video_opt_lut_preview_off)) {
                                update(true) { it.copy(logPreview = !it.logPreview) }
                            }
                            opt(getString(if (s.logBake) R.string.video_opt_log_bake_on else R.string.video_opt_log_bake_off)) {
                                update(false) { it.copy(logBake = !it.logBake) }
                            }
                        }
                    }
                    if (f?.hdrRanges?.isNotEmpty() == true) {
                        opt(getString(if (a?.hdr == true) R.string.video_opt_hdr_on else R.string.video_opt_hdr_off)) {
                            update(true) { it.copy(hdr = !(a?.hdr ?: it.hdr), log = false) }
                        }
                    }
                }
                opt(getString(R.string.video_opt_mic, getString(micLabel(s.mic)))) {
                    update(true) { it.copy(mic = next(MicSource.entries, it.mic)) }
                }
                opt(getString(if (s.stereo) R.string.video_opt_stereo else R.string.video_opt_mono)) {
                    update(true) { it.copy(stereo = !it.stereo) }
                }
            }
            VideoMode.SLOW_MOTION -> when (val plan = slowPlan) {
                is SlowMotionPlan.HighSpeed -> opt(getString(R.string.video_opt_high_speed, plan.fps)) {}
                is SlowMotionPlan.Retimed -> opt(getString(R.string.video_opt_retimed, plan.captureFps, plan.factor)) {}
                SlowMotionPlan.Unsupported -> Unit
            }
            VideoMode.TIME_LAPSE -> opt(getString(R.string.video_opt_interval, s.lapseIntervalMs / 1000f)) {
                update(false) { it.copy(lapseIntervalMs = next(listOf(500L, 1000L, 2000L, 5000L, 10000L), it.lapseIntervalMs)) }
            }
            VideoMode.HYPERLAPSE -> opt(getString(R.string.video_opt_speed, s.hyperlapseSpeed)) {
                update(false) { it.copy(hyperlapseSpeed = next(listOf(4, 8, 16, 32), it.hyperlapseSpeed)) }
            }
            VideoMode.TIME_FREEZE -> opt(getString(R.string.video_opt_length, s.freezeSeconds)) {
                update(false) { it.copy(freezeSeconds = next(listOf(4, 6, 10), it.freezeSeconds)) }
            }
            VideoMode.SLOW_SHUTTER -> {
                opt(getString(if (s.slowShutterLighten) R.string.video_opt_trails else R.string.video_opt_silky)) {
                    update(false) { it.copy(slowShutterLighten = !it.slowShutterLighten) }
                }
                opt(getString(R.string.video_opt_length, s.slowShutterSeconds)) {
                    update(false) { it.copy(slowShutterSeconds = next(listOf(2, 4, 8, 15), it.slowShutterSeconds)) }
                }
            }
            VideoMode.DOCUMENT -> {
                opt(getString(enhanceLabel(s.documentEnhance))) {
                    update(false) { it.copy(documentEnhance = (it.documentEnhance + 1) % 3) }
                }
                if (pages.isNotEmpty()) opt(getString(R.string.video_opt_export_pdf, pages.size)) { exportPdf() }
            }
            VideoMode.DUAL_VIDEO, VideoMode.PANORAMA, VideoMode.BOKEH_VIDEO -> Unit
        }
        updateUi()
    }

    private fun bitrateLabel(b: BitrateLevel) = when (b) {
        BitrateLevel.LOW -> R.string.video_value_low
        BitrateLevel.STANDARD -> R.string.video_value_standard
        BitrateLevel.HIGH -> R.string.video_value_high
    }

    private fun stabLabel(s: Stabilization) = when (s) {
        Stabilization.OFF -> R.string.video_value_off
        Stabilization.HAL -> R.string.video_value_stab_hal
        Stabilization.GYRO -> R.string.video_value_stab_gyro
    }

    private fun micLabel(m: MicSource) = when (m) {
        MicSource.CAMCORDER -> R.string.video_value_mic_camcorder
        MicSource.MIC -> R.string.video_value_mic_default
        MicSource.UNPROCESSED -> R.string.video_value_mic_raw
        MicSource.VOICE -> R.string.video_value_mic_voice
    }

    private fun enhanceLabel(i: Int) = when (i) {
        1 -> R.string.video_opt_doc_gray
        2 -> R.string.video_opt_doc_bw
        else -> R.string.video_opt_doc_color
    }

    private fun buildLayout(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        previewView = PreviewView(this).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
        root.addView(previewView, FrameLayout.LayoutParams(MATCH, MATCH))
        textureView = TextureView(this).apply { visibility = View.GONE }
        root.addView(textureView, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))
        overlay = GuideOverlayView(this)
        root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(36), dp(8), dp(8))
        }
        top.addView(chip(getString(R.string.video_modes_close), false) { finish() })
        optionsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(
            HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(optionsRow) },
            LinearLayout.LayoutParams(0, WRAP, 1f),
        )
        root.addView(top, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(28))
            setBackgroundColor(0x66000000)
        }
        statusText = TextView(this).apply { setTextColor(Color.WHITE); gravity = Gravity.CENTER; textSize = 15f }
        bottom.addView(statusText, LinearLayout.LayoutParams(MATCH, WRAP))
        modesRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bottom.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(modesRow) })
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        }
        flipButton = chip(getString(R.string.video_modes_flip), false) {
            if (busy || recording != null || session != null) return@chip
            back = !back
            lifecycleScope.launch { setupCamera() }
        }
        shutter = Button(this).apply {
            setOnClickListener { onShutter() }
            minWidth = dp(120)
        }
        pauseButton = chip(getString(R.string.video_modes_pause), false) {
            val r = recording ?: return@chip
            FeatureGuard.attempt(TAG, "pause/resume") {
                if (pauseButton.tag == true) {
                    r.resume(); pauseButton.tag = false; pauseButton.setText(R.string.video_modes_pause)
                } else {
                    r.pause(); pauseButton.tag = true; pauseButton.setText(R.string.video_modes_resume)
                }
            }
        }
        controls.addView(flipButton)
        controls.addView(shutter, LinearLayout.LayoutParams(WRAP, dp(64)).apply { marginStart = dp(16); marginEnd = dp(16) })
        controls.addView(pauseButton)
        bottom.addView(controls, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(bottom, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        return root
    }

    private fun chip(text: String, selected: Boolean, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 13f
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(12), dp(6), dp(12), dp(6))
        setTextColor(if (selected) Color.BLACK else Color.WHITE)
        background = pill(if (selected) Color.WHITE else 0x55FFFFFF, 18f)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(6) }
    }

    private fun pill(color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp * resources.displayMetrics.density
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun toast(res: Int) = toast(getString(res))
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
}
