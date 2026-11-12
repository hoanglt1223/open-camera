/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.viewmodels

import android.os.SystemClock
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import android.graphics.ImageFormat
import dev.opencamera.app.CameraActivity
import dev.opencamera.app.R
import androidx.camera.core.ImageProxy
import android.location.LocationManager
import android.net.Uri
import dev.opencamera.vendorbridge.OemFeature
import kotlinx.coroutines.withContext
import dev.opencamera.app.ext.toExtensionMode
import dev.opencamera.app.ext.oemFeatureOf
import dev.opencamera.app.ext.oemFeature
import dev.opencamera.vendorbridge.VendorTagRegistry
import dev.opencamera.vendorbridge.ProcessingSource
import dev.opencamera.vendorbridge.ExtensionsProbe
import dev.opencamera.profiles.ProfileSource
import dev.opencamera.app.robustness.FeatureGuard
import androidx.exifinterface.media.ExifInterface
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.view.OrientationEventListener
import androidx.camera.core.AspectRatio
import androidx.camera.core.ExperimentalZeroShutterLag
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.extensions.ExtensionMode
import androidx.camera.video.Quality
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.video.AudioConfig
import androidx.core.animation.addListener
import androidx.core.content.ContextCompat
import androidx.core.location.LocationRequestCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import dev.opencamera.app.ApertureApplication
import dev.opencamera.profiles.LensChip
import dev.opencamera.profiles.LensPlan
import dev.opencamera.app.ext.applicationContext
import dev.opencamera.app.ext.broadcastReceiverFlow
import dev.opencamera.app.ext.flashMode
import dev.opencamera.app.ext.locationFlow
import dev.opencamera.app.ext.mapToRange
import dev.opencamera.app.ext.next
import dev.opencamera.app.ext.nextPowerOfTwo
import dev.opencamera.app.ext.previous
import dev.opencamera.app.ext.previousPowerOfTwo
import dev.opencamera.app.ext.thermalStatusFlow
import dev.opencamera.app.models.Camera
import dev.opencamera.app.models.CameraConfiguration
import dev.opencamera.app.models.CameraFacing
import dev.opencamera.app.models.CameraMode
import dev.opencamera.app.models.CameraState
import dev.opencamera.app.models.ColorCorrectionAberrationMode
import dev.opencamera.app.models.DistortionCorrectionMode
import dev.opencamera.app.models.EdgeMode
import dev.opencamera.app.models.Event
import dev.opencamera.app.models.FlashMode
import dev.opencamera.app.models.GridMode
import dev.opencamera.app.models.HardwareKey
import dev.opencamera.app.models.HotPixelMode
import dev.opencamera.app.models.IslandItem
import dev.opencamera.app.models.NoiseReductionMode
import dev.opencamera.app.models.PhotoOutputFormat
import dev.opencamera.app.models.Rotation
import dev.opencamera.app.models.ShadingMode
import dev.opencamera.app.models.ThermalStatus
import dev.opencamera.app.models.TimerMode
import dev.opencamera.app.multiframe.ManualStill
import dev.opencamera.app.multiframe.MultiFrameCaptureController
import dev.opencamera.app.processing.BurstJobQueue
import dev.opencamera.app.modes.CreativeCaptureController
import dev.opencamera.app.modes.CreativeMode
import dev.opencamera.app.modes.MotionPhotoController
import dev.opencamera.app.processing.LookController
import dev.opencamera.app.qr.QrImageAnalyzer
import dev.opencamera.app.repositories.CameraRepository
import dev.opencamera.app.utils.CameraSoundsUtils
import dev.opencamera.app.utils.StorageUtils
import dev.opencamera.capture.BurstKind
import dev.opencamera.capture.BurstPlan
import dev.opencamera.capture.StreamConfig
import dev.opencamera.capture.StreamPolicy
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.safeCast

/**
 * [ViewModel] representing a camera session. This data is used to receive
 * live data regarding the setting currently enabled.
 */
class CameraViewModel(application: Application) : ApertureViewModel(application) {
    // System services
    private val locationManager = applicationContext.getSystemService(LocationManager::class.java)
    private val powerManager = applicationContext.getSystemService(PowerManager::class.java)

    // Camera sounds
    val cameraSoundsUtils = CameraSoundsUtils(preferencesRepository)

    // Orientation
    private val orientation = callbackFlow {
        val orientationEventListener = object : OrientationEventListener(applicationContext) {
            override fun onOrientationChanged(orientation: Int) {
                trySend(orientation)
            }
        }

        orientationEventListener.enable()

        awaitClose {
            orientationEventListener.disable()
        }
    }
        .flowOn(Dispatchers.IO)
        .shareIn(
            viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            replay = 1
        )

    /**
     * [ExecutorService] for camera related operations.
     */
    val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    /**
     * CameraX's [LifecycleCameraController].
     */
    val cameraController = LifecycleCameraController(applicationContext)

    /**
     * Preview look effect and background rendering of captured photos.
     */
    val lookController = LookController(applicationContext, cameraController, viewModelScope)

    /**
     * Mutex used to rebind the camera.
     */
    val rebindMutex = Mutex()

    /**
     * Mutex used to zoom the camera.
     */
    private var zoomGestureMutex = Mutex()

    /**
     * QR image analyzer.
     */
    val qrImageAnalyzer = QrImageAnalyzer(applicationContext, viewModelScope)

    val multiFrame = MultiFrameCaptureController(applicationContext, BurstJobQueue.get(applicationContext))

    /** Moon, long exposure, light trails, passer-by removal, focus stacking, sports burst. */
    val creative = CreativeCaptureController(applicationContext, multiFrame, BurstJobQueue.get(applicationContext))

    /** Motion photo and pre-shot buffer, fed from the burst analysis stream. */
    val motionPhoto = MotionPhotoController(applicationContext)

    /** True when the analysis stream is needed by multi-frame, creative or motion features. */
    val wantsAnalysisStream: Boolean
        get() = multiFrame.enabled || creative.mode.value != CreativeMode.OFF || motionPhoto.active

    /**
     * Session layout for a photo configuration: full-resolution YUV (or RAW) without the JPEG
     * stream while a multi-frame or creative mode is active, else the regular JPEG session.
     * Limited by the fallback cap after a failure.
     */
    fun desiredStreamConfig(cfg: CameraConfiguration.Photo): StreamConfig = multiFrame.capped(
        StreamPolicy.choose(
            StreamPolicy.Inputs(
                setting = multiFrame.setting.value,
                creativeActive = creative.mode.value != CreativeMode.OFF,
                jpegOutput = cfg.photoOutputFormat == PhotoOutputFormat.JPEG,
                // The phone's processing chosen by the vendor bridge also needs the JPEG session.
                noExtension = resolveExtensionMode(cfg) == ExtensionMode.NONE,
                backCamera = cfg.camera.cameraFacing != CameraFacing.FRONT,
                singleCaptureIntent = inSingleCaptureMode.value,
                // Auto flash never fired during bursts either; only a forced flash needs the JPEG path.
                flashOff = flashMode.value != FlashMode.ON && flashMode.value != FlashMode.SCREEN,
                rawCapable = PhotoOutputFormat.RAW in cfg.camera.supportedPhotoOutputFormats,
                source = multiFrame.source,
                // A RAW session cannot carry a CameraX look effect; our own preview renderer can.
                rawLarger = (lookController.ownsPreview || !lookController.hasLook) &&
                    rawLargerThanYuv(cfg.camera.cameraId),
            )
        )
    )

    private val rawLargerCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /** RAW_SENSOR has at least 20% more pixels than the largest YUV output (e.g. 12 MP vs 8 MP). */
    private fun rawLargerThanYuv(cameraId: String): Boolean = rawLargerCache.getOrPut(cameraId) {
        FeatureGuard.attempt(LOG_TAG, "RAW vs YUV size") {
            val map = cameraManager.getCameraCharacteristics(cameraId)
                .get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val px = { f: Int -> map?.getOutputSizes(f)?.maxOfOrNull { it.width.toLong() * it.height } ?: 0L }
            val raw = px(ImageFormat.RAW_SENSOR)
            val yuv = px(ImageFormat.YUV_420_888)
            Log.i(LOG_TAG, "Camera $cameraId RAW $raw px, YUV $yuv px")
            raw > yuv * 6 / 5
        } ?: false
    }

    val vendorBridge = getApplication<ApertureApplication>().vendorBridge

    private val cameraManager by lazy { applicationContext.getSystemService(CameraManager::class.java) }


    /** "OEM Night" while the bound session uses the phone's processing, else null. */
    @Volatile
    var oemProcessingLabel: String? = null

    /**
     * Do not emit here directly, use [emitEvent].
     */
    private val _event = MutableSharedFlow<Event>()

    /**
     * Events.
     */
    val event = _event.asSharedFlow()

    /**
     * The initial camera mode.
     */
    var initialCameraMode = preferencesRepository.lastCameraMode.value

    /**
     * The initial camera facing.
     */
    var initialCameraFacing = preferencesRepository.lastCameraFacing.value

    /**
     * Whether the current session is in single capture mode.
     */
    val inSingleCaptureMode = MutableStateFlow(false)

