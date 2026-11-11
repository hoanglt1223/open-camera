/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app

import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.content.ClipData
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.AnimatedVectorDrawable
import android.graphics.drawable.ColorDrawable
import android.icu.text.DecimalFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.view.CameraControllerInterop
import dev.opencamera.capture.StreamConfig
import kotlinx.coroutines.withTimeoutOrNull
import dev.opencamera.vendorbridge.VendorTagRegistry
import dev.opencamera.vendorbridge.SourcePolicy
import dev.opencamera.vendorbridge.ProcessingSource
import dev.opencamera.vendorbridge.OemFeature
import dev.opencamera.app.ext.oemFeatureOf
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeout
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.ExperimentalZeroShutterLag
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.MirrorMode
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.CameraController
import androidx.camera.view.PreviewView
import androidx.camera.view.ScreenFlashView
import androidx.camera.viewfinder.core.ZoomGestureDetector
import androidx.cardview.widget.CardView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.children
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import coil3.load
import coil3.request.crossfade
import coil3.request.error
import coil3.request.fallback
import coil3.size.Scale
import coil3.video.VideoFrameDecoder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.opencamera.app.ext.camera2CameraControl
import dev.opencamera.app.ext.decodeSampled
import dev.opencamera.app.ext.flashMode
import dev.opencamera.app.ext.mapToRange
import dev.opencamera.app.ext.px
import dev.opencamera.app.ext.scale
import dev.opencamera.app.ext.setColorCorrectionAberrationMode
import dev.opencamera.app.ext.setDistortionCorrectionMode
import dev.opencamera.app.ext.setEdgeMode
import dev.opencamera.app.ext.setFrameRate
import dev.opencamera.app.ext.setHotPixelMode
import dev.opencamera.app.ext.setNoiseReductionMode
import dev.opencamera.app.ext.setPadding
import dev.opencamera.app.ext.setShadingMode
import dev.opencamera.app.ext.setVideoStabilizationMode
import dev.opencamera.app.ext.slide
import dev.opencamera.app.ext.slideDown
import dev.opencamera.app.ext.smoothRotate
import dev.opencamera.app.ext.transform
import dev.opencamera.app.ext.updateBarsVisibility
import dev.opencamera.app.models.AssistantIntent
import dev.opencamera.app.models.CameraConfiguration
import dev.opencamera.app.models.CameraFacing
import dev.opencamera.app.models.CameraMode
import dev.opencamera.app.ai.AiController
import dev.opencamera.app.ai.BestShotCapture
import dev.opencamera.app.ai.CompositionOverlayView
import dev.opencamera.ml.tasks.Scene
import dev.opencamera.app.models.CameraState
import dev.opencamera.app.models.Event
import dev.opencamera.app.models.FlashMode
import dev.opencamera.app.models.GestureAction
import dev.opencamera.app.models.GridMode
import dev.opencamera.app.models.HardwareKey
import dev.opencamera.app.models.MediaType
import dev.opencamera.app.models.Permission
import dev.opencamera.app.models.PermissionState
import dev.opencamera.app.models.PhotoOutputFormat
import dev.opencamera.app.models.Rotation
import dev.opencamera.app.models.ThermalStatus
import dev.opencamera.app.models.TimerMode
import dev.opencamera.app.models.VideoDynamicRange
import dev.opencamera.app.models.VideoMirrorMode
import dev.opencamera.app.models.VideoStabilizationMode
import dev.opencamera.app.pro.ProModeUi
import dev.opencamera.app.robustness.FeatureGuard
import dev.opencamera.app.ui.dialogs.LocationPermissionsDialog
import dev.opencamera.app.ui.dialogs.QrBottomSheetDialog
import dev.opencamera.app.ui.views.CameraModeSelectorLayout
import dev.opencamera.app.ui.views.CapturePreviewLayout
import dev.opencamera.app.ui.views.CountDownView
import dev.opencamera.app.ui.views.GridView
import dev.opencamera.app.ui.views.HorizontalSlider
import dev.opencamera.app.ui.views.IslandView
import dev.opencamera.app.processing.LookPreferences
import dev.opencamera.app.processing.ProcessingService
import dev.opencamera.app.ui.looks.LookPickerView
import dev.opencamera.app.modes.ColorMatchActivity
import dev.opencamera.app.modes.CreativeMode
import dev.opencamera.app.ui.views.LensSelectorLayout
import dev.opencamera.app.ui.assist.AssistController
import dev.opencamera.app.ui.assist.AssistOverlayView
import dev.opencamera.app.ui.views.PreviewBlurView
import dev.opencamera.app.ui.views.PreviewFrames
import dev.opencamera.app.ui.views.VerticalSlider
import dev.opencamera.app.utils.ExifUtils
import dev.opencamera.app.utils.GoogleLensUtils
import dev.opencamera.app.utils.PermissionsManager
import dev.opencamera.app.utils.ShortcutsUtils
import dev.opencamera.app.viewmodels.CameraViewModel
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.opencamera.app.ui.overlay.CameraOverlay
import dev.opencamera.app.ui.overlay.LiveUiValues
import dev.opencamera.app.ui.overlay.LocalLiveUi
import dev.opencamera.app.ui.overlay.CameraUiActions
import dev.opencamera.app.ui.overlay.CameraUiState
import dev.opencamera.app.ui.overlay.Control
import dev.opencamera.app.ui.overlay.LegacyControl
import dev.opencamera.app.ui.overlay.LensChipUi
import dev.opencamera.app.ui.overlay.LookItem
import dev.opencamera.app.ui.overlay.OverlayHost
import dev.opencamera.app.ui.overlay.UiMode
import dev.opencamera.app.ui.overlay.UiModes
import dev.opencamera.app.ui.skin.SkinRepository
import dev.opencamera.app.ui.skin.SkinTokens
import dev.opencamera.app.pro.ProSettings
import dev.opencamera.app.video.VideoMode
import dev.opencamera.app.video.VideoModesActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import android.graphics.Bitmap
import android.graphics.RectF
import android.view.View
import android.view.ViewOutlineProvider
import androidx.activity.OnBackPressedCallback
import androidx.constraintlayout.widget.ConstraintSet
import dev.opencamera.capture.MultiFrameSetting
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import kotlin.math.abs
import kotlin.reflect.safeCast
import androidx.camera.core.CameraState as CameraXCameraState

@androidx.annotation.OptIn(ExperimentalCamera2Interop::class, ExperimentalZeroShutterLag::class)
open class CameraActivity : AppCompatActivity(R.layout.activity_camera) {
    // View models
    private val viewModel by viewModels<CameraViewModel>()

    // Views
    private val aspectRatioButton by lazy { findViewById<Button>(R.id.aspectRatioButton) }
    private val cameraModeSelectorLayout by lazy { findViewById<CameraModeSelectorLayout>(R.id.cameraModeSelectorLayout) }
    private val capturePreviewLayout by lazy { findViewById<CapturePreviewLayout>(R.id.capturePreviewLayout) }
    private val countDownView by lazy { findViewById<CountDownView>(R.id.countDownView) }
    private val effectButton by lazy { findViewById<Button>(R.id.effectButton) }
    private val exposureLevel by lazy { findViewById<VerticalSlider>(R.id.exposureLevel) }
    private val flashButton by lazy { findViewById<ImageButton>(R.id.flashButton) }
    private val flipCameraButton by lazy { findViewById<ImageButton>(R.id.flipCameraButton) }
    private val galleryButtonCardView by lazy { findViewById<CardView>(R.id.galleryButtonCardView) }
    private val galleryButtonIconImageView by lazy { findViewById<ImageView>(R.id.galleryButtonIconImageView) }
    private val galleryButtonPreviewImageView by lazy { findViewById<ImageView>(R.id.galleryButtonPreviewImageView) }
    private val googleLensButton by lazy { findViewById<ImageButton>(R.id.googleLensButton) }
    private val gridButton by lazy { findViewById<Button>(R.id.gridButton) }
    private val gridView by lazy { findViewById<GridView>(R.id.gridView) }
    private val islandView by lazy { findViewById<IslandView>(R.id.islandView) }
    private val lensSelectorLayout by lazy { findViewById<LensSelectorLayout>(R.id.lensSelectorLayout) }
    private val assistOverlay by lazy { findViewById<AssistOverlayView>(R.id.assistOverlay) }
    private lateinit var assistController: AssistController
    private val mainLayout by lazy { findViewById<ConstraintLayout>(R.id.mainLayout) }
    private val micButton by lazy { findViewById<Button>(R.id.micButton) }
    private val multiFrameButton by lazy { findViewById<Button>(R.id.multiFrameButton) }
    private val multiFrameProgress by lazy { findViewById<LinearProgressIndicator>(R.id.multiFrameProgress) }
    private val creativeButton by lazy { findViewById<Button>(R.id.creativeButton) }
    private val motionPhotoButton by lazy { findViewById<Button>(R.id.motionPhotoButton) }
    private val preShotButton by lazy { findViewById<Button>(R.id.preShotButton) }

    /** Whether the last bind included the analysis stream; null before the first setting is seen. */
    /** Analysis stream and session layout of the last bind; a change needs a rebind. */
    private var boundStreams: Triple<Boolean, StreamConfig?, OemFeature?>? = null

    private fun boundStreamsKey(config: CameraConfiguration?) = Triple(
        viewModel.wantsAnalysisStream,
        (config as? CameraConfiguration.Photo)?.let { viewModel.desiredStreamConfig(it) },
        config?.let { viewModel.bridgedOemFeature(it) },
    )
    private val previewBlurView by lazy { findViewById<PreviewBlurView>(R.id.previewBlurView) }
    private val proButton by lazy { findViewById<ImageButton>(R.id.proButton) }
    private val screenFlashView by lazy { findViewById<ScreenFlashView>(R.id.screenFlashView) }
    private val secondaryBottomBarLayout by lazy { findViewById<ConstraintLayout>(R.id.secondaryBottomBarLayout) }
    private val secondaryTopBarLayout by lazy { findViewById<HorizontalScrollView>(R.id.secondaryTopBarLayout) }
    private val lookButton by lazy { findViewById<Button>(R.id.lookButton) }
    private val aiPortraitButton by lazy { findViewById<Button>(R.id.aiPortraitButton) }
    private val aiBokehView by lazy { findViewById<ImageView>(R.id.aiBokehView) }
    private val aiCompositionOverlay by lazy { findViewById<CompositionOverlayView>(R.id.aiCompositionOverlay) }
    private val aiSceneChip by lazy { findViewById<TextView>(R.id.aiSceneChip) }
    private lateinit var aiController: AiController
    private var bestShotRunning = false
    private val lookPickerView by lazy { findViewById<LookPickerView>(R.id.lookPickerView) }
    private val settingsButton by lazy { findViewById<Button>(R.id.settingsButton) }
    private val shutterButton by lazy { findViewById<ImageButton>(R.id.shutterButton) }
    private val timerButton by lazy { findViewById<Button>(R.id.timerButton) }
    private val videoFrameRateButton by lazy { findViewById<Button>(R.id.videoFrameRateButton) }
    private val videoQualityButton by lazy { findViewById<Button>(R.id.videoQualityButton) }
    private val videoRecordingStateButton by lazy { findViewById<ImageButton>(R.id.videoRecordingStateButton) }
    private val videoDynamicRangeButton by lazy { findViewById<Button>(R.id.videoDynamicRangeButton) }
    private val viewFinder by lazy { findViewById<PreviewView>(R.id.viewFinder) }
    private val viewFinderFocus by lazy { findViewById<ImageView>(R.id.viewFinderFocus) }
    private val zoomLevel by lazy { findViewById<HorizontalSlider>(R.id.zoomLevel) }

    // System services
    private val keyguardManager by lazy { getSystemService(KeyguardManager::class.java) }

    // Skinned Compose overlay; null or inactive means the classic View layout is in use.
    private val uiState = MutableStateFlow(CameraUiState())
    private var overlay: OverlayHost? = null
    private val overlayActive get() = overlay?.active == true
    private val skinRepository by lazy { SkinRepository.get(this) }

    /**
     * The currently shown URI.
     */
    private var galleryButtonUri: Uri? = null

    /**
     * Medias captured from secure activity will be stored here
     * NOTE: Order is important, the first element is the newest one.
     */
    private val secureMediaUris = ArrayDeque<Uri>()

    // QR
    // Inflating the Material bottom sheet costs a few hundred ms; it is built when a code is found.
    private val qrBottomSheetDialog by lazy {
        QrBottomSheetDialog(this).apply { setOnDismissListener { viewModel.onQrDialogDismissed() } }
    }
    private val proModeUi by lazy {
        ProModeUi(
            this,
            viewModel,
            (application as ApertureApplication).preferencesRepository,
            mainLayout,
            viewFinder,
            R.id.proPanelAnchor,
        )
    }
    private val isGoogleLensAvailable by lazy { GoogleLensUtils.isGoogleLensAvailable(this) }

    private var viewFinderTouchEvent: MotionEvent? = null
    private val gestureDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                viewFinderTouchEvent = e
                return false
            }

            override fun onFling(
                e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float
            ): Boolean {

                return e1?.let {
                    if (!handler.hasMessages(MSG_ON_PINCH_TO_ZOOM) &&
                        abs(it.x - e2.x) > 75 * resources.displayMetrics.density
                    ) {
                        if (overlayActive) {
                            overlaySwipe(forward = (e2.x < it.x) != isRtl)
                        } else if (e2.x > it.x) {
                            // Left to right
                            viewModel.previousCameraMode()
                        } else {
                            // Right to left
                            viewModel.nextCameraMode()
                        }
                    }
                    true
                } ?: false
            }
        })
    }
    private val zoomGestureDetector by lazy {
        ZoomGestureDetector(this) {
            when (it) {
                is ZoomGestureDetector.ZoomEvent.Begin -> {
                    zoomGestureDetectorIsInProgress = true
                }

                is ZoomGestureDetector.ZoomEvent.Move -> {
                    viewModel.cameraController.onPinchToZoom(it.incrementalScaleFactor)
                    handler.removeMessages(MSG_ON_PINCH_TO_ZOOM)
                    handler.sendMessageDelayed(handler.obtainMessage(MSG_ON_PINCH_TO_ZOOM), 500)
                }

                is ZoomGestureDetector.ZoomEvent.End -> {
                    zoomGestureDetectorIsInProgress = false
                }
            }
            true
        }
    }
    private var zoomGestureDetectorIsInProgress = false

    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            super.handleMessage(msg)
            when (msg.what) {
                MSG_HIDE_ZOOM_SLIDER -> {
                    zoomLevel.isVisible = false
                }

                MSG_HIDE_FOCUS_RING -> {
                    viewFinderFocus.isVisible = false
                }

                MSG_HIDE_EXPOSURE_SLIDER -> {
                    exposureLevel.isVisible = false
                }
            }
        }
    }

    // Permissions
    private val permissionsManager = PermissionsManager(this)

    private val locationPermissionsDialog by lazy {
        LocationPermissionsDialog(this).also {
            it.onResultCallback = { result ->
                if (result) {
                    lifecycleScope.launch {
                        val permissionState = permissionsManager.requestPermission(
                            Permission.LOCATION
                        )
                        viewModel.setSaveLocation(permissionState == PermissionState.GRANTED)
                    }
                } else {
                    viewModel.setSaveLocation(false)
                }
            }
        }
    }

    private val forceTorchSnackbar by lazy {
        Snackbar.make(
            secondaryBottomBarLayout, R.string.force_torch_help, Snackbar.LENGTH_INDEFINITE
        )
            .setAnchorView(secondaryBottomBarLayout)
            .setAction(android.R.string.ok) {
                viewModel.setForceModeHelpShown(true)
            }
    }

    enum class ShutterAnimation(val resourceId: Int) {
        InitPhoto(R.drawable.avd_photo_capture),
        InitVideo(R.drawable.avd_mode_video_photo),

        PhotoCapture(R.drawable.avd_photo_capture),
        PhotoToVideo(R.drawable.avd_mode_photo_video),

        VideoToPhoto(R.drawable.avd_mode_video_photo),
        VideoStart(R.drawable.avd_video_start),
        VideoEnd(R.drawable.avd_video_end),
    }

    enum class VideoRecordingStateAnimation(val resourceId: Int) {
        Init(R.drawable.avd_video_recording_pause),
        ResumeToPause(R.drawable.avd_video_recording_pause),
        PauseToResume(R.drawable.avd_video_recording_resume),
    }

    private val intentActions = mapOf(
        // Intents
        MediaStore.ACTION_IMAGE_CAPTURE to {
            viewModel.initialCameraMode = CameraMode.PHOTO
            viewModel.inSingleCaptureMode.value = true
        },
        MediaStore.ACTION_IMAGE_CAPTURE_SECURE to {
            viewModel.initialCameraMode = CameraMode.PHOTO
            viewModel.inSingleCaptureMode.value = true
        },
        MediaStore.ACTION_VIDEO_CAPTURE to {
            viewModel.initialCameraMode = CameraMode.VIDEO
            viewModel.inSingleCaptureMode.value = true
        },
        MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA to {
            viewModel.initialCameraMode = CameraMode.PHOTO
        },
        MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE to {
            viewModel.initialCameraMode = CameraMode.PHOTO
        },
        MediaStore.INTENT_ACTION_VIDEO_CAMERA to {
            viewModel.initialCameraMode = CameraMode.VIDEO
        },

        // Shortcuts
        ShortcutsUtils.SHORTCUT_ID_SELFIE to {
            viewModel.initialCameraMode = CameraMode.PHOTO
            viewModel.initialCameraFacing = CameraFacing.FRONT
        },
        ShortcutsUtils.SHORTCUT_ID_VIDEO to {
            viewModel.initialCameraMode = CameraMode.VIDEO
            viewModel.initialCameraFacing = CameraFacing.BACK
        },
        ShortcutsUtils.SHORTCUT_ID_QR to {
            viewModel.initialCameraMode = CameraMode.QR
        },
    )
    private val assistantIntent
        get() = AssistantIntent.fromIntent(intent)
    private val launchedViaVoiceIntent
        get() = isVoiceInteractionRoot && intent.hasCategory(Intent.CATEGORY_VOICE)

    @Suppress("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge
        enableEdgeToEdge()

        // Hide the status bars
        window.updateBarsVisibility(
            WindowInsetsControllerCompat.BEHAVIOR_DEFAULT,
            statusBars = false,
        )

        // Force dark navigation bar
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightNavigationBars = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1
            && keyguardManager.isKeyguardLocked
        ) {
            setShowWhenLocked(true)

            @Suppress("SourceLockedOrientationActivity")
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        // Register shortcuts
        ShortcutsUtils.registerShortcuts(this)

        // Initialize initial camera mode
        overrideInitialCameraMode()?.let {
            viewModel.initialCameraMode = it
        }

        // Handle intent
        intent.action?.let {
            intentActions[it]?.invoke()
        }

        // Handle assistant intent
        assistantIntent?.useFrontCamera?.let {
            viewModel.initialCameraFacing = if (it) {
                CameraFacing.FRONT
            } else {
                CameraFacing.BACK
            }
        }

        // Setup window insets
        ViewCompat.setOnApplyWindowInsetsListener(mainLayout) { _, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

            cameraModeSelectorLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = insets.bottom
                leftMargin = insets.left
                rightMargin = insets.right
            }

            capturePreviewLayout.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = insets.bottom
                leftMargin = insets.left
                rightMargin = insets.right
            }

            val cutout = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
            uiState.update { it.copy(insetTop = maxOf(insets.top, cutout.top), insetBottom = insets.bottom) }
            if (overlayActive) layoutPreviewForSkin(skinRepository.skin.value)

            windowInsets
        }

        proModeUi.attach()

        // Set secondary top bar button callbacks
        aspectRatioButton.setOnClickListener { viewModel.cyclePhotoAspectRatio() }
        videoQualityButton.setOnClickListener { viewModel.cycleVideoQuality() }
        videoFrameRateButton.setOnClickListener { viewModel.cycleVideoFrameRate() }
        videoDynamicRangeButton.setOnClickListener { viewModel.cycleVideoDynamicRange() }
        effectButton.setOnClickListener { viewModel.cycleExtensionMode() }
        gridButton.setOnClickListener { viewModel.cycleGridMode() }
        timerButton.setOnClickListener { viewModel.toggleTimerMode() }
        multiFrameButton.setOnClickListener { viewModel.multiFrame.cycleSetting() }
        creativeButton.setOnClickListener { viewModel.creative.cycleMode() }
        motionPhotoButton.setOnClickListener {
            viewModel.motionPhoto.setMotionPhoto(!viewModel.motionPhoto.motionPhoto.value)
        }
        preShotButton.setOnClickListener { viewModel.motionPhoto.setPreShot(!viewModel.motionPhoto.preShot.value) }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) = viewModel.multiFrame.motionMeter.start()
            override fun onPause(owner: LifecycleOwner) {
                viewModel.multiFrame.motionMeter.stop()
                dev.opencamera.app.ui.overlay.RecomposeCounts.dump("camera paused")
            }
        })
        micButton.setOnClickListener { viewModel.toggleVideoMicrophoneEnabled() }
        settingsButton.setOnClickListener { openSettings() }
        setupLookPicker()
        setupAi()
        setupOverlay()

        // Set secondary bottom bar button callbacks
        proButton.setOnClickListener {
            secondaryTopBarLayout.slide()
        }
        googleLensButton.setOnClickListener {
            dismissKeyguardAndRun {
                GoogleLensUtils.launchGoogleLens(this)
            }
        }
        flashButton.setOnClickListener { viewModel.cycleFlashMode(false) }
        flashButton.setOnLongClickListener { viewModel.cycleFlashMode(true) }

        // Observe manual focus
        viewFinder.setOnTouchListener { _, event ->
            if (zoomGestureDetector.onTouchEvent(event) && zoomGestureDetectorIsInProgress) {
                return@setOnTouchListener true
            }
            return@setOnTouchListener gestureDetector.onTouchEvent(event)
        }
        viewFinder.setOnClickListener {
            // Reset exposure level to 0 EV
            viewModel.setExposureCompensationLevel(0.5f)

            exposureLevel.isVisible = true
            handler.removeMessages(MSG_HIDE_EXPOSURE_SLIDER)
            handler.sendMessageDelayed(handler.obtainMessage(MSG_HIDE_EXPOSURE_SLIDER), 2000)

            secondaryTopBarLayout.slideDown()
        }

        // Observe preview stream state
        viewFinder.previewStreamState.observe(this) {
            when (it) {
                PreviewView.StreamState.STREAMING -> {
                    if (!firstPreviewLogged) {
                        firstPreviewLogged = true
                        Log.i(PERF_TAG, "first preview ${SystemClock.uptimeMillis() - android.os.Process.getStartUptimeMillis()} ms after process start")
                    }
                    // Show grid
                    gridView.alpha = 1f
                    gridView.previewView = viewFinder

                    // Cross-fade from the frozen frame to the live preview
                    previewBlurView.reveal()

                    // Issue capture if requested via assistant
                    if ((launchedViaVoiceIntent || assistantIntent?.cameraOpenOnly != null)
                        && assistantIntent?.cameraOpenOnly != true
                    ) {
                        shutterButton.performClick()
                    }
                }

                else -> {}
            }
        }

        zoomLevel.onProgressChangedByUser = {
            viewModel.cameraController.setLinearZoom(it)
        }
        zoomLevel.textFormatter = {
            "%.1fx".format(viewModel.zoomState.value?.zoomRatio)
        }

        // Set expose level callback & text formatter
        exposureLevel.onProgressChangedByUser = {
            viewModel.setExposureCompensationLevel(it)

            handler.removeMessages(MSG_HIDE_EXPOSURE_SLIDER)
            handler.sendMessageDelayed(handler.obtainMessage(MSG_HIDE_EXPOSURE_SLIDER), 2000)
        }

        // Set primary bar button callbacks
        flipCameraButton.setOnClickListener { viewModel.flipCamera() }

        videoRecordingStateButton.setOnClickListener {
            viewModel.onVideoRecordingStateButtonPress()
        }

        shutterButton.setOnClickListener {
            uiState.update { it.copy(shutterPresses = it.shutterPresses + 1) }

            // Shutter animation
            when (viewModel.cameraMode.value) {
                CameraMode.PHOTO -> startShutterAnimation(ShutterAnimation.PhotoCapture)
                CameraMode.VIDEO -> {
                    if (countDownView.cancelCountDown()) {
                        viewModel.cameraState.value = CameraState.IDLE
                        startShutterAnimation(ShutterAnimation.VideoEnd)
                        return@setOnClickListener
                    }
                    if (viewModel.cameraState.value == CameraState.IDLE) {
                        startShutterAnimation(ShutterAnimation.VideoStart)
                    }
                }

                else -> {}
            }

            startTimerAndRun {
                when (viewModel.cameraMode.value) {
                    CameraMode.PHOTO -> if (!takeBestShot()) viewModel.takePhoto()
                    CameraMode.VIDEO -> viewModel.captureVideo()
                    else -> {}
                }
            }
        }

        galleryButtonCardView.setOnClickListener { openGallery() }

        // Set lens switching callback
        lensSelectorLayout.onCameraChangeCallback = {
            viewModel.selectCamera(it)
        }
        lensSelectorLayout.onZoomRatioChangeCallback = {
            viewModel.smoothZoom(it)
        }
        lensSelectorLayout.onResetZoomRatioCallback = {
            viewModel.resetZoom()
        }
        lensSelectorLayout.onLensChipCallback = { chip, camera ->
            viewModel.selectLensChip(chip, camera)
        }

        // Set capture preview callback
        capturePreviewLayout.onChoiceCallback = { input ->
            when (input) {
                null -> {
                    capturePreviewLayout.isVisible = false
                }

                is InputStream,
                is Uri -> sendIntentResultAndExit(input)

                else -> throw Exception("Invalid input")
            }
        }

        // Set mode selector callback
        cameraModeSelectorLayout.onModeSelectedCallback = {
            viewModel.setCameraMode(it)
        }
        cameraModeSelectorLayout.onMoreModesCallback = {
            dismissKeyguardAndRun {
                startActivity(Intent(this, dev.opencamera.app.video.VideoModesActivity::class.java))
            }
        }

        // Bind viewfinder and preview blur view
        previewBlurView.previewView = viewFinder
        onBackPressedDispatcher.addCallback(this, creativeBack)

        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                permissionsManager.withPermissionGranted(Permission.CAMERA) {
                    loadData()
                }
            }
        }

        // Also collect location
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                permissionsManager.withPermissionGranted(Permission.LOCATION) {
                    viewModel.location.collect()
                }
            }
        }

        // Check for permissions
        lifecycleScope.launch {
            permissionsManager.permissionStateFlow(
                Permission.CAMERA
            ).collectLatest { permissionState ->
                if (permissionState != PermissionState.GRANTED) {
                    when (permissionsManager.requestPermission(Permission.CAMERA)) {
                        PermissionState.GRANTED -> {
                            // This is a good time to ask the user for location permissions
                            if (viewModel.saveLocation.value == null) {
                                locationPermissionsDialog.show()
                            }
                        }

                        else -> {
                            Toast.makeText(
                                this@CameraActivity,
                                R.string.app_permissions_toast,
                                Toast.LENGTH_LONG,
                            ).show()
                            finish()
                        }
                    }
                }
            }
        }

        // Initialize stuff after camera permissions are granted
        lifecycleScope.launch {
            queueSetupWithCameraPermissions()
        }
    }

    override fun onDestroy() {
        overlay?.destroy()
        viewModel.multiFrame.previewZoomCompensation = null

        // Detach CameraController from ScreenFlashView
        screenFlashView.setController(null)

        // Detach CameraController from PreviewView
        viewFinder.controller = null

        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?) = when (capturePreviewLayout.isVisible) {
        true -> super.onKeyDown(keyCode, event)
        false -> handleHardwareKeyDown(keyCode, event) ?: super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?) = when (capturePreviewLayout.isVisible) {
        true -> super.onKeyUp(keyCode, event)
        false -> handleHardwareKeyUp(keyCode, event) ?: super.onKeyUp(keyCode, event)
    }

    /**
     * This is a method that can be overridden to set the initial camera mode and facing.
     * It's gonna have priority over shared preferences and intents.
     */
    protected open fun overrideInitialCameraMode(): CameraMode? = null

    private fun CoroutineScope.loadData() {
        launch {
            viewModel.event.collect { event ->
                when (event) {
                    is Event.NoCamera -> noCamera()

                    is Event.ShowForceTorchHelp -> if (!forceTorchSnackbar.isShownOrQueued) {
                        forceTorchSnackbar.show()
                    }

                    is Event.FlipCameraAnimation ->
                        (flipCameraButton.drawable as AnimatedVectorDrawable).start()

                    is Event.PhotoCaptureStatus -> when (event) {
                        is Event.PhotoCaptureStatus.CaptureStarted -> {
                            viewFinder.foreground = ColorDrawable(Color.BLACK)
                            ValueAnimator.ofInt(0, 255, 0).apply {
                                addUpdateListener { anim ->
                                    viewFinder.foreground.alpha = anim.animatedValue as Int
                                }
                            }.start()
                        }

                        is Event.PhotoCaptureStatus.ImageSaved -> {
                            if (!viewModel.inSingleCaptureMode.value) {
                                onCapturedMedia(event.output.savedUri)
                            } else {
                                event.output.savedUri?.let {
                                    openCapturePreview(it, MediaType.PHOTO)
                                }
                                event.photoOutputStream?.use {
                                    openCapturePreview(
                                        ByteArrayInputStream(
                                            event.photoOutputStream.toByteArray()
                                        )
                                    )
                                }
                            }
                        }

                        is Event.PhotoCaptureStatus.Error -> {
                            // Do nothing
                        }
                    }
                }
            }
        }

        launch {
            viewModel.cameraConfiguration.collectLatest { cameraConfiguration ->
                // Let the first bind carry the preview look instead of rebinding when it loads.
                viewModel.lookController.awaitPreload(LOOK_PRELOAD_WAIT_MS)
                // Let the mode change draw its frame first; the bind (CameraX must be configured on
                // the main thread) then runs in a frame of its own.
                viewModel.cameraState.first { it == CameraState.IDLE }
                kotlinx.coroutines.android.awaitFrame()
                bindCameraUseCases(cameraConfiguration)
            }
        }

        launch { collectRebindRequests() }

        launch {
            viewModel.cameraMode.collectLatest { cameraMode ->
                // Hide secondary top bar
                secondaryTopBarLayout.isVisible = false

                // Update secondary top bar buttons
                aspectRatioButton.isVisible = cameraMode != CameraMode.VIDEO
                videoQualityButton.isVisible = cameraMode == CameraMode.VIDEO
                videoFrameRateButton.isVisible = cameraMode == CameraMode.VIDEO
                videoDynamicRangeButton.isVisible = cameraMode == CameraMode.VIDEO
                micButton.isVisible = cameraMode == CameraMode.VIDEO

                // Update secondary bottom bar buttons
                proButton.isVisible = cameraMode != CameraMode.QR
                googleLensButton.isVisible = cameraMode == CameraMode.QR && isGoogleLensAvailable

                // Update primary bar buttons
                shutterButton.isInvisible = cameraMode == CameraMode.QR

                // Update camera mode selector
                cameraModeSelectorLayout.setCurrentCameraMode(cameraMode)
            }
        }

        launch {
            viewModel.cameraModeTransition.collectLatest { (oldCameraMode, newCameraMode) ->
                when (oldCameraMode) {
                    CameraMode.PHOTO -> when (newCameraMode) {
                        CameraMode.VIDEO -> startShutterAnimation(ShutterAnimation.PhotoToVideo)
                        else -> startShutterAnimation(ShutterAnimation.InitPhoto)
                    }

                    CameraMode.VIDEO -> when (newCameraMode) {
                        CameraMode.PHOTO -> startShutterAnimation(ShutterAnimation.VideoToPhoto)
                        else -> startShutterAnimation(ShutterAnimation.InitVideo)
                    }

                    CameraMode.QR, null -> when (newCameraMode) {
                        CameraMode.PHOTO -> startShutterAnimation(ShutterAnimation.InitPhoto)

                        CameraMode.VIDEO -> startShutterAnimation(ShutterAnimation.InitVideo)

                        CameraMode.QR -> {}
                    }
                }
            }
        }

        launch {
            combine(viewModel.lenses, viewModel.lensChips, ::Pair).collectLatest {
                    (lenses, chips) ->
                val (activeCamera, availableCameras) = lenses
                // Update lens selector: device profile stops when available, else the camera list
                activeCamera?.let {
                    if (chips.isNotEmpty() && chips.any { (_, camera) -> camera == it }) {
                        lensSelectorLayout.setChips(it, chips)
                    } else {
                        lensSelectorLayout.setCamera(
                            it,
                            availableCameras,
                        )
                    }
                }
            }
        }

        launch {
            viewModel.cameraXCameraState.collect { cameraXCameraState ->
                cameraXCameraState.error?.let {
                    // Log the error
                    Log.e(
                        LOG_TAG,
                        "Error: code: ${it.code}, type: ${it.type}",
                        it.cause
                    )

                    val showToast = { stringId: Int ->
                        Toast.makeText(
                            this@CameraActivity,
                            stringId,
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    when (it.code) {
                        CameraXCameraState.ERROR_MAX_CAMERAS_IN_USE -> {
                            // No way to fix it without user action, bail out
                            showToast(R.string.error_max_cameras_in_use)
                            finish()
                        }

                        CameraXCameraState.ERROR_CAMERA_IN_USE -> {
                            // No way to fix it without user action, bail out
                            showToast(R.string.error_camera_in_use)
                            finish()
                        }

                        CameraXCameraState.ERROR_OTHER_RECOVERABLE_ERROR -> {
                            // Warn the user and don't do anything
                            showToast(R.string.error_other_recoverable_error)
                        }

                        CameraXCameraState.ERROR_STREAM_CONFIG -> {
                            // CameraX use case misconfiguration, no way to recover
                            showToast(R.string.error_stream_config)
                            finish()
                        }

                        CameraXCameraState.ERROR_CAMERA_DISABLED -> {
                            // No way to fix it without user action, bail out
                            showToast(R.string.error_camera_disabled)
                            finish()
                        }

                        CameraXCameraState.ERROR_CAMERA_FATAL_ERROR -> {
                            // No way to fix it without user action, bail out
                            showToast(R.string.error_camera_fatal_error)
                            finish()
                        }

                        CameraXCameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED -> {
                            // No way to fix it without user action, bail out
                            showToast(R.string.error_do_not_disturb_mode_enabled)
                            finish()
                        }

                        else -> {
                            // We know anything about it, just check if it's recoverable or
                            // critical
                            when (it.type) {
                                CameraXCameraState.ErrorType.RECOVERABLE -> {
                                    showToast(R.string.error_unknown_recoverable)
                                }

                                CameraXCameraState.ErrorType.CRITICAL -> {
                                    showToast(R.string.error_unknown_critical)
                                    finish()
                                }

                                null -> {
                                    // Do nothing
                                }
                            }
                        }
                    }
                }
            }
        }

        launch {
            viewModel.inSingleCaptureMode.collectLatest { inSingleCaptureMode ->
                // Update primary bar buttons
                galleryButtonCardView.isInvisible = inSingleCaptureMode

                // Update camera mode selector
                cameraModeSelectorLayout.setInSingleCaptureMode(inSingleCaptureMode)
            }
        }

        launch {
            viewModel.cameraState.collectLatest { cameraState ->
                // Update secondary bar buttons
                timerButton.isEnabled = cameraState == CameraState.IDLE
                aspectRatioButton.isEnabled = cameraState == CameraState.IDLE
                effectButton.isEnabled = cameraState == CameraState.IDLE
                settingsButton.isEnabled = cameraState == CameraState.IDLE

                lensSelectorLayout.setCameraState(cameraState)

                // Update primary bar buttons
                galleryButtonCardView.isEnabled = cameraState == CameraState.IDLE
                // Shutter button must stay enabled
                flipCameraButton.isEnabled = cameraState == CameraState.IDLE
                videoRecordingStateButton.isVisible = cameraState.isRecordingVideo

                // Update camera mode selector
                cameraModeSelectorLayout.setCameraState(cameraState)
            }
        }

        launch {
            viewModel.screenRotation.collectLatest { screenRotation ->
                val compensationValue = screenRotation.compensationValue.toFloat()

                // Rotate sliders
                exposureLevel.screenRotation = screenRotation
                zoomLevel.screenRotation = screenRotation

                // Rotate info chip
                islandView.setScreenRotation(screenRotation)

                // Rotate secondary top bar buttons
                ConstraintLayout::class.safeCast(
                    secondaryTopBarLayout.getChildAt(0)
                )?.let { layout ->
                    for (child in layout.children) {
                        Button::class.safeCast(child)?.let {
                            it.smoothRotate(compensationValue)
                            ValueAnimator.ofFloat(
                                (it.layoutParams as ConstraintLayout.LayoutParams).verticalBias,
                                when (screenRotation) {
                                    Rotation.ROTATION_0 -> 0.0f
                                    Rotation.ROTATION_180 -> 1.0f
                                    Rotation.ROTATION_90,
                                    Rotation.ROTATION_270 -> 0.5f
                                }
                            ).apply {
                                addUpdateListener { anim ->
                                    it.updateLayoutParams<ConstraintLayout.LayoutParams> {
                                        verticalBias = anim.animatedValue as Float
                                    }
                                }
                            }.start()
                        }
                    }
                }

                // Rotate secondary bottom bar buttons
                proButton.smoothRotate(compensationValue)
                lensSelectorLayout.setScreenRotation(screenRotation)
                flashButton.smoothRotate(compensationValue)

                // Rotate primary bar buttons
                galleryButtonCardView.smoothRotate(compensationValue)
                shutterButton.smoothRotate(compensationValue)
                flipCameraButton.smoothRotate(compensationValue)

                // Rotate capture preview
                capturePreviewLayout.setScreenRotation(screenRotation)

                // Rotate count down view
                countDownView.setScreenRotation(screenRotation)
            }
        }

        launch {
            viewModel.capturedMedia.collectLatest { capturedMedia ->
                updateGalleryButton(capturedMedia.firstOrNull(), false)
            }
        }

        launch {
            viewModel.supportedFlashModes.collectLatest { supportedFlashModes ->
                // Hide the button if the only available mode is off,
                // we want the user to know if any other mode is being used
                flashButton.isVisible = supportedFlashModes.size != 1
                        || supportedFlashModes.first() != FlashMode.OFF
            }
        }

        launch {
            viewModel.flashMode.collectLatest { flashMode ->
                // Update secondary bar buttons
                flashButton.setImageResource(
                    when (flashMode) {
                        FlashMode.OFF -> R.drawable.ic_flash_off
                        FlashMode.AUTO -> R.drawable.ic_flash_auto
                        FlashMode.ON -> R.drawable.ic_flash_on
                        FlashMode.TORCH -> R.drawable.ic_flashlight_on
                        FlashMode.SCREEN -> R.drawable.ic_flash_screen
                    }
                )
            }
        }

        launch {
            viewModel.isFlashButtonEnabled.collectLatest { isFlashButtonEnabled ->
                flashButton.isEnabled = isFlashButtonEnabled
            }
        }

        launch {
            viewModel.gridMode.collectLatest { gridMode ->
                gridView.mode = gridMode

                // Update secondary bar buttons
                gridButton.setCompoundDrawablesWithIntrinsicBounds(
                    0,
                    when (gridMode) {
                        GridMode.OFF -> R.drawable.ic_grid_3x3_off
                        GridMode.ON_3 -> R.drawable.ic_grid_3x3
                        GridMode.ON_4 -> R.drawable.ic_grid_4x4
                        GridMode.ON_GOLDEN_RATIO -> R.drawable.ic_grid_goldenratio
                        GridMode.DIAGONAL -> R.drawable.ic_grid_diagonal
                        GridMode.SQUARE -> R.drawable.ic_grid_square
                    },
                    0,
                    0
                )
                gridButton.setText(
                    when (gridMode) {
                        GridMode.OFF -> R.string.grid_off
                        GridMode.ON_3 -> R.string.grid_on_3
                        GridMode.ON_4 -> R.string.grid_on_4
                        GridMode.ON_GOLDEN_RATIO -> R.string.grid_on_goldenratio
                        GridMode.DIAGONAL -> R.string.grid_on_diagonal
                        GridMode.SQUARE -> R.string.grid_on_square
                    }
                )
            }
        }

        launch {
            viewModel.multiFrame.setting.collectLatest { setting ->
                multiFrameButton.setCompoundDrawablesWithIntrinsicBounds(
                    0,
                    when (setting) {
                        MultiFrameSetting.OFF -> R.drawable.ic_hdr_off
                        MultiFrameSetting.HDR_AUTO -> R.drawable.ic_hdr_auto
                        MultiFrameSetting.HDR_ON -> R.drawable.ic_hdr_on
                        MultiFrameSetting.NIGHT -> R.drawable.ic_clear_night
                        MultiFrameSetting.ASTRO -> R.drawable.ic_astro
                    },
                    0,
                    0
                )
                multiFrameButton.setText(
                    when (setting) {
                        MultiFrameSetting.OFF -> R.string.multi_frame_off
                        MultiFrameSetting.HDR_AUTO -> R.string.multi_frame_hdr_auto
                        MultiFrameSetting.HDR_ON -> R.string.multi_frame_hdr_on
                        MultiFrameSetting.NIGHT -> R.string.multi_frame_night
                        MultiFrameSetting.ASTRO -> R.string.multi_frame_astro
                    }
                )

                rebindIfAnalysisChanged()
            }
        }

        launch {
            viewModel.creative.mode.collectLatest { mode ->
                creativeBack.isEnabled = mode != CreativeMode.OFF
                creativeButton.setText(mode.label)
                creativeButton.isActivated = mode != CreativeMode.OFF
                rebindIfAnalysisChanged()
            }
        }

        launch {
            viewModel.motionPhoto.motionPhoto.collectLatest { on ->
                motionPhotoButton.setText(if (on) R.string.motion_photo_on else R.string.motion_photo_off)
                rebindIfAnalysisChanged()
            }
        }

        launch {
            viewModel.motionPhoto.preShot.collectLatest { on ->
                preShotButton.setText(if (on) R.string.pre_shot_on else R.string.pre_shot_off)
                rebindIfAnalysisChanged()
            }
        }

        launch {
            viewModel.flashMode.collectLatest { rebindIfAnalysisChanged() }
        }

        launch {
            viewModel.multiFrame.configCap.collectLatest { rebindIfAnalysisChanged() }
        }

        launch {
            viewModel.fullResNotice.collect { res ->
                Toast.makeText(this@CameraActivity, res, Toast.LENGTH_SHORT).show()
                rebindIfAnalysisChanged()
            }
        }

        launch {
            viewModel.pendingChangeNotice.collect {
                Toast.makeText(this@CameraActivity, R.string.camera_change_pending, Toast.LENGTH_SHORT).show()
            }
        }

        launch {
            viewModel.creative.messages.collect { res ->
                Toast.makeText(this@CameraActivity, res, Toast.LENGTH_LONG).show()
            }
        }

        launch {
            var offered = false
            viewModel.creative.moonSeen.collectLatest { seen ->
                if (!seen || offered || viewModel.cameraMode.value != CameraMode.PHOTO) return@collectLatest
                offered = true
                Snackbar.make(mainLayout, R.string.creative_moon_suggestion, Snackbar.LENGTH_LONG)
                    .setAction(R.string.creative_moon) { viewModel.creative.setMode(CreativeMode.MOON) }
                    .show()
                viewModel.creative.clearMoonSuggestion()
            }
        }

        launch {
            viewModel.multiFrame.progress.collectLatest { progress ->
                multiFrameProgress.isVisible = progress != null
                progress?.let { multiFrameProgress.setProgressCompat((it * 100).toInt(), true) }
            }
        }

        launch {
            var astroOffered = false
            while (true) {
                delay(1000)
                if (astroOffered || viewModel.cameraMode.value != CameraMode.PHOTO) continue
                val setting = viewModel.multiFrame.setting.value
                if (setting == MultiFrameSetting.ASTRO || setting == MultiFrameSetting.OFF) continue
                if (!viewModel.multiFrame.astroSuggested()) continue
                astroOffered = true
                Snackbar.make(mainLayout, R.string.multi_frame_astro_suggestion, Snackbar.LENGTH_LONG)
                    .setAction(R.string.multi_frame_astro) {
                        // One shot only: a minutes-long Astro capture must not stay on by accident.
                        val previous = setting
                        viewModel.multiFrame.setSetting(MultiFrameSetting.ASTRO)
                        lifecycleScope.launch {
                            viewModel.cameraState.first { it == CameraState.TAKING_PHOTO }
                            viewModel.cameraState.first { it == CameraState.IDLE }
                            if (viewModel.multiFrame.setting.value == MultiFrameSetting.ASTRO) {
                                viewModel.multiFrame.setSetting(previous)
                            }
                        }
                    }
                    .show()
            }
        }

        launch {
            viewModel.timerMode.collectLatest { timerMode ->                // Update secondary bar buttons
                timerButton.setCompoundDrawablesWithIntrinsicBounds(
                    0,
                    when (timerMode) {
                        TimerMode.OFF -> R.drawable.ic_timer_off
                        TimerMode.ON_3S -> R.drawable.ic_timer_3_alt_1
                        TimerMode.ON_10S -> R.drawable.ic_timer_10_alt_1
                    },
                    0,
                    0
                )
                timerButton.setText(
                    when (timerMode) {
                        TimerMode.OFF -> R.string.timer_off
                        TimerMode.ON_3S -> R.string.timer_3
                        TimerMode.ON_10S -> R.string.timer_10
                    }
                )
            }
        }

        launch {
            viewModel.levelerEnabled.collectLatest { levelerEnabled ->
                assistController.levelEnabled = levelerEnabled
            }
        }

        launch {
            viewModel.fullScreenBrightness.collectLatest { fullScreenBrightness ->
                window.attributes = window.attributes.apply {
                    screenBrightness = when (fullScreenBrightness) {
                        true -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
                        false -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    }
                }
            }
        }

        launch {
            viewModel.thermalStatus.collectLatest { thermalStatus ->
                val showSnackBar = { stringId: Int ->
                    Snackbar.make(
                        secondaryBottomBarLayout,
                        stringId,
                        Snackbar.LENGTH_INDEFINITE
                    )
                        .setAnchorView(secondaryBottomBarLayout)
                        .setAction(android.R.string.ok) {
                            // Do nothing
                        }
                        .show()
                }

                when (thermalStatus) {
                    ThermalStatus.NONE,
                    ThermalStatus.LIGHT -> Unit

                    ThermalStatus.MODERATE -> {
                        showSnackBar(R.string.thermal_status_moderate)
                    }

                    ThermalStatus.SEVERE -> {
                        showSnackBar(R.string.thermal_status_severe)
                    }

                    ThermalStatus.CRITICAL -> {
                        showSnackBar(R.string.thermal_status_critical)
                    }

                    ThermalStatus.EMERGENCY -> {
                        showSnackBar(R.string.thermal_status_emergency)
                        finish()
                    }

                    ThermalStatus.SHUTDOWN -> {
                        showSnackBar(R.string.thermal_status_shutdown)
                        finish()
                    }
                }
            }
        }

        launch {
            viewModel.zoomState.collectLatest { zoomState ->
                zoomState?.takeIf { it.minZoomRatio != it.maxZoomRatio }?.let {
                    zoomLevel.progress = it.linearZoom
                    zoomLevel.isVisible = !overlayActive

                    handler.removeMessages(MSG_HIDE_ZOOM_SLIDER)
                    handler.sendMessageDelayed(handler.obtainMessage(MSG_HIDE_ZOOM_SLIDER), 2000)

                    lensSelectorLayout.onZoomRatioChanged(it.zoomRatio)
                }
            }
        }

        launch {
            viewModel.tapToFocusInfoState.collectLatest { tapToFocusInfoState ->
                when (tapToFocusInfoState.focusState) {
                    CameraController.TAP_TO_FOCUS_STARTED -> {
                        // The tap point is in viewfinder coordinates; skins may inset the viewfinder.
                        viewFinderFocus.x = viewFinder.left +
                            tapToFocusInfoState.tapPoint!!.x - (viewFinderFocus.width / 2)
                        viewFinderFocus.y = viewFinder.top +
                            tapToFocusInfoState.tapPoint!!.y - (viewFinderFocus.height / 2)
                        viewFinderFocus.isVisible = true
                        handler.removeMessages(MSG_HIDE_FOCUS_RING)
                        ValueAnimator.ofInt(0.px, 8.px).apply {
                            addUpdateListener { anim ->
                                viewFinderFocus.setPadding(anim.animatedValue as Int)
                            }
                        }.start()
                    }

                    else -> {
                        handler.removeMessages(MSG_HIDE_FOCUS_RING)
                        ValueAnimator.ofInt(8.px, 0.px).apply {
                            addUpdateListener { anim ->
                                viewFinderFocus.setPadding(anim.animatedValue as Int)
                            }
                        }.start()

                        handler.sendMessageDelayed(handler.obtainMessage(MSG_HIDE_FOCUS_RING), 500)
                    }
                }
            }
        }

        launch {
            viewModel.exposureCompensationRangeToLevel.collectLatest { (range, level) ->
                exposureLevel.steps = range.endInclusive - range.start
                exposureLevel.progress = level
                exposureLevel.textFormatter = {
                    val ev = Int.mapToRange(range, it)
                    when (ev == 0) {
                        true -> "0"
                        false -> EXPOSURE_LEVEL_FORMATTER.format(ev).toString()
                    }
                }
            }
        }

        launch {
            viewModel.isShutterButtonEnabled.collectLatest { isShutterButtonEnabled ->
                shutterButton.isEnabled = isShutterButtonEnabled
            }
        }

        launch {
            viewModel.photoAspectRatio.collectLatest { photoAspectRatio ->
                // Update secondary bar buttons
                aspectRatioButton.setText(
                    when (photoAspectRatio) {
                        AspectRatio.RATIO_4_3 -> R.string.aspect_ratio_4_3
                        AspectRatio.RATIO_16_9 -> R.string.aspect_ratio_16_9
                        else -> throw Exception("Unknown aspect ratio $photoAspectRatio")
                    }
                )
            }
        }

        launch {
            viewModel.photoEffect.collectLatest { photoEffect ->
                // Update secondary bar buttons
                effectButton.setCompoundDrawablesWithIntrinsicBounds(
                    0,
                    when (photoEffect) {
                        ExtensionMode.NONE -> R.drawable.ic_blur_off
                        ExtensionMode.BOKEH -> R.drawable.ic_effect_bokeh
                        ExtensionMode.HDR -> R.drawable.ic_hdr_on
                        ExtensionMode.NIGHT -> R.drawable.ic_clear_night
                        ExtensionMode.FACE_RETOUCH -> R.drawable.ic_face_retouching_natural
                        ExtensionMode.AUTO -> R.drawable.ic_hdr_auto
                        else -> R.drawable.ic_blur_off
                    },
                    0,
                    0
                )
                effectButton.setText(
                    when (photoEffect) {
                        ExtensionMode.NONE -> R.string.effect_none
                        ExtensionMode.BOKEH -> R.string.effect_bokeh
                        ExtensionMode.HDR -> R.string.effect_hdr
                        ExtensionMode.NIGHT -> R.string.effect_night
                        ExtensionMode.FACE_RETOUCH -> R.string.effect_face_retouch
                        ExtensionMode.AUTO -> R.string.effect_auto
                        else -> R.string.effect_none
                    }
                )
            }
        }

        launch {
            viewModel.isPhotoEffectButtonVisible.collectLatest { isPhotoEffectButtonVisible ->
                effectButton.isVisible = isPhotoEffectButtonVisible
            }
        }

        launch {
            viewModel.videoQuality.collectLatest { videoQuality ->
                // Update secondary bar buttons
                videoQualityButton.setCompoundDrawablesWithIntrinsicBounds(
                    0,
                    when (videoQuality) {
                        Quality.SD -> R.drawable.ic_sd
                        Quality.HD -> R.drawable.ic_hd
                        Quality.FHD -> R.drawable.ic_full_hd
                        Quality.QHD -> R.drawable.ic_qhd
                        Quality.UHD -> R.drawable.ic_4k
                        else -> throw Exception("Unknown video quality $videoQuality")
                    },
                    0,
                    0
                )
                videoQualityButton.setText(
                    when (videoQuality) {
                        Quality.SD -> R.string.video_quality_sd
                        Quality.HD -> R.string.video_quality_hd
                        Quality.FHD -> R.string.video_quality_fhd
                        Quality.QHD -> R.string.video_quality_qhd
                        Quality.UHD -> R.string.video_quality_uhd
                        else -> throw Exception("Unknown video quality $videoQuality")
                    }
                )
            }
        }

        launch {
            viewModel.isVideoQualityButtonEnabled.collectLatest { isVideoQualityButtonEnabled ->
                videoQualityButton.isEnabled = isVideoQualityButtonEnabled
            }
        }

        launch {
            viewModel.videoFrameRate.collectLatest { videoFrameRate ->
                // Update secondary bar buttons
                videoFrameRateButton.text = videoFrameRate?.let {
                    resources.getString(R.string.video_framerate_value, it.value)
                } ?: resources.getString(R.string.video_framerate_auto)
            }
        }

        launch {
            viewModel.isVideoFrameRateButtonEnabled.collectLatest { isVideoFrameRateButtonEnabled ->
                videoFrameRateButton.isEnabled = isVideoFrameRateButtonEnabled
            }
        }

        launch {
            viewModel.videoDynamicRange.collectLatest { videoDynamicRange ->
                // Update secondary bar buttons
                videoDynamicRangeButton.setCompoundDrawablesWithIntrinsicBounds(
                    0,
                    when (videoDynamicRange) {
                        VideoDynamicRange.SDR -> R.drawable.ic_hdr_off
                        VideoDynamicRange.HLG_10_BIT -> R.drawable.ic_hdr_on
                        VideoDynamicRange.HDR10_10_BIT -> R.drawable.ic_hdr_on
                        VideoDynamicRange.HDR10_PLUS_10_BIT -> R.drawable.ic_hdr_on
                        VideoDynamicRange.DOLBY_VISION_10_BIT -> R.drawable.ic_dolby
                        VideoDynamicRange.DOLBY_VISION_8_BIT -> R.drawable.ic_dolby
                    },
                    0,
                    0,
                )
                videoDynamicRangeButton.setText(
                    when (videoDynamicRange) {
                        VideoDynamicRange.SDR -> R.string.video_dynamic_range_sdr
                        VideoDynamicRange.HLG_10_BIT -> R.string.video_dynamic_range_hlg_10_bit
                        VideoDynamicRange.HDR10_10_BIT -> R.string.video_dynamic_range_hdr10_10_bit
                        VideoDynamicRange.HDR10_PLUS_10_BIT -> R.string.video_dynamic_range_hdr10_plus_10_bit
                        VideoDynamicRange.DOLBY_VISION_10_BIT -> R.string.video_dynamic_range_dolby_vision_10_bit
                        VideoDynamicRange.DOLBY_VISION_8_BIT -> R.string.video_dynamic_range_dolby_vision_8_bit
                    }
                )
            }
        }

        launch {
            viewModel.isVideoDynamicRangeButtonEnabled.collectLatest { isVideoDynamicRangeButtonEnabled ->
                videoDynamicRangeButton.isEnabled = isVideoDynamicRangeButtonEnabled
            }
        }

        launch {
            viewModel.videoMicMode.collectLatest { videoMicMode ->
                micButton.setCompoundDrawablesWithIntrinsicBounds(
                    0,
                    when (videoMicMode) {
                        true -> R.drawable.ic_mic_on
                        false -> R.drawable.ic_mic_off
                    },
                    0,
                    0
                )
                micButton.setText(
                    when (videoMicMode) {
                        true -> R.string.mic_on
                        false -> R.string.mic_off
                    }
                )
            }
        }

        launch {
            viewModel.isVideoMicButtonEnabled.collectLatest { isVideoMicButtonEnabled ->
                micButton.isEnabled = isVideoMicButtonEnabled
            }
        }

        launch {
            viewModel.videoRecordingDuration.collectLatest { videoRecordingDuration ->
                cameraModeSelectorLayout.setVideoRecordingDuration(videoRecordingDuration)
            }
        }

        launch {
            viewModel.videoRecordEvent.collect { videoRecordEvent ->
                when (videoRecordEvent) {
                    is VideoRecordEvent.Start -> runOnUiThread {
                        uiState.update { it.copy(recordingPaused = false) }
                        startVideoRecordingStateAnimation(
                            VideoRecordingStateAnimation.Init
                        )
                    }

                    is VideoRecordEvent.Pause -> runOnUiThread {
                        uiState.update { it.copy(recordingPaused = true) }
                        startVideoRecordingStateAnimation(
                            VideoRecordingStateAnimation.ResumeToPause
                        )
                    }

                    is VideoRecordEvent.Resume -> runOnUiThread {
                        uiState.update { it.copy(recordingPaused = false) }
                        startVideoRecordingStateAnimation(
                            VideoRecordingStateAnimation.PauseToResume
                        )
                    }

                    is VideoRecordEvent.Status -> {
                        // Do nothing
                    }

                    is VideoRecordEvent.Finalize -> {
                        runOnUiThread {
                            startShutterAnimation(ShutterAnimation.VideoEnd)
                        }
                        if (videoRecordEvent.error != VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA) {
                            if (!viewModel.inSingleCaptureMode.value) {
                                onCapturedMedia(videoRecordEvent.outputResults.outputUri)
                            } else {
                                openCapturePreview(
                                    videoRecordEvent.outputResults.outputUri,
                                    MediaType.VIDEO,
                                )
                            }
                        }
                    }
                }
            }
        }

        launch {
            viewModel.qrResult.collectLatest { qrResult ->
                qrBottomSheetDialog.setQrResult(qrResult)
            }
        }

        launch {
            viewModel.canFlipCamera.collectLatest { canFlipCamera ->
                flipCameraButton.isInvisible = !canFlipCamera
            }
        }

        launch {
            viewModel.islandItems.collectLatest { islandItems ->
                islandView.setItems(islandItems)
            }
        }
    }

    private suspend fun queueSetupWithCameraPermissions() {
        var initialized = false

        permissionsManager.permissionStateFlow(Permission.CAMERA).collect { permissionState ->
            if (permissionState == PermissionState.GRANTED && !initialized) {
                // Attach CameraController to PreviewView
                viewFinder.controller = viewModel.cameraController
                viewModel.lookController.refresh()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val app = applicationContext
                    lifecycleScope.launch(Dispatchers.IO) { ProcessingService.resumeIfPending(app) }
                }

                // Attach CameraController to ScreenFlashView
                screenFlashView.setController(viewModel.cameraController)
                screenFlashView.setScreenFlashWindow(window)

                val videoRecordingAvailable = viewModel.isVideoRecordingAvailable()
                if (viewModel.initialCameraMode == CameraMode.VIDEO && !videoRecordingAvailable) {
                    // If an app asked for a video we have to bail out
                    if (viewModel.inSingleCaptureMode.value) {
                        Toast.makeText(
                            this@CameraActivity,
                            R.string.camcorder_unsupported_toast,
                            Toast.LENGTH_LONG,
                        ).show()
                        finish()
                        return@collect
                    }
                    // Fallback to photo mode
                    viewModel.initialCameraMode = CameraMode.PHOTO
                }

                if (!viewModel.initializeCameraConfiguration()) {
                    noCamera()
                }

                initialized = true
            }
        }
    }

    private fun startShutterAnimation(shutterAnimation: ShutterAnimation) {
        // Get appropriate drawable
        val drawable = ContextCompat.getDrawable(
            this, shutterAnimation.resourceId
        ) as AnimatedVectorDrawable

        // Update current drawable
        shutterButton.setImageDrawable(drawable)

        // Start or reset animation
        when (shutterAnimation) {
            ShutterAnimation.InitPhoto,
            ShutterAnimation.InitVideo -> drawable.reset()

            else -> drawable.start()
        }
    }

    private fun startVideoRecordingStateAnimation(animation: VideoRecordingStateAnimation) {
        // Get appropriate drawable
        val drawable = ContextCompat.getDrawable(
            this, animation.resourceId
        ) as AnimatedVectorDrawable

        // Update current drawable
        videoRecordingStateButton.setImageDrawable(drawable)

        // Start or reset animation
        when (animation) {
            VideoRecordingStateAnimation.Init -> drawable.reset()
            else -> drawable.start()
        }
    }

    /**
     * Rebind cameraProvider use cases
     */
    /**
     * Binds [useCases] through [FeatureGuard.bindChain]: optional streams are dropped when the
     * camera has no supported surface combination for them (seen on Snapdragon 8 Elite with
     * Preview + JPEG + full-res YUV). Never throws; the last resort is the baseline use case.
     * Returns false when nothing could be bound.
     */
    private fun bindWithFallback(useCases: Int, quiet: Boolean = false): Boolean {
        val controller = viewModel.cameraController
        val primary = (useCases and (CameraController.IMAGE_CAPTURE or CameraController.VIDEO_CAPTURE))
            .takeIf { it != 0 } ?: useCases
        val chain = FeatureGuard.bindChain(
            useCases, primary, CameraController.IMAGE_ANALYSIS, CameraController.IMAGE_CAPTURE,
        )
        for (step in chain) {
            if (step.dropEffect) viewModel.lookController.disablePreviewEffect()
            val bound = FeatureGuard.attempt(LOG_TAG, "Binding use cases ") {
                controller.setEnabledUseCases(step.useCases)
                controller.bindToLifecycle(this)
            } != null
            if (!bound) continue
            if (step.useCases and CameraController.IMAGE_ANALYSIS == 0) {
                viewModel.multiFrame.analyzer.resetStream()
            }
            val notice = when (step.dropped) {
                FeatureGuard.Dropped.NONE -> null
                FeatureGuard.Dropped.MULTI_FRAME -> R.string.stream_fallback_multi_frame
                FeatureGuard.Dropped.LOOK_PREVIEW -> R.string.stream_fallback_look
                FeatureGuard.Dropped.BASELINE -> R.string.stream_fallback_baseline
            }
            notice?.let {
                Log.w(LOG_TAG, "Bound with fallback ")
                Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
            }
            return true
        }
        Log.e(LOG_TAG, "No use case combination could be bound")
        if (!quiet) Toast.makeText(this, R.string.stream_fallback_failed, Toast.LENGTH_LONG).show()
        return false
    }

    /**
     * The analysis stream is only bound while a multi-frame, creative or motion feature needs it,
     * and the session layout follows the active mode, flash, fallback cap and the phone's
     * processing the vendor bridge stands in with.
     */
    private fun rebindIfAnalysisChanged() {
        rebindRequests.tryEmit(Unit)
    }

    private val rebindRequests = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(
        extraBufferCapacity = 1, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    /**
     * Toggles only post a request; quick taps collapse into one check after [REBIND_DEBOUNCE_MS],
     * and the camera is rebound only when the stream layout really changed. A change made while
     * a capture runs is kept and applied once the camera is idle again.
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private suspend fun collectRebindRequests() {
        rebindRequests.debounce(REBIND_DEBOUNCE_MS).collectLatest {
            if (boundStreams == null) return@collectLatest
            val first = viewModel.cameraConfiguration.replayCache.lastOrNull() ?: return@collectLatest
            if (boundStreamsKey(first) == boundStreams) return@collectLatest
            if (viewModel.cameraState.value != CameraState.IDLE) {
                Toast.makeText(this, R.string.camera_change_pending, Toast.LENGTH_SHORT).show()
                viewModel.cameraState.first { it == CameraState.IDLE }
            }
            kotlinx.coroutines.android.awaitFrame()
            val config = viewModel.cameraConfiguration.replayCache.lastOrNull() ?: return@collectLatest
            if (boundStreamsKey(config) != boundStreams) bindCameraUseCases(config, "streams")
        }
    }

    /**
     * Use cases of the full-resolution photo session: preview + maximum YUV (no JPEG stream),
     * or preview + RAW_SENSOR + a small YUV stream for metering. The analysis use case carries
     * a capture callback for per-frame exposure when the controller allows it.
     */
    private fun fullResUseCases(cfg: CameraConfiguration.Photo, config: StreamConfig): Int {
        val controller = viewModel.cameraController
        val mf = viewModel.multiFrame
        val aspect = AspectRatioStrategy(cfg.photoAspectRatio, AspectRatioStrategy.FALLBACK_RULE_AUTO)
        val selector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(aspect)
            .setResolutionStrategy(
                if (config == StreamConfig.FULL_RES_RAW) {
                    ResolutionStrategy(METERING_STREAM_SIZE, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                } else {
                    ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY
                }
            )
            .build()
        if (config == StreamConfig.FULL_RES_RAW) controller.imageOutputFormat = ImageCapture.OUTPUT_FORMAT_RAW
        controller.imageAnalysisResolutionSelector = selector
        controller.setImageAnalysisAnalyzer(mf.analysisExecutor, mf.analyzer)
        CameraControllerInterop.attachAnalysisCaptureCallback(
            controller, selector, mf.analysisExecutor, mf.analyzer, mf.resultTracker,
        )
        return if (config == StreamConfig.FULL_RES_RAW) {
            CameraController.IMAGE_CAPTURE or CameraController.IMAGE_ANALYSIS
        } else {
            CameraController.IMAGE_ANALYSIS
        }
    }

    /**
     * After a full-resolution bind: the YUV stream must really be full size (some HALs shrink
     * it), and the RAW session needs a RAW size. Otherwise the next layout of the chain is bound.
     */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun verifyFullResStream(cfg: CameraConfiguration, config: StreamConfig) {
        lifecycleScope.launch {
            val ok = FeatureGuard.attempt(LOG_TAG, "Checking the $config stream") {
                viewModel.cameraController.initializationFuture.await()
                val info = viewModel.cameraController.cameraInfo ?: return@attempt false
                if (config == StreamConfig.FULL_RES_RAW) {
                    val map = Camera2CameraInfo.from(info)
                        .getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    val size = map?.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width * it.height }
                    viewModel.multiFrame.rawSize = size
                    size != null
                } else {
                    val size = withTimeoutOrNull(FULL_RES_STREAM_TIMEOUT_MS) {
                        while (viewModel.multiFrame.analyzer.frameSize == null) delay(50)
                        viewModel.multiFrame.analyzer.frameSize
                    }
                    Log.i(LOG_TAG, "Full-resolution YUV stream: $size")
                    size != null && size.width.toLong() * size.height >= FULL_RES_MIN_PIXELS
                }
            } ?: false
            if (ok && viewModel.multiFrame.streamConfig == config) {
                // The controller may have rebuilt the analysis use case after the bind.
                delay(CAPTURE_RESULT_WAIT_MS)
                if (viewModel.multiFrame.resultTracker.latest == null &&
                    viewModel.cameraState.value == CameraState.IDLE &&
                    CameraControllerInterop.ensureAttached(viewModel.cameraController)
                ) {
                    delay(CAPTURE_RESULT_WAIT_MS)
                }
                Log.i(LOG_TAG, "Analysis capture results: ${viewModel.multiFrame.resultTracker.latest != null}")
            }
            if (ok || viewModel.multiFrame.streamConfig != config) return@launch
            viewModel.multiFrame.demote(config)
            Toast.makeText(this@CameraActivity, R.string.full_res_fallback, Toast.LENGTH_SHORT).show()
            if (viewModel.cameraState.value == CameraState.IDLE) bindCameraUseCases(cfg)
        }
    }

    /**
     * Last resort around the bind: a configuration that cannot be bound (unsupported mode,
     * format or extension) reopens the camera with plain preview + JPEG capture.
     */
    private var lastBridgedOemFeature: OemFeature? = null
    private var lastBoundCamera: dev.opencamera.app.models.Camera? = null
    private var firstPreviewLogged = false
    private var previewLayoutKey: Any? = null

    private fun bindCameraUseCases(cameraConfiguration: CameraConfiguration, reason: String = "config") {
        if (viewModel.cameraState.value != CameraState.IDLE) {
            // Not a bind failure: the change is retried once the capture is done.
            Log.i(LOG_TAG, "Bind deferred until idle ($reason)")
            rebindIfAnalysisChanged()
            return
        }
        val start = SystemClock.elapsedRealtimeNanos()
        try {
            bindCameraUseCasesWithFallback(cameraConfiguration)
        } finally {
            // Video binds too, so a pending stream check after a mode switch does not bind again.
            boundStreams = boundStreamsKey(cameraConfiguration)
            Log.i(PERF_TAG, "bind $reason ${(SystemClock.elapsedRealtimeNanos() - start) / 1_000_000} ms")
        }
    }

    private fun bindCameraUseCasesWithFallback(cameraConfiguration: CameraConfiguration) {
        try {
            bindCameraUseCasesOrThrow(cameraConfiguration)
        } catch (e: RuntimeException) {
            // The phone's processing chosen by the vendor bridge failed: use the open pipeline
            val oemFeature = viewModel.bridgedOemFeature(cameraConfiguration)
            if (oemFeature != null) {
                Log.e(LOG_TAG, "OEM ${oemFeature.key} bind failed, retrying without it", e)
                viewModel.vendorBridge.markOemFailed(oemFeature)
                Toast.makeText(this, R.string.oem_processing_failed, Toast.LENGTH_SHORT).show()
                FeatureGuard.attempt(LOG_TAG, "Bind without OEM processing") {
                    bindCameraUseCasesOrThrow(cameraConfiguration)
                }?.let { return }
            }
            Log.e(LOG_TAG, "Bind failed for $cameraConfiguration, reopening in baseline mode", e)
            viewModel.lookController.disablePreviewEffect()
            viewModel.multiFrame.analyzer.resetStream()
            viewModel.multiFrame.streamConfig = StreamConfig.STANDARD
            FeatureGuard.attempt(LOG_TAG, "Baseline bind") {
                viewModel.cameraController.imageOutputFormat = ImageCapture.OUTPUT_FORMAT_JPEG
                viewModel.cameraController.cameraSelector = CameraSelector.Builder()
                    .requireLensFacing(
                        when (cameraConfiguration.camera.cameraFacing) {
                            CameraFacing.FRONT -> CameraSelector.LENS_FACING_FRONT
                            else -> CameraSelector.LENS_FACING_BACK
                        }
                    )
                    .build()
                viewModel.cameraController.setEnabledUseCases(CameraController.IMAGE_CAPTURE)
                viewModel.cameraController.bindToLifecycle(this)
            }
            Toast.makeText(this, R.string.stream_fallback_baseline, Toast.LENGTH_LONG).show()
        }
    }

    private fun bindCameraUseCasesOrThrow(cameraConfiguration: CameraConfiguration) {
        require(viewModel.cameraState.value == CameraState.IDLE) {
            "Trying to rebind camera while camera is not in IDLE state"
        }

        // Show blurred preview; a switch to another lens of the same side shows the frame sharp
        // at the new field of view instead.
        val previous = lastBoundCamera
        val next = cameraConfiguration.camera
        val lensScale = if (
            previous != null && previous.cameraId != next.cameraId &&
            previous.cameraFacing == next.cameraFacing &&
            previous.intrinsicZoomRatio > 0f && next.intrinsicZoomRatio > 0f
        ) next.intrinsicZoomRatio / previous.intrinsicZoomRatio else 1f
        lastBoundCamera = next
        previewBlurView.freeze(lensScale)
        previewBlurView.isVisible = true

        // Unbind previous use cases
        viewModel.cameraController.unbind()
        viewModel.multiFrame.streamConfig = StreamConfig.STANDARD

        // Hide grid until preview is ready
        gridView.alpha = 0f

        // If the current camera doesn't support the selected camera mode, give up
        require(cameraConfiguration.camera.supportsCameraMode(cameraConfiguration.cameraMode)) {
            "Selected camera mode (${
                cameraConfiguration.cameraMode
            }) not supported by camera ${cameraConfiguration.camera.cameraId}"
        }

        // The user's effect, or the phone's processing picked by the vendor bridge
        val extensionMode = viewModel.resolveExtensionMode(cameraConfiguration)
        val bridgedOemFeature = viewModel.bridgedOemFeature(cameraConfiguration)
        viewModel.oemProcessingLabel = oemFeatureOf(extensionMode)?.let {
            SourcePolicy.label(ProcessingSource.PHONE, it.label)
        }

        // Make sure the extension mode is supported
        require(cameraConfiguration.camera.supportsExtensionMode(extensionMode)) {
            "Selected extension mode ($extensionMode) not supported by camera ${
                cameraConfiguration.camera.cameraId
            }"
        }

        // Initialize the use case we want and set its properties
        val cameraUseCases = when (cameraConfiguration) {
            is CameraConfiguration.Photo -> {
                require(
                    cameraConfiguration.photoCaptureMode != ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG
                            || cameraConfiguration.camera.supportsZsl
                ) {
                    "Requested ZSL capture mode when the camera doesn't support it"
                }

                require(
                    cameraConfiguration.camera.supportedPhotoOutputFormats.contains(
                        cameraConfiguration.photoOutputFormat
                    )
                ) {
                    "Selected photo output format (${
                        cameraConfiguration.photoOutputFormat
                    } not supported by camera ${cameraConfiguration.camera.cameraId})"
                }

                viewModel.cameraController.imageCaptureMode = cameraConfiguration.photoCaptureMode

                viewModel.cameraController.imageOutputFormat = when (
                    cameraConfiguration.photoOutputFormat
                ) {
                    PhotoOutputFormat.JPEG -> ImageCapture.OUTPUT_FORMAT_JPEG
                    PhotoOutputFormat.JPEG_ULTRA_HDR -> ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR
                    PhotoOutputFormat.RAW -> ImageCapture.OUTPUT_FORMAT_RAW
                    PhotoOutputFormat.RAW_JPEG -> ImageCapture.OUTPUT_FORMAT_RAW_JPEG
                }

                viewModel.cameraController.imageCaptureResolutionSelector =
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(
                            AspectRatioStrategy(
                                cameraConfiguration.photoAspectRatio,
                                AspectRatioStrategy.FALLBACK_RULE_AUTO,
                            )
                        )
                        .setAllowedResolutionMode(
                            if (cameraConfiguration.enableHighResolution) {
                                ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE
                            } else {
                                ResolutionSelector.PREFER_CAPTURE_RATE_OVER_HIGHER_RESOLUTION
                            }
                        )
                        .build()

                viewModel.multiFrame.analyzer.resetStream()
                viewModel.motionPhoto.reset()
                viewModel.multiFrame.resultTracker.clear()
                val streamConfig = viewModel.desiredStreamConfig(cameraConfiguration)
                boundStreams = boundStreamsKey(cameraConfiguration)
                viewModel.multiFrame.streamConfig = streamConfig
                if (streamConfig != StreamConfig.STANDARD) {
                    fullResUseCases(cameraConfiguration, streamConfig)
                } else if (viewModel.wantsAnalysisStream &&
                    cameraConfiguration.photoOutputFormat == PhotoOutputFormat.JPEG &&
                    extensionMode == ExtensionMode.NONE &&
                    cameraConfiguration.camera.cameraFacing != CameraFacing.FRONT
                ) {
                    // Full resolution YUV stream for multi-frame bursts
                    viewModel.cameraController.imageAnalysisResolutionSelector =
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(
                                AspectRatioStrategy(
                                    cameraConfiguration.photoAspectRatio,
                                    AspectRatioStrategy.FALLBACK_RULE_AUTO,
                                )
                            )
                            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                            .build()
                    viewModel.cameraController.setImageAnalysisAnalyzer(
                        viewModel.multiFrame.analysisExecutor, viewModel.multiFrame.analyzer
                    )
                    CameraController.IMAGE_CAPTURE or CameraController.IMAGE_ANALYSIS
                } else {
                    CameraController.IMAGE_CAPTURE
                }
            }

            is CameraConfiguration.Video -> {
                // Check whether or not the video quality is supported
                val videoQualityInfo = cameraConfiguration.camera.supportedVideoQualities[
                    cameraConfiguration.videoQuality
                ] ?: error("Video quality not supported by the camera")

                require(
                    videoQualityInfo.supportedFrameRates.contains(
                        cameraConfiguration.videoFrameRate
                    )
                ) {
                    "Video frame rate not supported with the requested video quality"
                }

                require(
                    videoQualityInfo.supportedDynamicRanges.contains(
                        cameraConfiguration.videoDynamicRange
                    )
                ) {
                    "Video dynamic range not supported with the requested video quality"
                }

                // Set the quality
                viewModel.cameraController.videoCaptureQualitySelector =
                    QualitySelector.from(cameraConfiguration.videoQuality)

                // Set the dynamic range
                viewModel.cameraController.videoCaptureDynamicRange =
                    cameraConfiguration.videoDynamicRange.dynamicRange

                // Set video mirror mode
                viewModel.cameraController.videoCaptureMirrorMode =
                    when (cameraConfiguration.videoMirrorMode) {
                        VideoMirrorMode.OFF -> MirrorMode.MIRROR_MODE_OFF
                        VideoMirrorMode.ON -> MirrorMode.MIRROR_MODE_ON
                        VideoMirrorMode.ON_FFC_ONLY -> when (
                            cameraConfiguration.camera.cameraFacing
                        ) {
                            CameraFacing.FRONT -> MirrorMode.MIRROR_MODE_ON
                            else -> MirrorMode.MIRROR_MODE_OFF
                        }
                    }

                CameraController.VIDEO_CAPTURE
            }

            is CameraConfiguration.Qr -> {
                viewModel.cameraController.setImageAnalysisAnalyzer(
                    viewModel.cameraExecutor, viewModel.qrImageAnalyzer
                )

                CameraController.IMAGE_ANALYSIS
            }
        }

        // Get the camera selector
        val cameraSelector = viewModel.getExtensionEnabledCameraSelector(
            cameraConfiguration.camera, extensionMode
        )

        // Workaround: We cannot set flash mode to screen with a non front facing camera.
        // VM will set the correct value later on
        if (cameraConfiguration.camera.cameraFacing != CameraFacing.FRONT
            && viewModel.cameraController.flashMode == FlashMode.SCREEN
        ) {
            viewModel.cameraController.flashMode = FlashMode.OFF
        }

        // Bind use cases to camera
        viewModel.cameraController.cameraSelector = cameraSelector
        val fullRes = viewModel.multiFrame.streamConfig
        viewModel.lookController.setRawSession(fullRes == StreamConfig.FULL_RES_RAW)
        if (cameraConfiguration is CameraConfiguration.Photo && fullRes != StreamConfig.STANDARD) {
            viewModel.lookController.attachIfReady()
            val bound = FeatureGuard.attempt(LOG_TAG, "Binding $fullRes") {
                viewModel.cameraController.setEnabledUseCases(cameraUseCases)
                viewModel.cameraController.bindToLifecycle(this)
            } != null
            if (!bound) {
                // Next layout of the chain: RAW -> full-res YUV -> regular JPEG session.
                viewModel.multiFrame.demote(fullRes)
                Toast.makeText(this, R.string.full_res_fallback, Toast.LENGTH_SHORT).show()
                bindCameraUseCasesOrThrow(cameraConfiguration)
                return
            }
            verifyFullResStream(cameraConfiguration, fullRes)
        } else {
            viewModel.lookController.attachIfReady()
            if (!bindWithFallback(cameraUseCases, quiet = bridgedOemFeature != null)) {
                check(bridgedOemFeature == null) { "OEM ${bridgedOemFeature?.key} could not be bound" }
            }
        }
        if (bridgedOemFeature != lastBridgedOemFeature) {
            bridgedOemFeature?.let {
                Toast.makeText(
                    this, getString(R.string.oem_processing_active, it.label), Toast.LENGTH_SHORT
                ).show()
            }
            lastBridgedOemFeature = bridgedOemFeature
        }

        // Wait for camera to be ready
        lifecycleScope.launch {
            FeatureGuard.attempt(LOG_TAG, "Camera initialization") {
                viewModel.cameraController.initializationFuture.await()
                true
            } ?: return@launch

            val camera2CameraControl = viewModel.cameraController.camera2CameraControl ?: run {
                Log.wtf(LOG_TAG, "Camera2CameraControl not available even with camera ready?")
                return@launch
            }

            val camera2Options = cameraConfiguration.camera2Options

            // Set Camera2 CaptureRequest options, plus Pro mode options when active
            val cameraInfo = viewModel.cameraController.cameraInfo ?: run {
                Log.w(LOG_TAG, "Camera ready without CameraInfo, skipping Camera2 options")
                return@launch
            }
            val baseOptions = CaptureRequestOptions.Builder()
                .setFrameRate(
                    when (cameraConfiguration) {
                        is CameraConfiguration.Video -> cameraConfiguration.videoFrameRate
                        else -> null
                    }
                )
                .setVideoStabilizationMode(
                    when (cameraConfiguration) {
                        is CameraConfiguration.Video -> when (
                            cameraConfiguration.enableVideoStabilization
                        ) {
                            true -> VideoStabilizationMode.getMode(cameraConfiguration.camera)
                            false -> null
                        }

                        else -> null
                    } ?: VideoStabilizationMode.OFF
                )
                .setEdgeMode(camera2Options.edgeMode)
                .setNoiseReductionMode(camera2Options.noiseReductionMode)
                .setShadingMode(camera2Options.shadingMode)
                .setColorCorrectionAberrationMode(camera2Options.colorCorrectionAberrationMode)
                .setDistortionCorrectionMode(camera2Options.distortionCorrectionMode)
                .setHotPixelMode(camera2Options.hotPixelMode)
            if (extensionMode == ExtensionMode.NONE) {
                applyVendorTags(camera2CameraControl, cameraConfiguration.camera.cameraId, baseOptions)
            }
            proModeUi.bind(cameraInfo, camera2CameraControl, cameraConfiguration, baseOptions.build())
        }

        // Restore settings that can be set on the fly
        viewModel.setVideoMicrophoneEnabled(viewModel.videoMicMode.value)

        // Reset exposure level
        viewModel.setExposureCompensationLevel(0.5f)
    }

    /**
     * Writes the profile's vendor tags on top of [base]. A tag the framework rejects, or a
     * request that fails, drops the tag for the session; a crash while writing blocks it for
     * later launches (see VendorBridge).
     */
    private suspend fun applyVendorTags(
        control: Camera2CameraControl,
        cameraId: String,
        base: CaptureRequestOptions.Builder,
    ) {
        val bridge = viewModel.vendorBridge
        val tags = withContext(Dispatchers.IO) { viewModel.vendorTagsFor(cameraId) }
        if (tags.isEmpty()) return
        val vendorOptions = CaptureRequestOptions.Builder()
        val accepted = tags.filter { tag ->
            val ok = FeatureGuard.attempt(LOG_TAG, "Vendor tag ${tag.name}") {
                vendorOptions.setCaptureRequestOption(requireNotNull(VendorTagRegistry.requestKey(tag)), tag.value)
            } != null
            if (!ok) bridge.tags.disable(tag.name, "rejected by the framework")
            ok
        }
        if (accepted.isEmpty()) return
        // The crash marker must reach disk (commit) before the tags are sent; off the main thread.
        val names = accepted.map { it.name }
        withContext(Dispatchers.IO) { bridge.beginTagWrite(names) }
        val applied = FeatureGuard.attempt(LOG_TAG, "Vendor tags request") {
            withTimeout(VENDOR_TAG_TIMEOUT_MS) {
                control.addCaptureRequestOptions(vendorOptions.build()).await()
            }
            true
        } != null
        currentCoroutineContext().ensureActive()
        if (applied) {
            accepted.forEach { tag ->
                VendorTagRegistry.requestKey(tag)?.let { base.setCaptureRequestOption(it, tag.value) }
            }
            Log.i(LOG_TAG, "Vendor tags applied on camera $cameraId: ${accepted.map { it.name }}")
        } else {
            accepted.forEach { bridge.tags.disable(it.name, "capture request failed") }
        }
        lifecycleScope.launch {
            delay(VENDOR_TAG_SETTLE_MS)
            bridge.endTagWrite()
        }
    }

    private fun updateGalleryButton(uri: Uri?, fromCapture: Boolean) {
        runOnUiThread {
            val keyguardLocked = keyguardManager.isKeyguardLocked

            galleryButtonIconImageView.setImageResource(
                when (keyguardLocked) {
                    true -> R.drawable.ic_lock
                    false -> R.drawable.ic_image
                }
            )

            if (keyguardLocked && !fromCapture) {
                uiState.update { it.copy(galleryLocked = true) }
                return@runOnUiThread
            }

            galleryButtonUri = uri
            uiState.update {
                it.copy(
                    galleryUri = uri, galleryLocked = keyguardLocked,
                    captures = if (fromCapture) it.captures + 1 else it.captures,
                )
            }

            // When keyguard is unlocked, we want media from MediaStore, else we only trust the
            // ones coming from the capture
            // The skinned overlay draws its own thumbnail; the hidden View one is decoded only
            // when the View layout comes back (showLegacyChrome).
            if (overlayActive) return@runOnUiThread
            uri?.also {
                galleryButtonPreviewImageView.load(uri) {
                    decoderFactory(VideoFrameDecoder.Factory())
                    crossfade(true)
                    scale(Scale.FILL)
                    size(75.px)
                    error(R.drawable.ic_image)
                    fallback(R.drawable.ic_image)
                    listener(
                        onCancel = {
                            galleryButtonPreviewImageView.isVisible = false
                            galleryButtonIconImageView.isVisible = true
                        },
                        onError = { _, _ ->
                            galleryButtonPreviewImageView.isVisible = false
                            galleryButtonIconImageView.isVisible = true
                        },
                        onSuccess = { _, _ ->
                            galleryButtonPreviewImageView.isVisible = true
                            galleryButtonIconImageView.isVisible = false
                        }
                    )
                }
            } ?: run {
                galleryButtonIconImageView.isVisible = true
                galleryButtonPreviewImageView.isVisible = false
            }
        }
    }

    private fun dismissKeyguardAndRun(runnable: () -> Unit) {
        if (!keyguardManager.isKeyguardLocked) {
            runnable()
            return
        }

        keyguardManager.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    super.onDismissSucceeded()
                    runnable()
                }
            }
        )
    }

    private fun openGallery() {
        lifecycleScope.launch {
            // secureMediaUris will be cleared if keyguard is unlocked
            // or none of the items were found.
            withContext(Dispatchers.IO) {
                updateSecureMediaUris(keyguardManager.isKeyguardLocked)
            }

            if (keyguardManager.isKeyguardLocked) {
                // In this state the only thing we can do is launch a secure review intent
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    && secureMediaUris.isNotEmpty()
                ) {
                    val intent = Intent(
                        MediaStore.ACTION_REVIEW_SECURE, secureMediaUris.first()
                    ).apply {
                        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                        if (secureMediaUris.size > 1) {
                            clipData = ClipData.newUri(
                                contentResolver, null, secureMediaUris[1]
                            ).apply {
                                for (i in 2 until secureMediaUris.size) {
                                    addItem(contentResolver, ClipData.Item(secureMediaUris[i]))
                                }
                            }
                        }
                    }
                    runCatching {
                        startActivity(intent)
                        return@launch
                    }
                }
            }

            galleryButtonUri?.also { uri ->
                // Try to open the Uri in the non secure gallery
                dismissKeyguardAndRun {
                    mutableListOf<String>().apply {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            add(MediaStore.ACTION_REVIEW)
                        }
                        add(Intent.ACTION_VIEW)
                    }.forEach {
                        val intent = Intent(it, uri).apply {
                            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                        }
                        runCatching {
                            startActivity(intent)
                            return@dismissKeyguardAndRun
                        }
                    }
                }
            } ?: run {
                // If the Uri is null, attempt to launch non secure-gallery
                dismissKeyguardAndRun {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        type = "image/*"
                    }
                    runCatching {
                        startActivity(intent)
                    }
                }
            }
        }
    }

    private fun openCapturePreview(uri: Uri, mediaType: MediaType) {
        runOnUiThread {
            capturePreviewLayout.updateSource(uri, mediaType)
            capturePreviewLayout.isVisible = true
        }
    }

    private fun openCapturePreview(photoInputStream: InputStream) {
        runOnUiThread {
            capturePreviewLayout.updateSource(photoInputStream)
            capturePreviewLayout.isVisible = true
        }
    }

    /**
     * When the user took a photo or a video and confirmed it, its URI gets sent back to the
     * app that sent the intent and closes the camera.
     */
    private fun sendIntentResultAndExit(input: Any) {
        // The user confirmed the choice
        var outputUri: Uri? = null
        if (intent.extras?.containsKey(MediaStore.EXTRA_OUTPUT) == true) {
            outputUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.extras?.getParcelable(MediaStore.EXTRA_OUTPUT, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.extras?.get(MediaStore.EXTRA_OUTPUT) as Uri
            }
        }

        outputUri?.let {
            try {
                contentResolver.openOutputStream(it, "wt").use { outputStream ->
                    when (input) {
                        is InputStream -> input.use {
                            input.copyTo(outputStream!!)
                        }

                        is Uri -> contentResolver.openInputStream(input).use { inputStream ->
                            inputStream!!.copyTo(outputStream!!)
                        }

                        else -> throw IllegalStateException("Input is not Uri or InputStream")
                    }
                }

                setResult(RESULT_OK)
            } catch (exc: FileNotFoundException) {
                Log.e(LOG_TAG, "Failed to open URI")
                setResult(RESULT_CANCELED)
            }
        } ?: setResult(RESULT_OK, Intent().apply {
            when (input) {
                is InputStream -> {
                    // No output URI provided, so return the photo inline as a downscaled Bitmap.
                    action = "inline-data"
                    val transform = ExifUtils.getTransform(input)
                    val bytes = input.use { it.readBytes() }
                    val bitmap = decodeSampled({ bytes.inputStream() }, SINGLE_CAPTURE_INLINE_MAX_SIDE_LEN_PIXELS)
                        ?: throw IllegalStateException("Cannot decode the captured photo")
                    val scaledAndRotatedBitmap = bitmap.scale(
                        SINGLE_CAPTURE_INLINE_MAX_SIDE_LEN_PIXELS
                    ).transform(transform)
                    putExtra("data", scaledAndRotatedBitmap)
                }

                is Uri -> {
                    // We saved the media (video), so return the URI that we saved.
                    data = input
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    putExtra(MediaStore.EXTRA_OUTPUT, input)
                }

                else -> throw IllegalStateException("Input is not Uri or InputStream")
            }
        })

        finish()
    }

    override fun onResume() {
        super.onResume()
        // Picks up strength changes made in settings.
        if (viewFinder.controller != null) viewModel.lookController.refresh()
        // Colour match may have added or selected a look.
        refreshLookPicker()
    }

    private fun refreshLookPicker() {
        val looks = viewModel.lookController
        val items = listOf(LookPreferences.NONE to getString(R.string.look_none)) +
                looks.repository.descriptors.map { it.id to it.name } +
                (COLOR_MATCH_ITEM to getString(R.string.look_color_match))
        lookPickerView.setLooks(items, looks.lookId.value)
        uiState.update { s -> s.copy(looks = items.map { (id, name) -> LookItem(id, name) }) }
    }

    private fun setupLookPicker() {
        val looks = viewModel.lookController
        refreshLookPicker()
        lookPickerView.onLookSelected = { id ->
            if (id == COLOR_MATCH_ITEM) {
                startActivity(Intent(this, ColorMatchActivity::class.java))
            } else {
                looks.select(id)
            }
        }
        lookButton.setOnClickListener { lookPickerView.isVisible = !lookPickerView.isVisible }
        lifecycleScope.launch {
            looks.lookId.collectLatest { id ->
                lookPickerView.setSelected(id)
                lookButton.text = looks.repository.descriptor(id)?.name ?: getString(R.string.look_button)
                rebindIfAnalysisChanged()
            }
        }
    }

    private fun openSettings() {
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)
    }

    private fun startTimerAndRun(runnable: () -> Unit) {
        // Allow forcing timer if requested by the assistant
        val timerModeSeconds =
            assistantIntent?.timerDurationSeconds ?: viewModel.timerMode.value.seconds

        if (timerModeSeconds <= 0 || !viewModel.canRestartCamera()) {
            runnable()
            return
        }

        val previousCameraState = viewModel.cameraState.value
        viewModel.cameraState.value = CameraState.COUNTDOWN

        countDownView.onPreviewAreaChanged(Rect().apply {
            viewFinder.getGlobalVisibleRect(this)
        })
        countDownView.startCountDown(timerModeSeconds) {
            viewModel.cameraState.value = previousCameraState
            runnable()
        }
    }

    /**
     * Show a toast warning the user that no camera is available and close the activity.
     */
    private fun noCamera() {
        Toast.makeText(
            this, R.string.error_no_cameras_available, Toast.LENGTH_LONG
        ).show()
        finish()
    }

    /**
     * Method called when a new media have been successfully captured and saved.
     * Keep track of media items captured while in a secure lockscreen state so that they
     * can be passed to the gallery for viewing without unlocking the device. However, if the
     * keyguard is no longer locked, clear any existing URIs, and do not add this one.
     */
    private fun onCapturedMedia(item: Uri?) {
        updateGalleryButton(item, true)

        if (!keyguardManager.isKeyguardLocked) {
            secureMediaUris.clear()
        } else {
            item?.let {
                secureMediaUris.addFirst(it)
            }
        }
    }

    /**
     * If keyguard is not locked, remove any URIs that were stored while in the secure camera state.
     * Otherwise, remove any URIs that no longer exist.
     */
    private fun updateSecureMediaUris(keyguardLocked: Boolean) {
        if (!keyguardLocked) {
            secureMediaUris.clear()
        } else {
            secureMediaUris.removeIf { !viewModel.fileExists(it) }
        }
    }

    private fun handleHardwareKeyDown(
        keyCode: Int, event: KeyEvent?
    ) = HardwareKey.match(keyCode)?.let { (hardwareKey, tempIncrease) ->
        val increase = when (viewModel.getHardwareKeyInvert(hardwareKey)) {
            true -> !tempIncrease
            false -> tempIncrease
        }

        val gestureAction = viewModel.getHardwareKeyAction(hardwareKey)

        if (gestureAction.isTwoWayAction && !hardwareKey.isTwoWayKey) {
            Log.wtf(
                LOG_TAG,
                "${gestureAction.name} requires two-way key but ${hardwareKey.name} is not"
            )
            return@let true
        }

        when (gestureAction) {
            GestureAction.SHUTTER -> {
                if (viewModel.cameraMode.value == CameraMode.VIDEO
                    && viewModel.isShutterButtonEnabled.value
                    && event?.repeatCount == 0
                ) {
                    shutterButton.performClick()
                }
                true
            }

            GestureAction.FOCUS -> {
                if (event?.repeatCount == 0) {
                    viewFinderTouchEvent = null
                    viewFinder.performClick()
                }
                true
            }

            GestureAction.MIC_MUTE -> {
                if (viewModel.cameraMode.value == CameraMode.VIDEO && micButton.isEnabled &&
                    event?.repeatCount == 0
                ) {
                    viewModel.toggleVideoMicrophoneEnabled()
                }
                true
            }

            GestureAction.ZOOM -> {
                when (increase) {
                    true -> viewModel.zoomIn()
                    false -> viewModel.zoomOut()
                }
                true
            }

            GestureAction.DEFAULT -> {
                if (hardwareKey.supportsDefault) {
                    super.onKeyDown(keyCode, event)
                } else {
                    Log.wtf(
                        LOG_TAG,
                        "Got GestureAction.DEFAULT for ${hardwareKey.name} which doesn't support it"
                    )
                    true
                }
            }

            GestureAction.NOTHING -> {
                // Do nothing
                true
            }
        }
    }

    private fun handleHardwareKeyUp(
        keyCode: Int, event: KeyEvent?
    ) = HardwareKey.match(keyCode)?.let { (hardwareKey, _) ->
        val gestureAction = viewModel.getHardwareKeyAction(hardwareKey)

        if (gestureAction.isTwoWayAction && !hardwareKey.isTwoWayKey) {
            Log.wtf(
                LOG_TAG,
                "${gestureAction.name} requires two-way key but ${hardwareKey.name} is not"
            )
            return@let true
        }

        when (gestureAction) {
            GestureAction.SHUTTER -> {
                if (viewModel.cameraMode.value != CameraMode.QR
                    && viewModel.isShutterButtonEnabled.value
                ) {
                    shutterButton.performClick()
                }
                true
            }

            GestureAction.FOCUS -> {
                true
            }

            GestureAction.MIC_MUTE -> {
                true
            }

            GestureAction.ZOOM -> {
                true
            }

            GestureAction.DEFAULT -> {
                if (hardwareKey.supportsDefault) {
                    super.onKeyDown(keyCode, event)
                } else {
                    Log.wtf(
                        LOG_TAG,
                        "Got GestureAction.DEFAULT for ${hardwareKey.name} which doesn't support it"
                    )
                    true
                }
            }

            GestureAction.NOTHING -> {
                // Do nothing
                true
            }
        }
    }

    private fun setupAi() {
        assistController = AssistController(this, assistOverlay) {
            viewModel.cameraMode.value == CameraMode.PHOTO
        }
        lifecycle.addObserver(assistController)
        viewModel.multiFrame.previewZoomCompensation = { f ->
            viewFinder.scaleX = f
            viewFinder.scaleY = f
        }
        aiController = AiController(
            this, viewFinder, aiBokehView, aiCompositionOverlay, aiSceneChip,
            isPhotoMode = { viewModel.cameraMode.value == CameraMode.PHOTO && !viewModel.inSingleCaptureMode.value },
            onScenePreset = ::applyScenePreset,
            hintsWanted = { assistController.showHints },
            onComposition = { s, top ->
                assistController.onComposition(s?.subjectX, s?.subjectY, s?.targetX, s?.targetY, top)
            },
        )
        aiController.start()
        viewModel.lookController.captureOps = { aiController.captureOps() }
        updateAiPortraitButton()
        aiPortraitButton.setOnClickListener {
            aiController.settings.portrait = !aiController.settings.portrait
            updateAiPortraitButton()
        }
        lifecycleScope.launch {
            viewModel.cameraMode.collectLatest { aiPortraitButton.isVisible = it == CameraMode.PHOTO }
        }
    }

    private fun updateAiPortraitButton() {
        uiState.update { it.copy(portrait = aiController.settings.portrait) }
        aiPortraitButton.setText(
            if (aiController.settings.portrait) R.string.ai_portrait_on else R.string.ai_portrait_off
        )
    }

    /** Scene chip tap: switch to the preset that suits the detected scene. */
    private fun applyScenePreset(scene: Scene) {
        val message = when (scene) {
            Scene.NIGHT -> {
                viewModel.multiFrame.setSetting(MultiFrameSetting.NIGHT)
                R.string.ai_preset_night
            }
            Scene.PORTRAIT -> {
                aiController.settings.portrait = true
                updateAiPortraitButton()
                R.string.ai_preset_portrait
            }
            Scene.FOOD, Scene.PLANT, Scene.SKY -> {
                viewModel.lookController.select("vivid")
                R.string.ai_preset_vivid
            }
            Scene.DOCUMENT, Scene.TEXT -> {
                viewModel.lookController.select("natural")
                R.string.ai_preset_document
            }
            Scene.NONE -> return
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * Best-shot burst when enabled: several in-memory JPEGs, only the best is kept. Returns
     * false when the regular capture should run instead.
     */
    private fun takeBestShot(): Boolean {
        if (!aiController.settings.bestShot || bestShotRunning) return false
        if (viewModel.inSingleCaptureMode.value || viewModel.cameraState.value != CameraState.IDLE) return false
        if (viewModel.cameraController.imageOutputFormat != ImageCapture.OUTPUT_FORMAT_JPEG) return false
        // Full-res sessions have no JPEG ImageCapture, and creative/multi-frame shots own the shutter.
        if (viewModel.multiFrame.streamConfig != StreamConfig.STANDARD || viewModel.wantsAnalysisStream) return false
        bestShotRunning = true
        viewModel.cameraState.value = CameraState.TAKING_PHOTO
        lifecycleScope.launch {
            val result = try {
                BestShotCapture(this@CameraActivity).capture(
                    viewModel.cameraController, aiController.settings.bestShotFrames,
                )
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Best shot failed", e)
                null
            } finally {
                viewModel.cameraState.value = CameraState.IDLE
                bestShotRunning = false
            }
            if (result == null) {
                Toast.makeText(this@CameraActivity, R.string.ai_best_shot_failed, Toast.LENGTH_SHORT).show()
                viewModel.takePhoto()
                return@launch
            }
            viewModel.lookController.onPhotoSaved(result.uri)
            Toast.makeText(
                this@CameraActivity,
                getString(R.string.ai_best_shot_done, result.chosen + 1, result.frames),
                Toast.LENGTH_SHORT,
            ).show()
        }
        return true
    }
    // ------------------------------------------------------------------ skinned overlay

    private var legacyViewFinderParams: ConstraintLayout.LayoutParams? = null
    private var legacyProgressParams: ConstraintLayout.LayoutParams? = null
    private var legacySceneChipParams: ConstraintLayout.LayoutParams? = null
    private var videoRecordingAvailable = true
    private val isRtl get() = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL

    private val overlayBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            overlay?.ui?.back()
        }
    }

    /** Back leaves a creative mode instead of closing (and later cold-starting) the camera. */
    private val creativeBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            viewModel.creative.setMode(CreativeMode.OFF)
        }
    }

    /**
     * Puts the skinned Compose overlay over the preview. The View controls stay in the layout,
     * hidden, and keep their logic: the overlay mirrors their state and clicks them. When the
     * overlay cannot run (or fails later) the View controls come back.
     */
    private val liveUi = LiveUiValues()

    private fun setupOverlay() {
        val host = OverlayHost(
            context = this,
            lifecycle = lifecycle,
            parent = mainLayout,
            insertIndex = mainLayout.indexOfChild(screenFlashView),
            repo = skinRepository,
            guard = skinRepository.crashGuard,
            content = { ui ->
                val s by uiState.collectAsState()
                androidx.compose.runtime.CompositionLocalProvider(LocalLiveUi provides liveUi) {
                    CameraOverlay(s, overlayActions, ui)
                }
            },
            onSkin = { layoutPreviewForSkin(it) },
            onLegacy = { showLegacyChrome() },
            debugFail = OverlayHost.debugFailure(this, intent.getStringExtra(EXTRA_DEBUG_SKIN_FAIL)),
        )
        overlay = host
        if (!host.start()) {
            overlay = null
            return
        }
        legacyViewFinderParams = ConstraintLayout.LayoutParams(viewFinder.layoutParams as ConstraintLayout.LayoutParams)
        legacyProgressParams = ConstraintLayout.LayoutParams(multiFrameProgress.layoutParams as ConstraintLayout.LayoutParams)
        legacySceneChipParams = ConstraintLayout.LayoutParams(aiSceneChip.layoutParams as ConstraintLayout.LayoutParams)
        setLegacyChromeVisible(false)
        proModeUi.externalUi = true
        layoutPreviewForSkin(skinRepository.skin.value)

        lensSelectorLayout.onButtonsChanged = { syncLensChips() }
        mainLayout.viewTreeObserver.addOnGlobalLayoutListener { if (overlayActive) syncLegacyControls() }
        viewFinder.addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
            if (overlayActive) uiState.update { it.copy(previewRect = RectF(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat())) }
            v.invalidateOutline()
        }
        onBackPressedDispatcher.addCallback(this, overlayBack)
        lifecycleScope.launch {
            androidx.compose.runtime.snapshotFlow { host.ui.consumesBack }.collect { overlayBack.isEnabled = it }
        }
        lifecycleScope.launch {
            videoRecordingAvailable = FeatureGuard.attempt(LOG_TAG, "Video availability") {
                viewModel.isVideoRecordingAvailable()
            } ?: true
            uiState.update { it.derived() }
            // Same probe as the More activity, so the sheet lists only modes it will open.
            withContext(Dispatchers.Default) {
                FeatureGuard.attempt(LOG_TAG, "More modes probe") {
                    val provider = androidx.camera.lifecycle.ProcessCameraProvider.getInstance(this@CameraActivity).await()
                    val info = dev.opencamera.app.video.VideoCapsProbe.cameraInfo(provider, true)
                        ?: dev.opencamera.app.video.VideoCapsProbe.cameraInfo(provider, false)
                    val facts = info?.let { dev.opencamera.app.video.VideoCapsProbe.facts(it) }
                    dev.opencamera.app.video.VideoModes.supported(
                        dev.opencamera.app.video.VideoCapsProbe.caps(this@CameraActivity, provider, facts)
                    )
                }
            }?.let { modes -> uiState.update { it.copy(moreModes = modes) } }
        }
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { bindOverlayState() }
        }
    }

    private fun setLegacyChromeVisible(visible: Boolean) {
        secondaryBottomBarLayout.isVisible = visible
        (findViewById<View>(R.id.primaryBarLayout)).isVisible = visible
        cameraModeSelectorLayout.isVisible = visible
        if (!visible) {
            secondaryTopBarLayout.isVisible = false
            lookPickerView.isVisible = false
            zoomLevel.isVisible = false
        }
    }

    private fun showLegacyChrome() {
        previewLayoutKey = null
        legacyViewFinderParams?.let { viewFinder.layoutParams = it }
        legacyProgressParams?.let { multiFrameProgress.layoutParams = it }
        legacySceneChipParams?.let { aiSceneChip.layoutParams = it }
        viewFinder.clipToOutline = false
        viewFinder.outlineProvider = ViewOutlineProvider.BACKGROUND
        proModeUi.externalUi = false
        overlayBack.isEnabled = false
        setLegacyChromeVisible(true)
        updateGalleryButton(galleryButtonUri, false)
    }

    /** Preview box of the skin: below the top bar, preview-shaped, optionally inset and rounded. */
    private fun layoutPreviewForSkin(tokens: SkinTokens) {
        if (!overlayActive) return
        val density = resources.displayMetrics.density
        val shape = tokens.shape
        val ratio = when (viewModel.cameraMode.value) {
            CameraMode.VIDEO -> "9:16"
            CameraMode.QR -> "3:4"
            CameraMode.PHOTO -> if (viewModel.photoAspectRatio.value == AspectRatio.RATIO_16_9) "9:16" else "3:4"
        }
        // Mode switches call this with the same box most of the time; a relayout costs a frame.
        val key = Triple(tokens, ratio, uiState.value.insetTop)
        if (key == previewLayoutKey) return
        previewLayoutKey = key
        viewFinder.updateLayoutParams<ConstraintLayout.LayoutParams> {
            topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            topToBottom = ConstraintLayout.LayoutParams.UNSET
            bottomToTop = ConstraintLayout.LayoutParams.UNSET
            bottomToBottom = ConstraintLayout.LayoutParams.UNSET
            startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            topMargin = uiState.value.insetTop + (shape.previewTop * density).toInt()
            marginStart = (shape.previewSide * density).toInt()
            marginEnd = (shape.previewSide * density).toInt()
            width = 0
            height = 0
            dimensionRatio = ratio
        }
        multiFrameProgress.updateLayoutParams<ConstraintLayout.LayoutParams> {
            bottomToTop = ConstraintLayout.LayoutParams.UNSET
            bottomToBottom = R.id.viewFinder
        }
        // The overlay owns the bottom of the preview (lens chips, Pro dials, looks).
        aiSceneChip.updateLayoutParams<ConstraintLayout.LayoutParams> {
            bottomToBottom = ConstraintLayout.LayoutParams.UNSET
            topToTop = R.id.viewFinder
            topMargin = (12 * density).toInt()
        }
        val radius = shape.previewRadius * density
        viewFinder.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        viewFinder.clipToOutline = radius > 0f
    }

    private fun overlaySwipe(forward: Boolean) {
        val s = uiState.value

        if (s.cameraState != CameraState.IDLE || s.singleCapture) return
        UiModes.swipe(s.uiMode, forward, s.modes)?.let {
            mainLayout.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            selectUiMode(it)
        }
    }

    private fun CameraUiState.derived(): CameraUiState {
        val pro = pro?.enabled == true && pro.available
        return copy(
            uiMode = UiModes.derive(cameraMode, pro, portrait, multiFrame),
            modes = UiModes.available(
                proAvailable = this.pro?.available == true,
                videoAvailable = videoRecordingAvailable,
                moreAvailable = !singleCapture,
            ),
        )
    }

    /** Night, Portrait and Pro are Photo with one feature on; leaving them turns it off. */
    private fun selectUiMode(mode: UiMode) {
        FeatureGuard.attempt(LOG_TAG, "Mode $mode") {
            val mf = viewModel.multiFrame
            val photo = mode == UiMode.PHOTO || mode == UiMode.NIGHT || mode == UiMode.PORTRAIT || mode == UiMode.PRO
            if (!photo) {
                viewModel.setCameraMode(if (mode == UiMode.VIDEO) CameraMode.VIDEO else CameraMode.QR)
                return@attempt
            }
            if (viewModel.cameraMode.value != CameraMode.PHOTO) viewModel.setCameraMode(CameraMode.PHOTO)
            val portrait = mode == UiMode.PORTRAIT
            if (aiController.settings.portrait != portrait) {
                aiController.settings.portrait = portrait
                updateAiPortraitButton()
            }
            val proOn = mode == UiMode.PRO
            if ((uiState.value.pro?.enabled == true) != proOn) proModeUi.setEnabled(proOn)
            when {
                mode == UiMode.NIGHT -> mf.setSetting(MultiFrameSetting.NIGHT)
                mf.setting.value == MultiFrameSetting.NIGHT -> mf.setSetting(MultiFrameSetting.HDR_AUTO)
            }
        }
    }

    private fun syncLegacyControls() {
        fun Button.mirror() = LegacyControl(text.toString(), isEnabled, isVisible)
        val controls = mapOf(
            Control.ASPECT to aspectRatioButton.mirror(),
            Control.EFFECT to effectButton.mirror(),
            Control.LOOK to lookButton.mirror(),
            Control.VIDEO_QUALITY to videoQualityButton.mirror(),
            Control.VIDEO_FPS to videoFrameRateButton.mirror(),
            Control.VIDEO_DYNAMIC_RANGE to videoDynamicRangeButton.mirror(),
            Control.MIC to micButton.mirror(),
            Control.TIMER to timerButton.mirror(),
            Control.PORTRAIT to aiPortraitButton.mirror(),
            Control.MULTI_FRAME to multiFrameButton.mirror(),
            Control.CREATIVE to creativeButton.mirror(),
            Control.MOTION_PHOTO to motionPhotoButton.mirror(),
            Control.PRE_SHOT to preShotButton.mirror(),
            Control.GRID to gridButton.mirror(),
        )
        uiState.update {
            it.copy(
                controls = controls,
                flashVisible = flashButton.isVisible,
                flashEnabled = flashButton.isEnabled,
                canFlip = !flipCameraButton.isInvisible,
                shutterEnabled = shutterButton.isEnabled,
                googleLens = isGoogleLensAvailable,
            )
        }
    }

    private fun syncLensChips() {
        uiState.update { s -> s.copy(lensChips = lensSelectorLayout.snapshot().map { LensChipUi(it.first, it.second) }) }
    }

    private fun controlButton(control: Control): Button = when (control) {
        Control.ASPECT -> aspectRatioButton
        Control.EFFECT -> effectButton
        Control.LOOK -> lookButton
        Control.VIDEO_QUALITY -> videoQualityButton
        Control.VIDEO_FPS -> videoFrameRateButton
        Control.VIDEO_DYNAMIC_RANGE -> videoDynamicRangeButton
        Control.MIC -> micButton
        Control.TIMER -> timerButton
        Control.PORTRAIT -> aiPortraitButton
        Control.MULTI_FRAME -> multiFrameButton
        Control.CREATIVE -> creativeButton
        Control.MOTION_PHOTO -> motionPhotoButton
        Control.PRE_SHOT -> preShotButton
        Control.GRID -> gridButton
    }

    /** Mirrors the view model into [uiState]; each source only touches its own fields. */
    private suspend fun bindOverlayState() = kotlinx.coroutines.coroutineScope {
        val sync = { mainLayout.post { if (overlayActive) syncLegacyControls() } }
        launch {
            viewModel.cameraMode.collect { mode ->
                uiState.update { it.copy(cameraMode = mode).derived() }
                layoutPreviewForSkin(skinRepository.skin.value)
                sync()
            }
        }
        launch { viewModel.photoAspectRatio.collect { layoutPreviewForSkin(skinRepository.skin.value) } }
        launch {
            viewModel.cameraState.collect { cs ->
                uiState.update { it.copy(cameraState = cs) }
                sync()
            }
        }
        launch { viewModel.isShutterButtonEnabled.collect { sync() } }
        launch { viewModel.isFlashButtonEnabled.collect { sync() } }
        launch { viewModel.isVideoQualityButtonEnabled.collect { sync() } }
        launch { viewModel.isVideoFrameRateButtonEnabled.collect { sync() } }
        launch { viewModel.isVideoDynamicRangeButtonEnabled.collect { sync() } }
        launch { viewModel.isVideoMicButtonEnabled.collect { sync() } }
        launch { viewModel.flashMode.collect { f -> uiState.update { it.copy(flash = f) } } }
        launch { viewModel.gridMode.collect { g -> uiState.update { it.copy(grid = g) } } }
        launch { viewModel.timerMode.collect { m -> uiState.update { it.copy(timer = m) } } }
        launch { viewModel.multiFrame.setting.collect { m -> uiState.update { it.copy(multiFrame = m).derived() } } }
        launch { viewModel.creative.mode.collect { m -> uiState.update { it.copy(creative = m) } } }
        launch { viewModel.motionPhoto.motionPhoto.collect { m -> uiState.update { it.copy(motionPhoto = m) } } }
        launch { viewModel.lookController.lookId.collect { id -> uiState.update { it.copy(lookId = id) } } }
        launch {
            // Debug builds can pin the glyph angle (the device may be fixed on a stand).
            val pinned = OverlayHost.debugFailure(this@CameraActivity, intent.getStringExtra(EXTRA_DEBUG_GLYPH_ROTATION))
                ?.toIntOrNull()
            viewModel.screenRotation.collect { r ->
                uiState.update { it.copy(glyphRotation = pinned ?: (r.compensationValue % 360)) }
            }
        }
        launch { viewModel.videoRecordingDuration.collect { d -> liveUi.recordingNanos = d } }
        launch {
            viewModel.inSingleCaptureMode.collect { single -> uiState.update { it.copy(singleCapture = single).derived() } }
        }
        launch {
            viewModel.zoomState.collect { z ->
                z ?: return@collect
                liveUi.zoomRatio = z.zoomRatio
                // Unchanged bounds leave the state equal, so the overlay is not recomposed.
                uiState.update { it.copy(minZoom = z.minZoomRatio, maxZoom = z.maxZoomRatio) }
            }
        }
        launch { proModeUi.uiState.collect { p -> uiState.update { it.copy(pro = p).derived() } } }
        launch { skinRepository.blurEnabled.collect { b -> uiState.update { it.copy(blurEnabled = b) } } }
    }

    private val overlayActions = object : CameraUiActions {
        private inline fun act(what: String, block: () -> Unit) {
            FeatureGuard.attempt(LOG_TAG, "Overlay $what") { block() }
        }

        override fun shutter() = act("shutter") {
            if (shutterButton.isEnabled || viewModel.cameraState.value.isRecordingVideo) shutterButton.performClick()
        }

        override fun selectMode(mode: UiMode) = selectUiMode(mode)

        override fun openMoreMode(mode: VideoMode) = act("more") {
            dismissKeyguardAndRun {
                startActivity(
                    Intent(this@CameraActivity, VideoModesActivity::class.java)
                        .putExtra(VideoModesActivity.EXTRA_MODE, mode.name)
                )
            }
        }

        override fun setCreative(mode: CreativeMode) = act("creative") {
            if (mode != CreativeMode.OFF) selectUiMode(UiMode.PHOTO)
            viewModel.creative.setMode(mode)
        }

        override fun setMultiFrame(setting: MultiFrameSetting) = act("multi-frame") {
            selectUiMode(UiMode.PHOTO)
            viewModel.multiFrame.setSetting(setting)
        }

        override fun colorMatch() = act("color match") {
            startActivity(Intent(this@CameraActivity, ColorMatchActivity::class.java))
        }

        override fun flash() = act("flash") { flashButton.performClick() }
        override fun flashLong() = act("flash long") { flashButton.performLongClick() }
        override fun control(control: Control) = act("control $control") { controlButton(control).performClick() }
        override fun lensChip(index: Int) = act("lens") { lensSelectorLayout.clickAt(index) }
        override fun zoomTo(ratio: Float) = act("zoom") { viewModel.cameraController.setZoomRatio(ratio) }
        override fun flip() = act("flip") { flipCameraButton.performClick() }
        override fun gallery() = act("gallery") { galleryButtonCardView.performClick() }
        override fun pauseResume() = act("pause") { videoRecordingStateButton.performClick() }
        override fun selectLook(id: String) = act("look") { lookPickerView.onLookSelected?.invoke(id) }
        override fun allSettings() = act("settings") { openSettings() }
        override fun googleLens() = act("lens app") { googleLensButton.performClick() }
        override fun proSettings(settings: ProSettings) = act("pro") { proModeUi.onSettingsChanged(settings) }
        override fun proAfLock(locked: Boolean) = act("af lock") { proModeUi.onAfLockChanged(locked) }
        override fun proFormat() = act("format") { proModeUi.onCycleFormat() }
        override fun proReset() = act("pro reset") { proModeUi.onReset() }
        override fun setSkin(id: String) = act("skin") { skinRepository.select(id) }
        override fun setBlur(enabled: Boolean) = act("blur") { skinRepository.setBlurEnabled(enabled) }

        override suspend fun previewSnapshot(): Bitmap? = try {
            PreviewFrames.copy(viewFinder, maxOf(viewFinder.width, viewFinder.height) / 4)
        } catch (e: RuntimeException) {
            Log.w(LOG_TAG, "Preview snapshot failed", e)
            null
        }
    }
    companion object {
        private val LOG_TAG = CameraActivity::class.simpleName!!
        private const val COLOR_MATCH_ITEM = "__color_match__"

        /** Debug builds: "all" or a skin id makes the skin throw, to exercise the fallback chain. */
        const val EXTRA_DEBUG_SKIN_FAIL = "debug_skin_fail"
        const val EXTRA_DEBUG_GLYPH_ROTATION = "debug_glyph_rotation"
        private const val LOOK_PRELOAD_WAIT_MS = 300L
        private const val REBIND_DEBOUNCE_MS = 150L
        const val PERF_TAG = "OcPerf"
        private const val FULL_RES_STREAM_TIMEOUT_MS = 4000L
        private const val CAPTURE_RESULT_WAIT_MS = 800L
        private const val FULL_RES_MIN_PIXELS = 6_000_000L
        private val METERING_STREAM_SIZE = Size(1280, 960)

        private const val VENDOR_TAG_TIMEOUT_MS = 3000L
        private const val VENDOR_TAG_SETTLE_MS = 3000L

        private const val MSG_HIDE_ZOOM_SLIDER = 0
        private const val MSG_HIDE_FOCUS_RING = 1
        private const val MSG_HIDE_EXPOSURE_SLIDER = 2
        private const val MSG_ON_PINCH_TO_ZOOM = 3

        // We need to return something small enough so as not to overwhelm Binder. 1MB is the
        // per-process limit across all transactions. Camera2 sets a max pixel count of 51200.
        // We set a max side length of 256, for a max pixel count of 65536. Even at 4 bytes per
        // pixel, this is only 256K, well within the limits. (Note: It's not clear if any modern
        // app expects a photo to be returned inline, rather than providing an output URI.)
        // https://developer.android.com/guide/components/activities/parcelables-and-bundles#sdbp
        private const val SINGLE_CAPTURE_INLINE_MAX_SIDE_LEN_PIXELS = 256

        private val EXPOSURE_LEVEL_FORMATTER = DecimalFormat("+#;-#")
    }
}