    private val _cameraConfiguration = MutableStateFlow<CameraConfiguration?>(null)
    val cameraConfiguration = _cameraConfiguration
        .filterNotNull()
        .shareIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            replay = 1,
        )

    /**
     * The current camera.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val camera = cameraConfiguration
        .mapLatest { cameraConfiguration -> cameraConfiguration.camera }
        .flowOn(Dispatchers.IO)
        .shareIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            replay = 1,
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    val cameraFacing = camera
        .mapLatest { camera -> camera.cameraFacing }
        .flowOn(Dispatchers.IO)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = preferencesRepository.lastCameraFacing.value,
        )

    /**
     * The current camera mode.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val cameraMode = cameraConfiguration
        .mapLatest { cameraConfiguration -> cameraConfiguration.cameraMode }
        .flowOn(Dispatchers.IO)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = preferencesRepository.lastCameraMode.value,
        )

    /**
     * The list of [Camera]s to use for cycling.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val camerasForCycling = combine(
        cameraRepository.cameras,
        cameraMode,
    ) { cameras, cameraMode ->
        cameras.filter { it.supportsCameraMode(cameraMode) }.groupBy(Camera::cameraFacing)
    }
        .flowOn(Dispatchers.IO)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = mapOf(),
        )

    /**
     * Flow used for camera mode animations.
     */
    val cameraModeTransition = cameraMode
        .runningFold(null as CameraMode? to cameraMode.value) { acc, value ->
            acc.second to value
        }
        .flowOn(Dispatchers.IO)
        .shareIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(),
        )

    /**
     * Pair of active camera to the available cameras.
     */
    val lenses = combine(
        cameraRepository.cameras,
        camera,
        cameraMode,
    ) { cameras, camera, cameraMode ->
        camera to cameras.filter {
            it.cameraFacing == camera.cameraFacing && it.supportsCameraMode(cameraMode)
        }
    }
        .flowOn(Dispatchers.IO)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = null to listOf(),
        )

    private val deviceProfileRepository =
        getApplication<ApertureApplication>().deviceProfileRepository

    /**
     * Zoom selector chips from the device profile for the active camera's facing, each with the
     * CameraX camera it opens. Empty when the profile has no usable stops (front camera,
     * profiles turned off, 1x not openable): the UI then shows the plain camera list.
     */
    val lensChips = combine(lenses, deviceProfileRepository.resolved) { (camera, available), profile ->
        if (camera == null || profile == null || camera.cameraFacing != CameraFacing.BACK) {
            return@combine emptyList()
        }
        val byId = available.associateBy { it.cameraId }
        LensPlan.from(profile).chips(byId.keys).map { it to byId.getValue(it.cameraId) }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = listOf<Pair<LensChip, Camera>>(),
        )

    private var lensChipZoomJob: Job? = null

    /** Longest physical telephoto chip (a different lens than 1x, at 2x or more), if any. */
    private fun moonTelephoto(chips: List<Pair<LensChip, Camera>>): Pair<LensChip, Camera>? {
        val main = chips.firstOrNull { it.first.ratio == 1f }?.first ?: return null
        return chips.filter { it.first.ratio >= 2f && it.first.lensId != main.lensId }.maxByOrNull { it.first.ratio }
    }

    /** Zoom ratio on the open logical multi-camera that shows [chip]'s lens, if it has one. */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun logicalZoomRatio(chip: LensChip): Float? = runCatching {
        val info = cameraController.cameraInfo ?: return null
        val zoom = zoomState.value ?: return null
        val physical = info.physicalCameraInfos.map { Camera2CameraInfo.from(it).cameraId }.toSet()
        dev.opencamera.profiles.LensPlan.logicalZoom(chip, physical, zoom.minZoomRatio, zoom.maxZoomRatio)
    }.getOrNull()

    /**
     * Opens the chip's camera if needed, then applies the chip's zoom ratio once that camera
     * is bound. A lens inside the open logical multi-camera is reached by zooming instead.
     */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    fun selectLensChip(chip: LensChip, camera: Camera) {
        lensChipZoomJob?.cancel()
        if (_cameraConfiguration.value?.camera == camera) {
            smoothZoom(chip.zoomRatio)
            return
        }
        logicalZoomRatio(chip)?.let {
            smoothZoom(it)
            return
        }
        if (!selectCamera(camera)) return
        if (chip.zoomRatio == 1f) return
        lensChipZoomJob = viewModelScope.launch {
            withTimeoutOrNull(LENS_CHIP_ZOOM_TIMEOUT_MS) {
                zoomState.filterNotNull().first {
                    cameraController.cameraInfo?.let { info ->
                        Camera2CameraInfo.from(info).cameraId
                    } == camera.cameraId
                }
            }?.let { cameraController.setZoomRatio(chip.zoomRatio.coerceIn(it.minZoomRatio, it.maxZoomRatio)) }
        }
    }

    /**
     * CameraX's state of the current camera.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val cameraXCameraState = camera
        .flatMapLatest { camera ->
            camera.cameraXCameraState
        }
        .flowOn(Dispatchers.IO)
        .shareIn(
            viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            replay = 1
        )

    /**
     * Current camera state.
     */
    val cameraState = MutableStateFlow(CameraState.IDLE)

    /**
     * Current screen rotation.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val screenRotation = orientation
        .filter { it != OrientationEventListener.ORIENTATION_UNKNOWN }
        .mapLatest { orientation ->
            Rotation.fromDegreesInAperture(orientation)
        }
        .flowOn(Dispatchers.IO)
        .stateIn(
            viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = Rotation.ROTATION_0
        )

    /**
     * Captured media [Uri]s
     */
    val capturedMedia = mediaRepository.capturedMedia()
        .flowOn(Dispatchers.IO)
        .stateIn(
            viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = listOf(),
        )

    /**
     * The current list of supported [FlashMode]s.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val supportedFlashModes = cameraConfiguration
        .mapLatest { cameraConfiguration ->
            cameraConfiguration.cameraMode.supportedFlashModes.intersect(
                cameraConfiguration.camera.supportedFlashModes
            )
        }
        .flowOn(Dispatchers.IO)
        .stateIn(
            viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = setOf()
        )

    /**
     * Whether force torch mode should be enabled.
     */
    private val forceTorch = MutableStateFlow(false)

    /**
     * Whether QR torch mode should be enabled.
     */
    private val qrFlashMode = MutableStateFlow(FlashMode.OFF)

    /**
     * The user selected flash mode.
     */
    private val wantedFlashMode = combine(
        cameraConfiguration,
        preferencesRepository.photoFlashMode,
        preferencesRepository.videoFlashMode,
        qrFlashMode,
    ) { cameraConfiguration, photoFlashMode, videoFlashMode, qrFlashMode ->
        when (cameraConfiguration.cameraMode) {
            CameraMode.PHOTO -> photoFlashMode
            CameraMode.VIDEO -> videoFlashMode
            CameraMode.QR -> qrFlashMode
        }
    }
        .flowOn(Dispatchers.IO)
        .shareIn(
            viewModelScope,
            started = SharingStarted.Eagerly,
            replay = 1
        )

    /**
     * The flash mode that will actually be used.
     */
    val flashMode = combine(
        supportedFlashModes,
        wantedFlashMode,
        forceTorch,
        cameraConfiguration,
    ) { supportedFlashModes, wantedFlashMode, forceTorch, cameraConfiguration ->
        val flashMode = wantedFlashMode.takeIf { it in supportedFlashModes } ?: FlashMode.OFF

        val shouldForceTorch = forceTorch
                && cameraConfiguration.cameraMode == CameraMode.PHOTO
                && FlashMode.TORCH in cameraConfiguration.camera.supportedFlashModes

        when (shouldForceTorch) {
            true -> FlashMode.TORCH
            false -> flashMode
        }
    }
        .flowOn(Dispatchers.IO)
        .stateIn(
            viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = FlashMode.OFF
        )

    /**
     * Whether the user should be able to cycle between different flash modes.
     */
    val isFlashButtonEnabled = combine(
        cameraState,
        cameraMode,
    ) { cameraState, cameraMode ->
        cameraState == CameraState.IDLE || cameraMode == CameraMode.VIDEO
    }
        .flowOn(Dispatchers.IO)
        .stateIn(
            viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = false
        )

    /**
     * Grid mode.
     */
    val gridMode = combine(
        preferencesRepository.lastGridMode,
        cameraConfiguration,
    ) { gridMode, cameraConfiguration ->
        gridMode.takeIf { cameraConfiguration.cameraMode != CameraMode.QR } ?: GridMode.OFF
    }
        .flowOn(Dispatchers.IO)
        .stateIn(
            viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = GridMode.OFF
        )

    /**
     * Timer mode.
     */
    val timerMode = preferencesRepository.timerMode
        .stateIn(
            viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = TimerMode.OFF
        )

    /**
     * Whether the leveler is enabled.
     */
    val levelerEnabled = preferencesRepository.leveler
        .stateIn(
            viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = false
        )

    /**
     * Whether screen brightness should be forced to full.
     */
    val fullScreenBrightness = preferencesRepository.brightScreen
        .stateIn(
            viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = false
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        powerManager.thermalStatusFlow().mapLatest { thermalStatus ->
            when (thermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> ThermalStatus.NONE
                PowerManager.THERMAL_STATUS_LIGHT -> ThermalStatus.LIGHT
                PowerManager.THERMAL_STATUS_MODERATE -> ThermalStatus.MODERATE
                PowerManager.THERMAL_STATUS_SEVERE -> ThermalStatus.SEVERE
                PowerManager.THERMAL_STATUS_CRITICAL -> ThermalStatus.CRITICAL
                PowerManager.THERMAL_STATUS_EMERGENCY -> ThermalStatus.EMERGENCY
                PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalStatus.SHUTDOWN
                else -> error("Unknown thermal status: $thermalStatus")
            }
        }
    } else {
        flowOf(ThermalStatus.NONE)
    }
        .flowOn(Dispatchers.IO)
        .stateIn(
            viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = ThermalStatus.NONE,
        )

    /**
     * The current zoom state.
     */
    val zoomState = cameraController.zoomState.asFlow()
        .flowOn(Dispatchers.IO)
        .stateIn(
            viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = null
        )

    /**
     * The profile lens behind the current camera and zoom (null without profile chips). Pro
     * settings are keyed by its [LensChip.lensId], so logical-zoom stops keep separate settings.
     */
    val currentLensChip = combine(lensChips, camera, zoomState) { chips, camera, zoom ->
        val ratio = zoom?.zoomRatio ?: 1f
        val onCamera = chips.map { it.first }.filter { it.cameraId == camera.cameraId }
        onCamera.sortedBy { it.zoomRatio }.lastOrNull { it.zoomRatio <= ratio + 1e-3f }
            ?: onCamera.firstOrNull()
    }
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = null,
        )

    val tapToFocusInfoState = cameraController.tapToFocusInfoState.asFlow()
        .flowOn(Dispatchers.IO)
        .shareIn(
            viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            replay = 1
        )

    @Suppress("MissingPermission")
    val location = locationManager.locationFlow(
        LocationRequestCompat.Builder(1000)
            .setMinUpdateDistanceMeters(1f)
            .setQuality(LocationRequestCompat.QUALITY_BALANCED_POWER_ACCURACY)
            .build()
    )
        .flowOn(Dispatchers.IO)
        .stateIn(
            viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = null
        )

    val saveLocation = combine(
        preferencesRepository.saveLocation,
        inSingleCaptureMode,
    ) { saveLocation, inSingleCaptureMode ->
        when (inSingleCaptureMode) {
            true -> false
            false -> saveLocation
        }
    }
        .flowOn(Dispatchers.IO)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = preferencesRepository.saveLocation,
        )

    private val batteryIntent = applicationContext.broadcastReceiverFlow(
        IntentFilter(Intent.ACTION_BATTERY_CHANGED)
    )
        .onStart { emit(null) }
        .flowOn(Dispatchers.IO)
        .stateIn(
            viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = null
        )

    /**
     * The current, exposure compensation level, from 0 to 1
     */
    private val exposureCompensationLevel = MutableStateFlow(0.5f)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val exposureCompensationRange = cameraConfiguration
        .mapLatest { it.camera.exposureCompensationRange }
        .flowOn(Dispatchers.IO)
        .shareIn(
            viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            replay = 1
        )

    val exposureCompensationRangeToLevel = combine(
        exposureCompensationLevel,
        exposureCompensationRange,
    ) { exposureCompensationLevel, exposureCompensationRange ->
        exposureCompensationRange to exposureCompensationLevel
    }
        .flowOn(Dispatchers.IO)
        .shareIn(
            viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            replay = 1
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val exposureCompensationIndex = exposureCompensationRangeToLevel
        .mapLatest { (exposureCompensationLevel, exposureCompensationRange) ->
            Int.mapToRange(exposureCompensationLevel, exposureCompensationRange)
        }
        .flowOn(Dispatchers.IO)
        .shareIn(
            viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            replay = 1
        )

    val isShutterButtonEnabled = combine(
        cameraMode,
        cameraState,
        multiFrame.cancellable,
    ) { cameraMode, cameraState, cancellable ->
        when (cameraState) {
            CameraState.IDLE -> true
            CameraState.COUNTDOWN -> cameraMode == CameraMode.VIDEO
            CameraState.TAKING_PHOTO -> cancellable
            CameraState.PRE_RECORDING_VIDEO -> false
            CameraState.RECORDING_VIDEO -> true
            CameraState.RECORDING_VIDEO_PAUSED -> true
        }
    }
        .flowOn(Dispatchers.IO)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = false
        )

    // Photo

    /**
     * Photo capture mode.
     * @see ImageCapture.CaptureMode
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val photoCaptureMode = cameraConfiguration
        .mapLatest { cameraConfiguration ->
            when (cameraConfiguration) {
                is CameraConfiguration.Photo -> cameraConfiguration.photoCaptureMode
                else -> preferencesRepository.photoCaptureMode.value
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = preferencesRepository.photoCaptureMode.value,
        )

    /**
     * Photo aspect ratio.
     * @see AspectRatio.Ratio
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val photoAspectRatio = cameraConfiguration
        .mapLatest { cameraConfiguration ->
            when (cameraConfiguration) {
                is CameraConfiguration.Photo -> cameraConfiguration.photoAspectRatio
                else -> preferencesRepository.photoAspectRatio.value
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = preferencesRepository.photoAspectRatio.value,
        )

    /**
     * Photo effect.
     * @see ExtensionMode.Mode
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val photoEffect = cameraConfiguration
        .mapLatest { cameraConfiguration ->
            cameraConfiguration.extensionMode
        }
        .flowOn(Dispatchers.IO)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = preferencesRepository.photoEffect.value,
        )

    /**
     * Whether the user should be able to select a photo effect.
     */
    @androidx.annotation.OptIn(ExperimentalZeroShutterLag::class)
    val isPhotoEffectButtonVisible = combine(
        cameraConfiguration,
        cameraMode,
        photoCaptureMode,
    ) { cameraConfiguration, cameraMode, photoCaptureMode ->
        cameraMode == CameraMode.PHOTO &&
                photoCaptureMode != ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG &&
                cameraConfiguration.camera.supportedExtensionModes.size > 1
    }

    // Video

    /**
     * Video quality.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val videoQuality = cameraConfiguration
        .mapLatest { cameraConfiguration ->
            when (cameraConfiguration) {
                is CameraConfiguration.Video -> cameraConfiguration.videoQuality
                else -> preferencesRepository.videoQuality.value
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = preferencesRepository.videoQuality.value,
        )

    val isVideoQualityButtonEnabled = combine(
        camera,
        cameraState,
    ) { camera, cameraState ->
        cameraState == CameraState.IDLE && camera.supportedVideoQualities.size > 1
    }
        .flowOn(Dispatchers.IO)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = false,
        )

    /**
     * Video frame rate.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val videoFrameRate = cameraConfiguration
        .mapLatest { cameraConfiguration ->
            when (cameraConfiguration) {
                is CameraConfiguration.Video -> cameraConfiguration.videoFrameRate
                else -> preferencesRepository.videoFrameRate.value
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = preferencesRepository.videoFrameRate.value,
        )

    val isVideoFrameRateButtonEnabled = combine(
        cameraConfiguration,
        cameraState,
    ) { cameraConfiguration, cameraState ->
        cameraState == CameraState.IDLE && when (cameraConfiguration) {
            is CameraConfiguration.Video -> {
                val videoQualityInfo = cameraConfiguration.camera.supportedVideoQualities[
                    cameraConfiguration.videoQuality
                ] ?: error("Video quality not supported")

                videoQualityInfo.supportedFrameRates.size > 1
            }

            else -> false
        }
    }
        .flowOn(Dispatchers.IO)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = false,
        )

    /**
     * Video dynamic range.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val videoDynamicRange = cameraConfiguration
        .mapLatest { cameraConfiguration ->
            when (cameraConfiguration) {
                is CameraConfiguration.Video -> cameraConfiguration.videoDynamicRange
                else -> preferencesRepository.videoDynamicRange.value
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = preferencesRepository.videoDynamicRange.value,
        )

    val isVideoDynamicRangeButtonEnabled = combine(
        cameraConfiguration,
        cameraState,
    ) { cameraConfiguration, cameraState ->
        cameraState == CameraState.IDLE && when (cameraConfiguration) {
            is CameraConfiguration.Video -> {
                val videoQualityInfo = cameraConfiguration.camera.supportedVideoQualities[
                    cameraConfiguration.videoQuality
                ] ?: error("Video quality not supported")

                videoQualityInfo.supportedDynamicRanges.size > 1
            }

            else -> false
        }
    }

    /**
     * Video mic mode.
     */
    val videoMicMode = MutableStateFlow(preferencesRepository.videoMicMode.value)

    /**
     * Video [AudioConfig].
     */
    var videoAudioConfig: AudioConfig = AudioConfig.AUDIO_DISABLED

    /**
     * Video [Recording].
     */
    val videoRecording = MutableStateFlow<Recording?>(null)

    val isVideoMicButtonEnabled = combine(
        cameraState,
        videoRecording,
    ) { cameraState, videoRecording ->
        cameraState == CameraState.IDLE || videoRecording?.isAudioSourceConfigured == true
    }

    /**
     * Video recording duration.
     */
    val videoRecordingDuration = MutableStateFlow(0L)

    /**
     * Video record event.
     */
    val videoRecordEvent = MutableSharedFlow<VideoRecordEvent>()

    // QR

    /**
     * QR result.
     */
    val qrResult = qrImageAnalyzer.qrResult
        .shareIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(),
        )

    /**
     * Whether the camera can be flipped.
     */
    val canFlipCamera = combine(
        camerasForCycling,
        cameraState,
    ) { camerasForCycling, cameraState ->
        camerasForCycling.keys.size > 1 && !cameraState.isRecordingVideo
    }
        .flowOn(Dispatchers.IO)
        .stateIn(
            viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = false
        )

    /**
     * Island items.
     */
    val islandItems = combine(
        cameraConfiguration,
        videoMicMode,
        batteryIntent,
        thermalStatus,
    ) { cameraConfiguration, videoMicMode, batteryIntent, thermalStatus ->
        buildList {
            when (thermalStatus) {
                ThermalStatus.NONE,
                ThermalStatus.LIGHT -> Unit

                ThermalStatus.MODERATE,
                ThermalStatus.SEVERE -> add(
                    IslandItem.ThermalThrottling(false)
                )

                ThermalStatus.CRITICAL,
                ThermalStatus.EMERGENCY,
                ThermalStatus.SHUTDOWN -> add(
                    IslandItem.ThermalThrottling(true)
                )
            }

            batteryIntent?.let {
                val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)

                val percentage = level.times(100).div(scale)

                if (percentage <= 15) {
                    add(
                        IslandItem.LowBattery(
                            isCharging = it.getIntExtra(
                                BatteryManager.EXTRA_PLUGGED,
                                0,
                            ) != 0,
                        )
                    )
                }

                when (cameraConfiguration) {
                    is CameraConfiguration.Photo -> {
                        when (cameraConfiguration.photoOutputFormat) {
                            PhotoOutputFormat.JPEG -> {}

                            PhotoOutputFormat.JPEG_ULTRA_HDR -> add(
                                IslandItem.PhotoJpegUltraHdr
                            )

                            PhotoOutputFormat.RAW -> add(
                                IslandItem.PhotoRawEnabled(false)
                            )

                            PhotoOutputFormat.RAW_JPEG -> add(
                                IslandItem.PhotoRawEnabled(true)
                            )
                        }
                    }

                    is CameraConfiguration.Video -> {
                        if (!videoMicMode) {
                            add(IslandItem.VideoMicMuted)
                        }
                    }

                    is CameraConfiguration.Qr -> {}
                }
            }
        }
    }
        .flowOn(Dispatchers.IO)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = listOf(),
        )

    init {
        // LUT and GL set-up start now so the first bind can carry the look effect.
        lookController.preload()

        multiFrame.analyzer.frameTap = { image ->
            creative.tap(image)
            motionPhoto.tap(image)
        }

        viewModelScope.launch {
            // CameraController state is main-thread only; the analysis thread reads this copy.
            launch {
                while (true) {
                    creative.zoomRatio = cameraController.zoomState.value?.zoomRatio ?: 1f
                    delay(500)
                }
            }

            launch(Dispatchers.IO) {
                FeatureGuard.attempt(LOG_TAG, "Vendor bridge probe") {
                    val cameras = cameraRepository.cameras.first()
                    vendorBridge.dumpOnce(cameraManager, cameras.associate { camera ->
                        camera.cameraId to camera.supportedExtensionModes
                            .mapNotNull(::oemFeatureOf).toSet()
                    })
                }
            }

            launch {
                flashMode.collectLatest { flashMode ->
                    cameraController.flashMode = flashMode
                }
            }

            launch {
                // Moon: a telephoto lens from the device profile beats crop + Super Res.
                combine(creative.mode, lensChips) { mode, chips -> mode to chips }.collectLatest { (mode, chips) ->
                    val tele = if (mode == CreativeMode.MOON) moonTelephoto(chips) else null
                    creative.moonOnTele = tele != null
                    if (tele != null && currentLensChip.value?.lensId != tele.first.lensId) {
                        selectLensChip(tele.first, tele.second)
                    }
                }
            }

            launch {
                exposureCompensationIndex.collectLatest { exposureCompensationIndex ->
                    cameraController.cameraControl?.setExposureCompensationIndex(
                        exposureCompensationIndex
                    )
                }
            }

            launch {
                // Track all user preferences that requires a rebind
                combine<Any?, _>(
                    preferencesRepository.photoCaptureMode,
                    preferencesRepository.enableZsl,
                    preferencesRepository.usePhotoJpegUltraHdr,
                    preferencesRepository.enableRawImageCapture,
                    preferencesRepository.disableJpegWithRaw,
                    preferencesRepository.videoStabilization,
                    preferencesRepository.videoMirrorMode,
                    preferencesRepository.edgeMode,
                    preferencesRepository.noiseReductionMode,
                    preferencesRepository.shadingMode,
                    preferencesRepository.colorCorrectionAberrationMode,
                    preferencesRepository.distortionCorrectionMode,
                    preferencesRepository.hotPixelMode,
                ) { }.drop(1).collectLatest {
                    updateConfiguration<CameraConfiguration> { cameraConfiguration ->
                        createInitialCameraConfiguration(
                            camera = cameraConfiguration.camera,
                            cameraMode = cameraConfiguration.cameraMode,
                        )
                    }
                }
            }
        }
    }

    override fun onCleared() {
        cameraController.unbind()
        lookController.release()

        cameraExecutor.shutdown()

        multiFrame.release()

        cameraSoundsUtils.release()
    }

    /**
     * Initialize [cameraConfiguration].
     * [initialCameraMode] and [initialCameraFacing] must be initialized.
     */
    suspend fun initializeCameraConfiguration(): Boolean {
        val cameraMode = initialCameraMode
        val cameraFacing = initialCameraFacing

        val camera = getSuitableCamera(
            cameraMode = cameraMode,
            cameraFacing = cameraFacing,
        ) ?: return false

        val cameraConfiguration = createInitialCameraConfiguration(
            camera = camera,
            cameraMode = cameraMode,
        )

        _cameraConfiguration.value = cameraConfiguration

        return true
    }

    /**
     * @see CameraRepository.getExtensionEnabledCameraSelector
     */
    fun getExtensionEnabledCameraSelector(
        camera: Camera,
        mode: Int,
    ) = cameraRepository.getExtensionEnabledCameraSelector(camera, mode)

    /**
     * Get whether we can restart the camera.
     */
    fun canRestartCamera() = cameraState.value == CameraState.IDLE

    /**
     * Select another camera.
     *
     * @param camera The new camera
     */
    fun selectCamera(
        camera: Camera,
    ): Boolean = updateConfiguration<CameraConfiguration>(retry = { selectCamera(camera); Unit }) { cameraConfiguration ->
        createInitialCameraConfiguration(
            camera = camera,
            cameraMode = cameraConfiguration.cameraMode,
        )
    }

    /** Stops a running Camera2 burst (shutter tap, activity stopped); the camera is bound again. */
    fun cancelBurst() {
        if (multiFrame.cancellable.value) burstJob?.cancel()
    }

    private var burstJob: Job? = null

    fun takePhoto(allowMultiFrame: Boolean = true) {
        val tapMs = SystemClock.uptimeMillis()
        if (multiFrame.cancellable.value) {
            cancelBurst()
            return
        }
        // Bail out if a photo is already being taken
        if (cameraState.value == CameraState.TAKING_PHOTO) {
            Log.i(LOG_TAG, "Shutter ignored: a photo is still being taken")
            return
        }

        if (allowMultiFrame && takeCreativePhoto()) {
            return
        }

        // No JPEG stream is bound in the full-resolution session: shots come from the app path.
        if (multiFrame.fullRes) {
            takeFullResPhoto(allowMultiFrame)
            return
        }

        if (allowMultiFrame && takeMultiFramePhoto()) {
            return
        }

        cameraState.value = CameraState.TAKING_PHOTO

        // Motion photo / pre-shot: the frames up to now are the "before" part.
        val motionShutterTs = motionPhoto.shutterTimestamp()
        val wantsMotion = !inSingleCaptureMode.value && motionPhoto.motionPhoto.value &&
                cameraController.imageOutputFormat == ImageCapture.OUTPUT_FORMAT_JPEG
        if (!inSingleCaptureMode.value && motionPhoto.preShot.value) {
            viewModelScope.launch {
                motionPhoto.savePreShot(System.currentTimeMillis()).forEach { mediaRepository.broadcastNewPicture(it) }
            }
        }

        val photoOutputStream = if (inSingleCaptureMode.value) {
            ByteArrayOutputStream(SINGLE_CAPTURE_PHOTO_BUFFER_INITIAL_SIZE_BYTES)
        } else {
            null
        }

        val imageOutputFormat = cameraController.imageOutputFormat

        val mimeType = when (imageOutputFormat) {
            ImageCapture.OUTPUT_FORMAT_JPEG,
            ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR,
            ImageCapture.OUTPUT_FORMAT_RAW_JPEG -> "image/jpeg"

            ImageCapture.OUTPUT_FORMAT_RAW -> "image/x-adobe-dng"

            else -> error("Unknown image output format")
        }

        val imageCount = AtomicInteger(
            when (imageOutputFormat) {
                ImageCapture.OUTPUT_FORMAT_RAW_JPEG -> 2
                else -> 1
            }
        )

        // Create output options object which contains file + metadata
        val timestamp = System.currentTimeMillis()
        val outputOptions = StorageUtils.getPhotoMediaStoreOutputOptions(
            applicationContext.contentResolver,
            ImageCapture.Metadata().apply {
                if (!inSingleCaptureMode.value) {
                    location = this@CameraViewModel.location.value
                }
                if (cameraFacing.value == CameraFacing.FRONT) {
                    isReversedHorizontal = preferencesRepository.photoFfcMirror.value
                }
            },
            mimeType,
            timestamp,
            photoOutputStream,
        )

        // Set up image capture listener, which is triggered after photo has
        // been taken
        val onImageSavedCallback = object : ImageCapture.OnImageSavedCallback {
            override fun onCaptureStarted() {
                Log.i(CameraActivity.PERF_TAG, "capture started ${SystemClock.uptimeMillis() - tapMs} ms after shutter, mode ${cameraController.imageCaptureMode}")
                emitEvent(Event.PhotoCaptureStatus.CaptureStarted)

                cameraSoundsUtils.playShutterClick()
            }

            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                Log.i(CameraActivity.PERF_TAG, "image saved ${SystemClock.uptimeMillis() - tapMs} ms after shutter, mode ${cameraController.imageCaptureMode}")
                Log.d(
                    LOG_TAG,
                    "Photo capture succeeded: ${output.savedUri} format = ${output.imageFormat}"
                )

                if (!inSingleCaptureMode.value) {
                    output.savedUri?.let {
                        mediaRepository.broadcastNewPicture(it)
                        if (output.imageFormat !in RAW_FORMATS) {
                            val label = oemProcessingLabel
                            val motion = wantsMotion && motionShutterTs != 0L
                            if (label == null && !motion) {
                                lookController.onPhotoSaved(it)
                            } else viewModelScope.launch {
                                // The look queue rewrites the JPEG; tag and embed first so both are carried over.
                                label?.let { l -> withContext(Dispatchers.IO) { tagProcessingSource(it, l) } }
                                if (motion) motionPhoto.embed(it, motionShutterTs)
                                lookController.onPhotoSaved(it)
                            }
                        }
                    }
                }

                if (imageCount.decrementAndGet() == 0) {
                    cameraState.value = CameraState.IDLE
                }

                // We only care about JPEG-like formats
                if (imageOutputFormat == ImageCapture.OUTPUT_FORMAT_RAW_JPEG
                    && output.imageFormat in RAW_FORMATS
                ) {
                    return
                }

                emitEvent(
                    Event.PhotoCaptureStatus.ImageSaved(
                        output,
                        photoOutputStream,
                    )
                )
            }

            override fun onError(exc: ImageCaptureException) {
                emitEvent(Event.PhotoCaptureStatus.Error(exc))

                Log.e(LOG_TAG, "Photo capture failed", exc)
                cameraState.value = CameraState.IDLE
            }
        }

        when (imageOutputFormat) {
            ImageCapture.OUTPUT_FORMAT_RAW_JPEG -> {
                // Create output options object which contains raw file + metadata
                val rawOutputOptions = StorageUtils.getPhotoMediaStoreOutputOptions(
                    applicationContext.contentResolver,
                    ImageCapture.Metadata().apply {
                        if (!inSingleCaptureMode.value) {
                            location = this@CameraViewModel.location.value
                        }
                        if (cameraFacing.value == CameraFacing.FRONT) {
                            isReversedHorizontal = preferencesRepository.photoFfcMirror.value
                        }
                    },
                    "image/x-adobe-dng",
                    timestamp,
                )

                cameraController.takePicture(
                    rawOutputOptions,
                    outputOptions,
                    ContextCompat.getMainExecutor(applicationContext),
                    onImageSavedCallback,
                )
            }

            else -> cameraController.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(applicationContext),
                onImageSavedCallback,
            )
        }
    }

    /**
     * Runs the active creative mode. Returns false when the regular capture should be used
     * instead; failures inside the creative capture also end in a regular photo.
     */
    @SuppressLint("RestrictedApi")
    private fun takeCreativePhoto(): Boolean {
        if (creative.mode.value == CreativeMode.OFF) return false
        if (inSingleCaptureMode.value || cameraFacing.value == CameraFacing.FRONT) return false
        if (cameraController.imageOutputFormat != ImageCapture.OUTPUT_FORMAT_JPEG) return false
        val cameraControl = cameraController.cameraControl ?: return false
        val cameraInfo = cameraController.cameraInfo ?: return false
        if (multiFrame.analyzer.frameSize == null) {
            Log.w(LOG_TAG, "Creative ${creative.mode.value}: no analysis frames yet, regular photo")
            return false
        }
        Log.i(LOG_TAG, "Creative ${creative.mode.value} capture")

        cameraState.value = CameraState.TAKING_PHOTO
        viewModelScope.launch {
            emitEvent(Event.PhotoCaptureStatus.CaptureStarted)
            cameraSoundsUtils.playShutterClick()
            val started = try {
                creative.capture(cameraControl, cameraInfo, location.value) { uris ->
                    for (uri in uris) {
                        mediaRepository.broadcastNewPicture(uri)
                        lookController.onPhotoSaved(uri)
                    }
                    uris.lastOrNull()?.let {
                        emitEvent(Event.PhotoCaptureStatus.ImageSaved(ImageCapture.OutputFileResults(it, ImageFormat.JPEG), null))
                    }
                }
            } catch (e: CancellationException) {
                cameraState.value = CameraState.IDLE
                throw e
            } catch (e: Throwable) {
                Log.e(LOG_TAG, "Creative capture failed", e)
                false
            }
            cameraState.value = CameraState.IDLE
            if (!started) takePhoto(allowMultiFrame = false)
        }
        return true
    }

    /**
     * Captures a burst over the analysis stream when a multi-frame mode applies. Returns false
     * when the regular single capture should be used instead.
     */
    @SuppressLint("RestrictedApi")
    private fun takeMultiFramePhoto(): Boolean {
        if (inSingleCaptureMode.value || cameraFacing.value == CameraFacing.FRONT) {
            return false
        }
        val cameraControl = cameraController.cameraControl ?: return false
        val cameraInfo = cameraController.cameraInfo ?: return false
        // Bursts are YUV-only: keep the user's RAW / Ultra HDR choice.
        if (cameraController.imageOutputFormat != ImageCapture.OUTPUT_FORMAT_JPEG) return false
        val zoomRatio = cameraController.zoomState.value?.zoomRatio ?: 1f
        val plan = multiFrame.plan(cameraInfo, zoomRatio) ?: return false

        cameraState.value = CameraState.TAKING_PHOTO
        viewModelScope.launch {
            emitEvent(Event.PhotoCaptureStatus.CaptureStarted)
            cameraSoundsUtils.playShutterClick()

            val captured = try {
                multiFrame.capture(cameraControl, cameraInfo, plan)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(LOG_TAG, "Burst capture failed, taking a single frame", e)
                null
            }
            cameraState.value = CameraState.IDLE
            if (captured == null) {
                takePhoto(allowMultiFrame = false)
                return@launch
            }
            multiFrame.process(captured, location.value) { uri ->
                uri ?: return@process
                mediaRepository.broadcastNewPicture(uri)
                lookController.onPhotoSaved(uri)
                emitEvent(
                    Event.PhotoCaptureStatus.ImageSaved(
                        ImageCapture.OutputFileResults(uri, ImageFormat.JPEG),
                        null,
                    )
                )
            }
        }
        return true
    }
    /** One RAW_SENSOR still from the RAW session's ImageCapture (main thread). */
    private suspend fun takeRawImage(): ImageProxy = suspendCancellableCoroutine { cont ->
        cameraController.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                if (!cont.tryResumeImage(image)) image.close()
            }

            override fun onError(exception: ImageCaptureException) {
                if (cont.isActive) cont.resumeWithException(exception)
            }
        })
    }

    private fun kotlinx.coroutines.CancellableContinuation<ImageProxy>.tryResumeImage(image: ImageProxy): Boolean {
        if (!isActive) return false
        resume(image)
        return true
    }

    /**
     * Photo in the full-resolution session: a burst when one is planned, otherwise a single
     * frame from the stream, encoded by the app. On failure the next shot falls back to the
     * regular JPEG session (the activity rebinds) with a notice.
     */
    @SuppressLint("RestrictedApi")
    private fun takeFullResPhoto(allowMerge: Boolean) {
        val cameraControl = cameraController.cameraControl ?: return
        val cameraInfo = cameraController.cameraInfo ?: return
        val zoomRatio = cameraController.zoomState.value?.zoomRatio ?: 1f
        val plan = (if (allowMerge) multiFrame.plan(cameraInfo, zoomRatio) else null)
            ?: BurstPlan(BurstKind.SINGLE, 1)
        val config = multiFrame.streamConfig
        cameraState.value = CameraState.TAKING_PHOTO
        val motionShutterTs = motionPhoto.shutterTimestamp()
        val wantsMotion = motionPhoto.motionPhoto.value
        burstJob = viewModelScope.launch {
            emitEvent(Event.PhotoCaptureStatus.CaptureStarted)
            cameraSoundsUtils.playShutterClick()
            val takeRaw: suspend () -> ImageProxy = { takeRawImage() }
            // RAW bursts and long Pro exposures run as one Camera2 burst; if that fails the RAW
            // session only takes a single frame, since RAW stills through CameraX queue behind the preview.
            val camera2Plan = multiFrame.camera2Plan(plan)
            val single: suspend () -> MultiFrameCaptureController.CapturedBurst = {
                multiFrame.capture(cameraControl, cameraInfo, BurstPlan(BurstKind.SINGLE, 1), takeRaw = takeRaw)
            }
            val captured = try {
                ManualStill.withFallback(
                    primary = if (camera2Plan != null) {
                        { multiFrame.captureCamera2(cameraInfo, camera2Plan) }
                    } else {
                        { multiFrame.capture(cameraControl, cameraInfo, plan, takeRaw = takeRaw) }
                    },
                    fallback = single.takeIf { camera2Plan != null || plan.kind != BurstKind.SINGLE },
                ) { step, e -> Log.e(LOG_TAG, "Full-resolution ${plan.kind} capture ($step) failed", e) }
            } catch (e: CancellationException) {
                cameraState.value = CameraState.IDLE
                Log.i(LOG_TAG, "Burst cancelled")
                throw e
            }
            cameraState.value = CameraState.IDLE
            if (captured == null) {
                multiFrame.demote(config)
                _fullResNotice.tryEmit(R.string.full_res_fallback)
                return@launch
            }
            multiFrame.process(captured, location.value) { uri ->
                uri ?: return@process
                mediaRepository.broadcastNewPicture(uri)
                if (wantsMotion && motionShutterTs != 0L) {
                    viewModelScope.launch {
                        motionPhoto.embed(uri, motionShutterTs)
                        lookController.onPhotoSaved(uri)
                    }
                } else {
                    lookController.onPhotoSaved(uri)
                }
                emitEvent(
                    Event.PhotoCaptureStatus.ImageSaved(ImageCapture.OutputFileResults(uri, ImageFormat.JPEG), null)
                )
            }
        }
    }

    private val _fullResNotice = MutableSharedFlow<Int>(extraBufferCapacity = 2)

    /** Notices (string resources) when the full-resolution session falls back. */
    val fullResNotice = _fullResNotice.asSharedFlow()

    /**
     * Extension to bind: the effect the user picked, else the phone's processing for the
     * multi-frame setting when the user prefers it and the camera offers it, else none.
     */
    @androidx.annotation.OptIn(ExperimentalZeroShutterLag::class)
    fun resolveExtensionMode(cameraConfiguration: CameraConfiguration): Int {
        if (cameraConfiguration !is CameraConfiguration.Photo ||
            cameraConfiguration.extensionMode != ExtensionMode.NONE
        ) {
            return cameraConfiguration.extensionMode
        }
        if (inSingleCaptureMode.value ||
            cameraConfiguration.photoOutputFormat != PhotoOutputFormat.JPEG ||
            cameraConfiguration.photoCaptureMode == ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG
        ) {
            return ExtensionMode.NONE
        }
        val feature = multiFrame.setting.value.oemFeature() ?: return ExtensionMode.NONE
        val mode = feature.toExtensionMode()
        val available = cameraConfiguration.camera.supportsExtensionMode(mode)
        return when (vendorBridge.resolve(feature, available)) {
            ProcessingSource.PHONE -> mode
            ProcessingSource.OPEN -> ExtensionMode.NONE
        }
    }

    /** The phone's processing the bridge picked for [cameraConfiguration], if any. */
    fun bridgedOemFeature(cameraConfiguration: CameraConfiguration): OemFeature? =
        if (cameraConfiguration.extensionMode == ExtensionMode.NONE) {
            oemFeatureOf(resolveExtensionMode(cameraConfiguration))
        } else null

    /** Vendor request tags the device profile enables for [cameraId]. */
    fun vendorTagsFor(cameraId: String): List<VendorTagRegistry.Tag> {
        val profile = deviceProfileRepository.resolved.value ?: return emptyList()
        if (profile.vendorTags.isEmpty()) return emptyList()
        return vendorBridge.selectTags(
            profile.vendorTags,
            profile.source == ProfileSource.VERIFIED,
            ExtensionsProbe.requestKeyNames(cameraManager, cameraId),
        )
    }

    private fun tagProcessingSource(uri: Uri, label: String) =
        FeatureGuard.attempt(LOG_TAG, "Processing source EXIF") {
            applicationContext.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                ExifInterface(pfd.fileDescriptor).apply {
                    setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, label)
                    saveAttributes()
                }
            }
        }

    fun captureVideo() {
        if (cameraState.value != CameraState.IDLE) {
            if (cameraController.isRecording) {
                // Stop the current recording session.
                videoRecording.value?.stop()
            }
            return
        }

        // Disallow state changes while we are about to prepare for recording video
        cameraState.value = CameraState.PRE_RECORDING_VIDEO

        // Update duration text
        videoRecordingDuration.value = 0L

        // Create output options object which contains file + metadata
        val outputOptions = StorageUtils.getVideoMediaStoreOutputOptions(
            applicationContext.contentResolver,
            System.currentTimeMillis(),
            location.value.takeUnless { inSingleCaptureMode.value }
        )

        // Play shutter sound
        val delayTime = if (cameraSoundsUtils.playStartVideoRecording()) 500L else 0L

        viewModelScope.launch {
            delay(delayTime)

            // Start recording
            videoRecording.value = cameraController.startRecording(
                outputOptions,
                videoAudioConfig,
                cameraExecutor,
            ) {
                viewModelScope.launch {
                    videoRecordEvent.emit(it)
                }

                when (it) {
                    is VideoRecordEvent.Start -> {
                        cameraState.value = CameraState.RECORDING_VIDEO
                    }

                    is VideoRecordEvent.Pause -> {
                        cameraState.value = CameraState.RECORDING_VIDEO_PAUSED
                    }

                    is VideoRecordEvent.Resume -> {
                        cameraState.value = CameraState.RECORDING_VIDEO
                    }

                    is VideoRecordEvent.Status -> {
                        videoRecordingDuration.value = it.recordingStats.recordedDurationNanos
                    }

                    is VideoRecordEvent.Finalize -> {
                        cameraSoundsUtils.playStopVideoRecording()
                        if (it.error != VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA) {
                            Log.d(
                                LOG_TAG,
                                "Video capture succeeded: ${it.outputResults.outputUri}",
                            )

                            if (!inSingleCaptureMode.value) {
                                mediaRepository.broadcastNewVideo(it.outputResults.outputUri)
                            }
                        }
                        cameraState.value = CameraState.IDLE
                        videoRecording.value = null
                    }
                }
            }
        }
    }

    fun onVideoRecordingStateButtonPress() {
        when (cameraState.value) {
            CameraState.RECORDING_VIDEO -> videoRecording.value?.pause()
            CameraState.RECORDING_VIDEO_PAUSED -> videoRecording.value?.resume()
            else -> error(
                "videoRecordingStateButton clicked while in invalid state: ${cameraState.value}"
            )
        }
    }

    /**
     * Flip the camera.
     */
    fun flipCamera(): Boolean = updateConfiguration<CameraConfiguration>(retry = { flipCamera(); Unit }) { cameraConfiguration ->
        val cameraForCycling = camerasForCycling.value

        val cameraFacing = cameraForCycling.keys.sortedWith(
            cameraFacingComparator
        ).next(cameraConfiguration.camera.cameraFacing) ?: error("No camera available")

        val camera = when (cameraFacing) {
            CameraFacing.UNKNOWN -> null
            CameraFacing.FRONT -> cameraRepository.mainFrontCamera
            CameraFacing.BACK -> cameraRepository.mainBackCamera
            CameraFacing.EXTERNAL -> null
        } ?: cameraForCycling[cameraFacing]?.firstOrNull() ?: error(
            "Camera with facing $cameraFacing not available"
        )

        preferencesRepository.lastCameraFacing.value = camera.cameraFacing

        emitEvent(Event.FlipCameraAnimation)

        createInitialCameraConfiguration(
            camera = camera,
            cameraMode = cameraConfiguration.cameraMode,
        )
    }

    fun setCameraMode(
        cameraMode: CameraMode,
    ): kotlinx.coroutines.Job = viewModelScope.launch {
        updateConfiguration<CameraConfiguration>(retry = { setCameraMode(cameraMode); Unit }) { cameraConfiguration ->
            if (cameraConfiguration.cameraMode == cameraMode) {
                return@updateConfiguration cameraConfiguration
            }

            val camera = cameraConfiguration.camera.takeIf {
                it.supportsCameraMode(cameraMode)
            } ?: getSuitableCamera(
                cameraMode,
                cameraConfiguration.camera.cameraFacing,
            ) ?: run {
                Log.e(LOG_TAG, "No camera supports the requested camera mode $cameraMode")
                return@updateConfiguration cameraConfiguration
            }

            preferencesRepository.lastCameraMode.value = cameraMode

            createInitialCameraConfiguration(
                camera = camera,
                cameraMode = cameraMode,
            )
        }
    }

    /**
     * Switch to the previous camera mode.
     */
    fun previousCameraMode() = cameraMode.value.previous()?.let { setCameraMode(it) }

    /**
     * Switch to the next camera mode.
     */
    fun nextCameraMode() = cameraMode.value.next()?.let { setCameraMode(it) }

    /**
     * Cycle flash mode
     *
     * @param forceTorch Whether force torch mode should be toggled
     * @return true if the flash mode was changed, false otherwise
     */
    fun cycleFlashMode(forceTorch: Boolean): Boolean {
        val cameraConfiguration = _cameraConfiguration.value ?: return false

        // Long-press is supported only on photo mode and if torch mode is available
        val forceTorchAvailable = cameraConfiguration.cameraMode == CameraMode.PHOTO
                && cameraConfiguration.camera.supportedFlashModes.contains(FlashMode.TORCH)
        if (forceTorch && !forceTorchAvailable) {
            this.forceTorch.value = false

            return false
        }

        when (forceTorch) {
            true -> {
                this.forceTorch.value = this.forceTorch.value.not()
            }

            else -> when (this.forceTorch.value) {
                true -> {
                    // Just disable torch mode
                    this.forceTorch.value = false
                }

                false -> supportedFlashModes.value.toList().next(flashMode.value)?.let {
                    when (cameraConfiguration.cameraMode) {
                        CameraMode.PHOTO -> preferencesRepository.photoFlashMode.value = it
                        CameraMode.VIDEO -> preferencesRepository.videoFlashMode.value = it
                        CameraMode.QR -> qrFlashMode.value = it
                    }
                }
            }
        }

        // Check if we should show the force torch suggestion
        if (!preferencesRepository.forceTorchHelpShown.value) {
            if (forceTorch) {
                // The user figured it out by themself
                preferencesRepository.forceTorchHelpShown.value = true
            } else {
                emitEvent(Event.ShowForceTorchHelp)
            }
        }

        return true
    }

    /**
     * Set whether the force torch help has been shown.
     */
    fun setForceModeHelpShown(forceTorchHelpShown: Boolean) {
        preferencesRepository.forceTorchHelpShown.value = forceTorchHelpShown
    }

    /**
     * Cycle to the next grid mode.
     */
    fun cycleGridMode() {
        gridMode.value.next()?.let {
            preferencesRepository.lastGridMode.value = it
        }
    }

    /**
     * Toggle the timer mode.
     */
    fun toggleTimerMode() {
        timerMode.value.next()?.let {
            preferencesRepository.timerMode.value = it
        }
    }

    /**
     * Cycle the extension mode.
     */
    fun cycleExtensionMode(): Boolean =
        updateConfiguration<CameraConfiguration.Photo>(retry = { cycleExtensionMode(); Unit }) { cameraConfiguration ->
            val supportedExtensionModes = cameraConfiguration.camera.supportedExtensionModes
            val extensionMode = supportedExtensionModes.toList().sorted().next(
                cameraConfiguration.extensionMode
            ) ?: error("No extension mode supported by the camera")

            preferencesRepository.photoEffect.value = extensionMode

            cameraConfiguration.copy(
                extensionMode = extensionMode,
            )
        }

    /**
     * Cycle the photo aspect ratio.
     */
    fun cyclePhotoAspectRatio(): Boolean =
        updateConfiguration<CameraConfiguration.Photo>(retry = { cyclePhotoAspectRatio(); Unit }) { cameraConfiguration ->
            val newAspectRatio = when (cameraConfiguration.photoAspectRatio) {
                AspectRatio.RATIO_4_3 -> AspectRatio.RATIO_16_9
                AspectRatio.RATIO_16_9 -> AspectRatio.RATIO_4_3
                else -> AspectRatio.RATIO_4_3
            }

            preferencesRepository.photoAspectRatio.value = newAspectRatio

            cameraConfiguration.copy(
                photoAspectRatio = newAspectRatio,
            )
        }

    /**
     * Cycle the video quality.
     */
    fun cycleVideoQuality(): Boolean =
        updateConfiguration<CameraConfiguration.Video>(retry = { cycleVideoQuality(); Unit }) { cameraConfiguration ->
            val videoQuality =
                cameraConfiguration.camera.supportedVideoQualities.keys.toList()
                    .sortedWith { a, b ->
                        listOf(Quality.SD, Quality.HD, Quality.FHD, Quality.QHD, Quality.UHD).let {
                            it.indexOf(a) - it.indexOf(b)
                        }
                    }.next(cameraConfiguration.videoQuality) ?: error(
                    "No video quality supported by the camera"
                )

            val videoQualityInfo = cameraConfiguration.camera.supportedVideoQualities[
                videoQuality
            ] ?: error("Video quality not supported")

            val videoFrameRate = cameraConfiguration.videoFrameRate.takeIf {
                videoQualityInfo.supportedFrameRates.contains(it)
            } ?: videoQualityInfo.supportedFrameRates.firstOrNull()

            val videoDynamicRange = cameraConfiguration.videoDynamicRange.takeIf {
                videoQualityInfo.supportedDynamicRanges.contains(it)
            } ?: videoQualityInfo.supportedDynamicRanges.firstOrNull() ?: error(
                "No video dynamic range supported by the camera"
            )

            preferencesRepository.videoQuality.value = videoQuality

            cameraConfiguration.copy(
                videoQuality = videoQuality,
                videoFrameRate = videoFrameRate,
                videoDynamicRange = videoDynamicRange,
            )
        }

    /**
     * Cycle the video frame rate.
     */
    fun cycleVideoFrameRate(): Boolean =
        updateConfiguration<CameraConfiguration.Video>(retry = { cycleVideoFrameRate(); Unit }) { cameraConfiguration ->
            val videoQualityInfo = cameraConfiguration.camera.supportedVideoQualities[
                cameraConfiguration.videoQuality
            ] ?: error(
                "Camera ${cameraConfiguration.camera.cameraId} does not support video quality ${cameraConfiguration.videoQuality}"
            )

            val videoFrameRate = videoQualityInfo.supportedFrameRates.toList().sorted().next(
                cameraConfiguration.videoFrameRate
            )

            preferencesRepository.videoFrameRate.value = videoFrameRate

            cameraConfiguration.copy(
                videoFrameRate = videoFrameRate,
            )
        }

    /**
     * Cycle the video dynamic range.
     */
    fun cycleVideoDynamicRange(): Boolean =
        updateConfiguration<CameraConfiguration.Video>(retry = { cycleVideoDynamicRange(); Unit }) { cameraConfiguration ->
            val videoQualityInfo = cameraConfiguration.camera.supportedVideoQualities[
                cameraConfiguration.videoQuality
            ] ?: error(
                "Camera ${cameraConfiguration.camera.cameraId} does not support video quality ${cameraConfiguration.videoQuality}"
            )

            val videoDynamicRange = videoQualityInfo.supportedDynamicRanges.toList().next(
                cameraConfiguration.videoDynamicRange
            ) ?: error("No video dynamic range supported by the camera")

            preferencesRepository.videoDynamicRange.value = videoDynamicRange

            cameraConfiguration.copy(
                videoDynamicRange = videoDynamicRange,
            )
        }

    /**
     * Toggle the video microphone status.
     */
    fun toggleVideoMicrophoneEnabled() = setVideoMicrophoneEnabled(!videoMicMode.value)

    /**
     * Set whether the video microphone is enabled.
     *
     * @param videoMicrophoneEnabled Whether the video microphone should be enabled
     */
    @Suppress("MissingPermission")
    fun setVideoMicrophoneEnabled(videoMicrophoneEnabled: Boolean) {
        videoAudioConfig = when (videoMicrophoneEnabled) {
            true -> AudioConfig.create(true)
            false -> AudioConfig.AUDIO_DISABLED
        }

        videoRecording.value?.mute(!videoMicrophoneEnabled)

        this.videoMicMode.value = videoMicrophoneEnabled

        preferencesRepository.videoMicMode.value = videoMicrophoneEnabled
    }

    /**
     * Set the desired exposure compensation range.
     *
     * @param exposureCompensationLevel A value between 0 and 1, with 0.5 being 0 EV
     */
    fun setExposureCompensationLevel(exposureCompensationLevel: Float) {
        require(exposureCompensationLevel in 0f..1f) {
            "Exposure compensation level must be between 0 and 1, got $exposureCompensationLevel"
        }

        this.exposureCompensationLevel.value = exposureCompensationLevel
    }

    /**
     * Apply the specified zoom smoothly. The value will be automatically clamped
     * between min and max.
     * @param zoomRatio The zoom ratio to apply
     */
    fun smoothZoom(zoomRatio: Float) {
        val acquired = zoomGestureMutex.tryLock()
        if (!acquired) {
            return
        }

        val zoomState = zoomState.value ?: return

        ValueAnimator.ofFloat(
            zoomState.zoomRatio,
            zoomRatio.coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
        ).apply {
            addUpdateListener {
                cameraController.setZoomRatio(it.animatedValue as Float)
            }
            addListener(
                onEnd = {
                    zoomGestureMutex.unlock()
                }
            )
        }.start()
    }

    /**
     * Reset the zoom to 1.0x (value relative to the lens, not of the FOV).
     */
    fun resetZoom() = smoothZoom(1f)

    /**
     * Zoom in by a power of 2.
     */
    fun zoomIn() = zoomState.value?.zoomRatio?.let {
        smoothZoom(it.nextPowerOfTwo())
    }

    /**
     * Zoom out by a power of 2.
     */
    fun zoomOut() = zoomState.value?.zoomRatio?.let {
        smoothZoom(it.previousPowerOfTwo())
    }

    fun setSaveLocation(saveLocation: Boolean?) {
        preferencesRepository.saveLocation.value = saveLocation
    }

    fun onQrDialogDismissed() {
        qrImageAnalyzer.dismissResult()
    }

    fun getHardwareKeyAction(
        hardwareKey: HardwareKey,
    ) = preferencesRepository.hardwareKeyActionPreferences[
        hardwareKey
    ]?.value ?: hardwareKey.defaultAction

    fun getHardwareKeyInvert(
        hardwareKey: HardwareKey,
    ) = hardwareKey.isTwoWayKey && preferencesRepository.hardwareKeyInvertPreferences[
        hardwareKey
    ]?.value ?: false

    fun fileExists(uri: Uri) = mediaRepository.fileExists(uri)

    /**
     * Get whether or not any camera currently supports video mode.
     */
    suspend fun isVideoRecordingAvailable() = cameraRepository.cameras.first().any {
        it.supportsCameraMode(CameraMode.VIDEO)
    }

    /**
     * Emit a new event to let the activity handle it.
     */
    private fun emitEvent(event: Event) = viewModelScope.launch {
        _event.emit(event)
    }

    /**
     * Get a suitable [Camera] for the provided [CameraFacing] and the current [CameraMode].
     *
     * @param cameraMode The requested [CameraMode]
     * @param cameraFacing The requested [CameraFacing]
     * @return A [Camera] that is compatible with the provided configuration or null
     */
    private suspend fun getSuitableCamera(
        cameraMode: CameraMode,
        cameraFacing: CameraFacing,
    ): Camera? {
        val compatibleCameras = cameraRepository.cameras.first()
            .filter { camera ->
                camera.supportsCameraMode(cameraMode)
            }

        return compatibleCameras.firstOrNull { camera ->
            camera.cameraFacing == cameraFacing
        } ?: compatibleCameras.firstOrNull()
    }

    /**
     * Set when a ZSL session could not be bound; later configurations use the regular capture
     * mode for the rest of the process.
     */
    @Volatile
    var zslFailed = false

    /** ZSL turns the phone's extension processing off, so a usable extension takes precedence. */
    private fun oemExtensionAvailable(camera: Camera): Boolean = runCatching {
        val mode = multiFrame.setting.value.oemFeature()?.toExtensionMode() ?: return false
        camera.supportsExtensionMode(mode)
    }.getOrDefault(false)

    @androidx.annotation.OptIn(ExperimentalZeroShutterLag::class)
    private fun createInitialCameraConfiguration(
        camera: Camera,
        cameraMode: CameraMode,
    ) = when (cameraMode) {
        CameraMode.PHOTO -> {
            // Enable ZSL when requested by the user and supported by the camera
            val photoCaptureMode = when (
                val photoCaptureMode = preferencesRepository.photoCaptureMode.value
            ) {
                ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY -> when (
                    useZsl(
                        preferencesRepository.enableZsl.value, camera.supportsZsl, zslFailed,
                        oemExtensionAvailable(camera),
                    )
                ) {
                    true -> ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG
                    false -> ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                }

                else -> photoCaptureMode
            }

            val usePhotoJpegUltraHdr = preferencesRepository.usePhotoJpegUltraHdr.value
            val enableRawImageCapture = preferencesRepository.enableRawImageCapture.value
            val disableJpegWithRaw = preferencesRepository.disableJpegWithRaw.value
            val photoOutputFormat = when {
                inSingleCaptureMode.value -> PhotoOutputFormat.JPEG

                enableRawImageCapture
                        && disableJpegWithRaw
                        && camera.supportedPhotoOutputFormats.contains(
                    PhotoOutputFormat.RAW
                ) -> PhotoOutputFormat.RAW

                enableRawImageCapture && camera.supportedPhotoOutputFormats.contains(
                    PhotoOutputFormat.RAW_JPEG
                ) -> PhotoOutputFormat.RAW_JPEG

                usePhotoJpegUltraHdr && camera.supportedPhotoOutputFormats.contains(
                    PhotoOutputFormat.JPEG_ULTRA_HDR
                ) -> PhotoOutputFormat.JPEG_ULTRA_HDR

                else -> PhotoOutputFormat.JPEG
            }
            Log.i(LOG_TAG, "Camera ${camera.cameraId}: photo formats ${camera.supportedPhotoOutputFormats}, using $photoOutputFormat")

            CameraConfiguration.Photo(
                camera = camera,
                extensionMode = preferencesRepository.photoEffect.value,
                camera2Options = buildPhotoCamera2Options(camera, photoCaptureMode),
                photoCaptureMode = photoCaptureMode,
                photoAspectRatio = preferencesRepository.photoAspectRatio.value,
                enableHighResolution = overlaysRepository.enableHighResolution,
                photoOutputFormat = photoOutputFormat,
            )
        }

        CameraMode.VIDEO -> {
            val videoQuality = preferencesRepository.videoQuality.value.takeIf {
                camera.supportedVideoQualities.contains(it)
            } ?: camera.supportedVideoQualities.keys.firstOrNull() ?: error(
                "Camera ${camera.cameraId} does not support any video quality"
            )

            val videoQualityInfo = camera.supportedVideoQualities[videoQuality] ?: error(
                "Camera ${camera.cameraId} does not support video quality $videoQuality"
            )

            val videoFrameRate = preferencesRepository.videoFrameRate.value.takeIf {
                videoQualityInfo.supportedFrameRates.contains(it)
            } ?: videoQualityInfo.supportedFrameRates.firstOrNull()

            val videoDynamicRange = preferencesRepository.videoDynamicRange.value.takeIf {
                videoQualityInfo.supportedDynamicRanges.contains(it)
            } ?: videoQualityInfo.supportedDynamicRanges.firstOrNull() ?: error(
                "No video dynamic range supported by the camera"
            )

            CameraConfiguration.Video(
                camera = camera,
                camera2Options = buildVideoCamera2Options(camera),
                videoQuality = videoQuality,
                videoFrameRate = videoFrameRate,
                videoDynamicRange = videoDynamicRange,
                videoMirrorMode = preferencesRepository.videoMirrorMode.value,
                enableVideoStabilization = preferencesRepository.videoStabilization.value,
            )
        }

        CameraMode.QR -> CameraConfiguration.Qr(
            camera = camera,
        )
    }

    @androidx.annotation.OptIn(ExperimentalZeroShutterLag::class)
    private fun buildPhotoCamera2Options(
        camera: Camera,
        photoCaptureMode: Int,
    ) = CameraConfiguration.Camera2Options(
        edgeMode = preferencesRepository.edgeMode.value?.takeIf {
            camera.supportedEdgeModes.contains(it) && when (
                photoCaptureMode
            ) {
                ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG ->
                    EdgeMode.ALLOWED_MODES_ON_ZSL.contains(it)

                else -> true
            }
        },
        noiseReductionMode = preferencesRepository.noiseReductionMode.value?.takeIf {
            camera.supportedNoiseReductionModes.contains(it) && when (
                photoCaptureMode
            ) {
                ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG ->
                    NoiseReductionMode.ALLOWED_MODES_ON_ZSL.contains(it)

                else -> true
            }
        },
        shadingMode = preferencesRepository.shadingMode.value?.takeIf {
            camera.supportedShadingModes.contains(it) && when (
                photoCaptureMode
            ) {
                ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG ->
                    ShadingMode.ALLOWED_MODES_ON_ZSL.contains(it)

                else -> true
            }
        },
        colorCorrectionAberrationMode = preferencesRepository.colorCorrectionAberrationMode.value?.takeIf {
            camera.supportedColorCorrectionAberrationModes.contains(it) && when (
                photoCaptureMode
            ) {
                ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG ->
                    ColorCorrectionAberrationMode.ALLOWED_MODES_ON_ZSL.contains(it)

                else -> true
            }
        },
        distortionCorrectionMode = preferencesRepository.distortionCorrectionMode.value?.takeIf {
            camera.supportedDistortionCorrectionModes.contains(it) && when (
                photoCaptureMode
            ) {
                ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG ->
                    DistortionCorrectionMode.ALLOWED_MODES_ON_ZSL.contains(it)

                else -> true
            }
        },
        hotPixelMode = preferencesRepository.hotPixelMode.value?.takeIf {
            camera.supportedHotPixelModes.contains(it) && when (
                photoCaptureMode
            ) {
                ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG ->
                    HotPixelMode.ALLOWED_MODES_ON_ZSL.contains(it)

                else -> true
            }
        },
    )

    @androidx.annotation.OptIn(ExperimentalZeroShutterLag::class)
    private fun buildVideoCamera2Options(
        camera: Camera,
    ) = CameraConfiguration.Camera2Options(
        edgeMode = preferencesRepository.edgeMode.value?.takeIf {
            camera.supportedEdgeModes.contains(it)
                    && EdgeMode.ALLOWED_MODES_ON_VIDEO_MODE.contains(it)
        },
        noiseReductionMode = preferencesRepository.noiseReductionMode.value?.takeIf {
            camera.supportedNoiseReductionModes.contains(it)
                    && NoiseReductionMode.ALLOWED_MODES_ON_VIDEO_MODE.contains(it)
        },
        shadingMode = preferencesRepository.shadingMode.value?.takeIf {
            camera.supportedShadingModes.contains(it)
                    && ShadingMode.ALLOWED_MODES_ON_VIDEO_MODE.contains(it)
        },
        colorCorrectionAberrationMode = preferencesRepository.colorCorrectionAberrationMode.value?.takeIf {
            camera.supportedColorCorrectionAberrationModes.contains(it)
                    && ColorCorrectionAberrationMode.ALLOWED_MODES_ON_VIDEO_MODE.contains(it)
        },
        distortionCorrectionMode = preferencesRepository.distortionCorrectionMode.value?.takeIf {
            camera.supportedDistortionCorrectionModes.contains(it)
                    && DistortionCorrectionMode.ALLOWED_MODES_ON_VIDEO_MODE.contains(it)
        },
        hotPixelMode = preferencesRepository.hotPixelMode.value?.takeIf {
            camera.supportedHotPixelModes.contains(it)
                    && HotPixelMode.ALLOWED_MODES_ON_VIDEO_MODE.contains(it)
        },
    )

    /**
     * Reconfigure the camera session. This helper method will also handle concurrency.
     *
     * @param block A block that will be executed with the current [CameraConfiguration] and should
     *   return a new one.
     */
    private inline fun <reified T : CameraConfiguration> updateConfiguration(
        noinline retry: (() -> Unit)? = null,
        block: (T) -> CameraConfiguration,
    ): Boolean {
        if (!canRestartCamera()) {
            // A recording is ended by the user; a change then would surprise, so it is dropped.
            if (retry == null || cameraState.value.isRecordingVideo) return false
            deferUntilIdle(retry)
            return true
        }

        if (!rebindMutex.tryLock()) {
            // Another update is still running (it may suspend, e.g. while picking a camera).
            // Keep only the newest request and run it once that update released the lock.
            if (retry == null) return false
            pendingConfigurationUpdate = retry
            return true
        }

        return try {
            val currentCameraConfiguration = _cameraConfiguration.value ?: error(
                "Camera configuration is null"
            )

            T::class.safeCast(currentCameraConfiguration)?.let {
                val newCameraConfiguration = block(it)

                _cameraConfiguration.value = newCameraConfiguration

                true
            } ?: false
        } finally {
            rebindMutex.unlock()
            pendingConfigurationUpdate?.let { next ->
                pendingConfigurationUpdate = null
                viewModelScope.launch { next() }
            }
        }
    }

    /** The latest configuration change that arrived while another one held [rebindMutex]. */
    @PublishedApi
    internal var pendingConfigurationUpdate: (() -> Unit)? = null

    /**
     * Busy capturing: keeps only the newest change and applies it once the camera is idle,
     * instead of dropping the tap; the user is told it is pending.
     */
    @PublishedApi
    internal fun deferUntilIdle(retry: () -> Unit) {
        idleUpdate = retry
        pendingChangeNotice.tryEmit(Unit)
        if (idleWaiter?.isActive == true) return
        idleWaiter = viewModelScope.launch {
            cameraState.first { it == CameraState.IDLE }
            idleUpdate?.also { idleUpdate = null }?.invoke()
        }
    }

    private var idleUpdate: (() -> Unit)? = null
    private var idleWaiter: Job? = null

    /** A camera change was accepted while capturing and will apply when the camera is idle. */
    val pendingChangeNotice = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)

    companion object {
        /** ZSL only when wanted, supported, not failed before and not hiding the phone's processing. */
        internal fun useZsl(enabled: Boolean, supported: Boolean, failed: Boolean, extension: Boolean) =
            enabled && supported && !failed && !extension

        private val LOG_TAG = CameraViewModel::class.simpleName!!

        private const val SINGLE_CAPTURE_PHOTO_BUFFER_INITIAL_SIZE_BYTES = 8 * 1024 * 1024 // 8 MiB

        private const val LENS_CHIP_ZOOM_TIMEOUT_MS = 3000L

        private val cameraFacingComparator = Comparator.comparingInt<CameraFacing> {
            when (it) {
                CameraFacing.UNKNOWN -> 3
                CameraFacing.FRONT -> 1
                CameraFacing.BACK -> 0
                CameraFacing.EXTERNAL -> 2
            }
        }

        private val RAW_FORMATS = listOf(
            ImageFormat.RAW10,
            ImageFormat.RAW12,
            ImageFormat.RAW_SENSOR,
            ImageFormat.RAW_PRIVATE,
        )
    }
}
