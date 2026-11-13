/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.ui.overlay

import dev.opencamera.app.processing.PhotoStyle
import dev.opencamera.app.processing.PhotoStyles
import android.graphics.RectF
import android.os.Build
import android.provider.MediaStore
import kotlin.math.cos
import kotlin.math.sin
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.runtime.State
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.toBitmap
import coil3.video.VideoFrameDecoder
import dev.opencamera.app.R
import dev.opencamera.app.models.CameraMode
import dev.opencamera.app.models.CameraState
import dev.opencamera.app.models.FlashMode
import dev.opencamera.app.models.Rotation
import dev.opencamera.app.models.TimerMode
import dev.opencamera.app.modes.CreativeMode
import dev.opencamera.app.utils.TimeUtils
import dev.opencamera.capture.MultiFrameSetting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln

enum class Sheet { NONE, MORE, SETTINGS }

/** Overlay-local state that the activity also reads (back press closes sheets first). */
@Stable
class OverlayUiState {
    var sheet by mutableStateOf(Sheet.NONE)
    var looksOpen by mutableStateOf(false)

    val consumesBack get() = sheet != Sheet.NONE || looksOpen

    fun back() {
        if (sheet != Sheet.NONE) sheet = Sheet.NONE else looksOpen = false
    }
}

/** Animated glyph rotation, read in the draw phase so rotating never recomposes. */
val LocalGlyphRotation = staticCompositionLocalOf<State<Float>> { mutableFloatStateOf(0f) }

/**
 * On-preview chrome (lens chips, mode carousel, top bar) has fixed round geometry sized for touch
 * targets; past this scale labels overflow it. Sheets and settings keep the full system scale.
 */
internal const val PREVIEW_CHROME_MAX_FONT_SCALE = 1.3f

internal fun androidx.compose.ui.unit.Density.cappedFontScale(): androidx.compose.ui.unit.Density =
    if (fontScale <= PREVIEW_CHROME_MAX_FONT_SCALE) this
    else androidx.compose.ui.unit.Density(density, PREVIEW_CHROME_MAX_FONT_SCALE)

fun Modifier.uprightGlyph(rotation: State<Float>) = graphicsLayer { rotationZ = rotation.value }

/**
 * Debuggable builds count recompositions per overlay section and log them under `OcRecompose`
 * (see [RecomposeCounts.dump]); release builds skip the bookkeeping.
 */
object RecomposeCounts {
    @Volatile
    var enabled = false
    private val counts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    fun hit(name: String) {
        counts.merge(name, 1, Int::plus)
    }

    fun dump(reason: String) {
        if (!enabled || counts.isEmpty()) return
        android.util.Log.i("OcRecompose", "$reason: " + counts.entries.sortedBy { it.key }.joinToString { "${it.key}=${it.value}" })
        counts.clear()
    }
}

// Non-restartable, so it runs whenever its caller runs instead of being skipped with it.
@Composable
@androidx.compose.runtime.NonRestartableComposable
internal fun CountRecompositions(name: String) {
    if (RecomposeCounts.enabled) androidx.compose.runtime.SideEffect { RecomposeCounts.hit(name) }
}

// Each section gets a copy of the state holding only the fields it reads, so a change elsewhere
// leaves its argument equal and Compose skips it.
private fun CameraUiState.forTopBar() = CameraUiState(
    cameraState = cameraState, flashVisible = flashVisible, flash = flash, flashEnabled = flashEnabled,
    cameraMode = cameraMode, multiFrame = multiFrame, controls = controls, motionPhoto = motionPhoto,
    lookId = lookId, timer = timer,
)

private fun CameraUiState.forLooks() = CameraUiState(looks = looks, lookId = lookId, style = style)

private fun CameraUiState.forLens() = CameraUiState(minZoom = minZoom, maxZoom = maxZoom, lensChips = lensChips)

private fun CameraUiState.forModes() = CameraUiState(modes = modes, uiMode = uiMode, cameraState = cameraState)

private fun CameraUiState.forBottom() = CameraUiState(
    cameraState = cameraState, cameraMode = cameraMode, googleLens = googleLens, singleCapture = singleCapture,
    recordingPaused = recordingPaused, canFlip = canFlip, shutterPresses = shutterPresses,
    shutterEnabled = shutterEnabled, galleryUri = galleryUri, captures = captures, galleryLocked = galleryLocked,
    saving = saving,
)

private fun CameraUiState.forSheets() = CameraUiState(
    blurEnabled = blurEnabled, cameraMode = cameraMode, controls = controls, creative = creative,
    googleLens = googleLens, insetBottom = insetBottom, moreModes = moreModes, multiFrame = multiFrame,
    imageQuality = imageQuality,
)

@Composable
fun CameraOverlay(state: CameraUiState, actions: CameraUiActions, ui: OverlayUiState) {
    CountRecompositions("Overlay")
    val s = skin
    var rotationTarget by remember { mutableFloatStateOf(state.glyphRotation.toFloat() % 360f) }
    LaunchedEffect(state.glyphRotation) {
        rotationTarget += Rotation.getDifference(rotationTarget, state.glyphRotation.toFloat())
    }
    val rotation = animateFloatAsState(rotationTarget, s.medium(), label = "glyphs")
    val recording = state.cameraState.isRecordingVideo

    val density = LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(
        LocalGlyphRotation provides rotation,
        LocalDensity provides density.cappedFontScale(),
    ) {
        // Test tags double as resource ids so UiAutomator (macrobenchmark) finds them in any locale.
        @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
        Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
            PreviewChrome(state.previewRect, state.uiMode)

            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = with(LocalDensity.current) { state.insetTop.toDp() } + 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                TopBar(state.forTopBar(), actions, ui)
                Spacer(Modifier.height(8.dp))
                AnimatedVisibility(recording, enter = fadeIn(s.medium()), exit = fadeOut(s.medium())) {
                    RecordingPill(state.recordingPaused)
                }
                AnimatedVisibility(
                    !recording && state.cameraMode == CameraMode.PHOTO && state.creative != CreativeMode.OFF,
                    enter = fadeIn(s.medium()), exit = fadeOut(s.medium()),
                ) {
                    CreativeChip(state.creative) { actions.setCreative(CreativeMode.OFF) }
                }
            }

            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = with(LocalDensity.current) { state.insetBottom.toDp() } + 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AnimatedVisibility(
                    !recording && state.shotWarning != 0,
                    enter = fadeIn(s.medium()), exit = fadeOut(s.medium()),
                ) {
                    ShotWarning(state.shotWarning, state.shotWarningId, actions)
                }
                AnimatedVisibility(
                    ui.looksOpen && state.cameraMode == CameraMode.PHOTO,
                    enter = s.enter(), exit = s.exit(),
                ) {
                    Column {
                        LooksStrip(state.forLooks(), actions)
                        AnimatedVisibility(state.lookId == PhotoStyles.LOOK_ID, enter = s.enter(), exit = s.exit()) {
                            StylePanel(state.style, actions)
                        }
                    }
                }
                val pro = state.pro
                val showPro = state.uiMode == UiMode.PRO && pro != null && pro.available
                androidx.compose.animation.AnimatedContent(
                    showPro,
                    transitionSpec = { s.enter() togetherWith s.exit() },
                    contentAlignment = Alignment.BottomCenter,
                    label = "proPanel",
                ) { proShown ->
                    if (proShown && pro != null) {
                        ProPanel(pro, actions)
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            AnimatedVisibility(
                                !ui.looksOpen || state.cameraMode != CameraMode.PHOTO,
                                enter = s.enter(), exit = s.exit(),
                            ) {
                                LensRow(state.forLens(), actions)
                            }
                            if (pro != null && pro.available) ProPanelWarmUp(pro, actions)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier.height(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.animation.AnimatedVisibility(
                        !recording && !state.singleCapture,
                        enter = fadeIn(s.medium()), exit = fadeOut(s.medium()),
                    ) {
                        ModeCarousel(state.forModes(), actions, ui)
                    }
                }
                Spacer(Modifier.height(8.dp))
                BottomRow(state.forBottom(), actions)
            }

            androidx.compose.runtime.CompositionLocalProvider(LocalDensity provides density) {
                Sheets(state.forSheets(), actions, ui)
            }
        }
    }
}

private val GlassBar = Color(0xFF050505)

/** Rounded preview corners (masks over the SurfaceView), Glass black bars and bottom scrim. */
@Composable
private fun BoxScope.PreviewChrome(rect: RectF?, mode: UiMode) {
    CountRecompositions("PreviewChrome")
    val s = skin
    val radiusPx = with(LocalDensity.current) { s.tokens.shape.previewRadius.dp.toPx() }
    // Every mode change dims the preview briefly, so switches that keep the session (Night,
    // Portrait, Pro) read as a transition too; rebinding switches add the frozen blurred frame.
    val dip = remember { Animatable(0f) }
    var lastMode by remember { mutableStateOf(mode) }
    LaunchedEffect(mode) {
        if (mode == lastMode || !s.glass) return@LaunchedEffect
        lastMode = mode
        dip.animateTo(MODE_DIP, tween(s.motion.mediumMs * 2 / 5))
        dip.animateTo(0f, tween(s.motion.slowMs))
    }
    Canvas(Modifier.matchParentSize()) {
        val d = dip.value
        if (d > 0f && rect != null) {
            drawRect(
                Color.Black.copy(alpha = d), Offset(rect.left, rect.top), Size(rect.width(), rect.height()),
            )
        }
        if (s.glass) {
            // As in the iOS camera: the strips outside a letterboxed preview are opaque black bars;
            // only a preview that reaches the bottom edge gets a dark scrim under the controls.
            val bottom = rect?.bottom ?: size.height
            if (rect != null) {
                if (rect.top > 0f) drawRect(GlassBar, Offset.Zero, Size(size.width, rect.top))
                if (bottom < size.height) drawRect(GlassBar, Offset(0f, bottom), Size(size.width, size.height - bottom))
            }
            val top = size.height * 0.6f
            if (bottom > top + 1f) {
                drawRect(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .6f)), startY = top, endY = bottom),
                    topLeft = Offset(0f, top), size = Size(size.width, bottom - top),
                )
            }
        }
        if (rect != null && radiusPx > 0f) {
            val path = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(androidx.compose.ui.geometry.Rect(rect.left, rect.top, rect.right, rect.bottom))
                addRoundRect(
                    androidx.compose.ui.geometry.RoundRect(
                        rect.left, rect.top, rect.right, rect.bottom, CornerRadius(radiusPx, radiusPx),
                    )
                )
            }
            drawPath(path, Color.Black)
        }
    }
}

// ---------------------------------------------------------------- top bar

@Composable
private fun TopBar(state: CameraUiState, actions: CameraUiActions, ui: OverlayUiState) {
    CountRecompositions("TopBar")
    val s = skin
    val recording = state.cameraState.isRecordingVideo
    val idle = state.cameraState == CameraState.IDLE
    val container = if (s.tokens.shape.topBarCapsule) {
        Modifier.glassCapsule().padding(horizontal = 8.dp)
    } else {
        Modifier.fillMaxWidth().padding(horizontal = 12.dp)
    }
    Row(
        container.heightIn(min = 48.dp),
        horizontalArrangement = if (s.tokens.shape.topBarCapsule) Arrangement.spacedBy(2.dp) else Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.flashVisible) {
            val (glyph, badge, on) = when (state.flash) {
                FlashMode.OFF -> Triple(Glyphs.flashOff, null, false)
                FlashMode.AUTO -> Triple(Glyphs.flash, "A", false)
                FlashMode.ON -> Triple(Glyphs.flash, null, true)
                FlashMode.TORCH -> Triple(Glyphs.torch, null, true)
                FlashMode.SCREEN -> Triple(Glyphs.flash, "S", true)
            }
            TopIcon(
                glyph, stringResource(R.string.flash_button_description), stringResource(flashLabel(state.flash)),
                active = on, badge = badge, enabled = state.flashEnabled,
                onLongClick = actions::flashLong, onClick = actions::flash,
            )
        }
        if (recording) return@Row
        when (state.cameraMode) {
            CameraMode.PHOTO -> {
                val mf = state.multiFrame
                TopIcon(
                    when (mf) {
                        MultiFrameSetting.NIGHT -> Glyphs.night
                        MultiFrameSetting.ASTRO -> Glyphs.star
                        else -> Glyphs.hdr
                    },
                    stringResource(R.string.ui_hdr), state.controls[Control.MULTI_FRAME]?.label ?: "",
                    active = mf != MultiFrameSetting.OFF && mf != MultiFrameSetting.HDR_AUTO,
                    badge = if (mf == MultiFrameSetting.HDR_AUTO) "A" else null,
                    struck = mf == MultiFrameSetting.OFF,
                    onClick = { actions.control(Control.MULTI_FRAME) },
                )
                TopIcon(
                    if (state.motionPhoto) Glyphs.live else Glyphs.liveOff,
                    stringResource(R.string.ui_motion_photo), state.controls[Control.MOTION_PHOTO]?.label ?: "",
                    active = state.motionPhoto, onClick = { actions.control(Control.MOTION_PHOTO) },
                )
                TimerIcon(state, actions, idle)
                TopIcon(
                    Glyphs.looks, stringResource(R.string.look_button), state.controls[Control.LOOK]?.label ?: "",
                    active = ui.looksOpen || state.lookId.isNotEmpty() && state.lookId != "none", tag = "looks",
                    onClick = {
                        if (!ui.looksOpen) dev.opencamera.app.PerfTrace.begin(dev.opencamera.app.PerfTrace.OPEN_LOOKS)
                        ui.looksOpen = !ui.looksOpen
                    },
                )
            }

            CameraMode.VIDEO -> {
                listOf(Control.VIDEO_QUALITY, Control.VIDEO_FPS).forEach { c ->
                    state.controls[c]?.takeIf { it.visible }?.let { ctl ->
                        TopText(ctl.label, stringResource(controlName(c)), ctl.enabled && idle) { actions.control(c) }
                    }
                }
                state.controls[Control.MIC]?.takeIf { it.visible }?.let { ctl ->
                    TopText(ctl.label, stringResource(R.string.ui_mic), ctl.enabled) { actions.control(Control.MIC) }
                }
                TimerIcon(state, actions, idle)
            }

            CameraMode.QR -> Unit
        }
        TopIcon(
            Glyphs.settings, stringResource(R.string.ui_settings), null, enabled = idle, tag = "settings",
            // Tap: the full settings screen; long press: the quick sheet (skin, blur, mode options).
            onLongClick = { ui.sheet = Sheet.SETTINGS },
            onClick = { actions.allSettings() },
        )
    }
}

@Composable
private fun TimerIcon(state: CameraUiState, actions: CameraUiActions, idle: Boolean) = TopIcon(
    Glyphs.timer, stringResource(R.string.ui_timer), state.controls[Control.TIMER]?.label ?: "",
    active = state.timer != TimerMode.OFF,
    badge = when (state.timer) {
        TimerMode.OFF -> null
        TimerMode.ON_3S -> "3"
        TimerMode.ON_10S -> "10"
        TimerMode.SMART -> "A"
    },
    enabled = idle, onClick = { actions.control(Control.TIMER) },
)

private fun flashLabel(mode: FlashMode) = when (mode) {
    FlashMode.OFF -> R.string.ui_flash_off
    FlashMode.AUTO -> R.string.ui_flash_auto
    FlashMode.ON -> R.string.ui_flash_on
    FlashMode.TORCH -> R.string.ui_flash_torch
    FlashMode.SCREEN -> R.string.ui_flash_screen
}

@Composable
private fun TopIcon(
    glyph: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    stateText: String?,
    active: Boolean = false,
    badge: String? = null,
    struck: Boolean = false,
    enabled: Boolean = true,
    tag: String? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val s = skin
    val view = LocalView.current
    val tint by animateColorAsState(if (active) s.accentText else s.fg, s.fast(), label = "tint")
    Box(
        Modifier
            .size(48.dp)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .semantics {
                contentDescription = description
                if (stateText != null) stateDescription = stateText
            }
            .pressable(enabled, onLongClick?.let { { Haptics.longPress(view); it() } }) {
                Haptics.segment(view)
                onClick()
            }
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.uprightGlyph(LocalGlyphRotation.current), contentAlignment = Alignment.Center) {
            Glyph(glyph, tint, size = 22.dp)
            if (struck && glyph == Glyphs.hdr) {
                Canvas(Modifier.size(22.dp)) {
                    drawLine(tint, Offset(2f, 2f), Offset(size.width - 2f, size.height - 2f), strokeWidth = 4f)
                }
            }
            if (badge != null) {
                BasicText(
                    badge,
                    Modifier.offset(11.dp, (-9).dp),
                    style = TextStyle(color = tint, fontSize = 9.sp, fontWeight = FontWeight.Bold),
                )
            }
        }
    }
}

@Composable
private fun TopText(label: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    val s = skin
    val view = LocalView.current
    Box(
        Modifier
            .padding(horizontal = 4.dp)
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .semantics { contentDescription = description; stateDescription = label }
            .pressable(enabled) { Haptics.segment(view); onClick() }
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f },
        contentAlignment = Alignment.Center,
    ) {
        val rotation = LocalGlyphRotation.current
        BasicText(
            label,
            Modifier.graphicsLayer {
                rotationZ = rotation.value
                // Turned sideways, a wide label ("1080p", "MIC ON") must still fit the bar height.
                val rad = Math.toRadians(rotation.value.toDouble())
                val extent = abs(sin(rad)) * size.width + abs(cos(rad)) * size.height
                val limit = 44.dp.toPx()
                val fit = if (extent > limit) (limit / extent).toFloat() else 1f
                scaleX = fit
                scaleY = fit
            },
            // Longer translations ("BẬT MIC", "30 FPS") would touch their neighbours at 12 sp.
            style = TextStyle(
                color = s.fg, fontSize = if (label.length > 5) 11.sp else 12.sp, fontWeight = FontWeight.SemiBold,
            ),
            maxLines = 1,
        )
    }
}

/**
 * Glass material: a light veil with a bright rim that fades from the top edge (the specular
 * highlight). The faint dark base keeps white glyphs readable over bright scenes.
 */
fun Modifier.glassCapsule(circle: Boolean = false) = this
    .clip(if (circle) CircleShape else RoundedCornerShape(50))
    .background(Color.Black.copy(alpha = .14f))
    .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = .20f), Color.White.copy(alpha = .08f))))
    .drawWithContent {
        drawContent()
        val r = size.minDimension / 2
        drawRoundRect(
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = .55f), .45f to Color.White.copy(alpha = .06f),
                1f to Color.White.copy(alpha = .18f),
            ),
            cornerRadius = CornerRadius(r, r), style = Stroke(1.dp.toPx()),
        )
    }

/** Click (and optional long click) without ripple; a slight scale-down while pressed. */
@Composable
fun Modifier.pressable(enabled: Boolean = true, onLongClick: (() -> Unit)? = null, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, skin.fast(), label = "press")
    val base = this.graphicsLayer { scaleX = scale; scaleY = scale }
    return if (onLongClick != null) {
        base.combinedClickableCompat(source, enabled, onLongClick, onClick)
    } else {
        base.clickable(source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(
    source: MutableInteractionSource, enabled: Boolean, onLongClick: () -> Unit, onClick: () -> Unit,
) = this.then(
    Modifier.combinedClickable(
        interactionSource = source, indication = null, enabled = enabled, role = Role.Button,
        onLongClick = onLongClick, onClick = onClick,
    )
)

@Composable
private fun RecordingPill(paused: Boolean) {
    val s = skin
    val blink = rememberInfiniteTransition(label = "rec")
    val dot by blink.animateFloat(
        1f, 0f, infiniteRepeatable(tween(500, easing = LinearEasing), RepeatMode.Reverse), label = "dot",
    )
    val text = TimeUtils.convertNanosToString(LocalLiveUi.current.recordingNanos)
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (paused) s.surface else s.record)
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .semantics { liveRegion = LiveRegionMode.Polite; contentDescription = text },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .graphicsLayer { alpha = if (paused) 1f else dot }
                .background(Color.White, CircleShape)
        )
        Spacer(Modifier.width(6.dp))
        BasicText(text, style = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
    }
}

@Composable
private fun CreativeChip(mode: CreativeMode, onClear: () -> Unit) {
    val s = skin
    val label = stringResource(mode.label)
    val clear = stringResource(R.string.ui_creative_clear, label)
    Row(
        Modifier
            .heightIn(min = 48.dp)
            .semantics { contentDescription = clear }
            .pressable(onClick = onClear),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(s.surface).padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(label, style = TextStyle(color = s.accentText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
            Spacer(Modifier.width(4.dp))
            Glyph(Glyphs.close, s.fg, size = 16.dp)
        }
    }
}

// ---------------------------------------------------------------- lens chips + zoom ring

@Composable
private fun LensRow(state: CameraUiState, actions: CameraUiActions) {
    CountRecompositions("LensRow")
    val s = skin
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var ring by remember { mutableStateOf(false) }
    var hideJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val live = LocalLiveUi.current
    val minZ by androidx.compose.runtime.rememberUpdatedState(state.minZoom)
    val maxZ by androidx.compose.runtime.rememberUpdatedState(state.maxZoom)
    var dragZoom by remember { mutableFloatStateOf(1f) }
    val canZoom = state.maxZoom > state.minZoom
    val pxPerStop = with(LocalDensity.current) { 140.dp.toPx() }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    Box(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .then(if (!canZoom) Modifier else Modifier.pointerInput("ring") {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        hideJob?.cancel()
                        dragZoom = live.zoomRatio
                        ring = true
                        Haptics.longPress(view)
                    },
                    onDragEnd = { hideJob = scope.launch { delay(1200); ring = false } },
                    onDragCancel = { hideJob = scope.launch { delay(1200); ring = false } },
                ) { change, amount ->
                    change.consume()
                    val dx = if (rtl) -amount.x else amount.x
                    val before = dragZoom
                    dragZoom = (dragZoom * exp(dx / pxPerStop)).coerceIn(minZ, maxZ)
                    if (floor(before * 2) != floor(dragZoom * 2)) Haptics.tick(view)
                    actions.zoomTo(dragZoom)
                }
            }),
        contentAlignment = Alignment.Center,
    ) {
        val ringAlpha by animateFloatAsState(if (ring) 1f else 0f, s.medium(), label = "ring")
        if (ringAlpha > 0f) ZoomRing(state, Modifier.matchParentSize().graphicsLayer { alpha = ringAlpha })
        if (state.lensChips.isNotEmpty()) {
            val selected = state.lensChips.indexOfFirst { it.selected }
            // The selected-lens pill slides between chips like the mode pill; read in draw only.
            val pillAt = animateFloatAsState(selected.coerceAtLeast(0).toFloat(), s.bouncy(), label = "lensPill")
            val pillColor = if (s.glass) Color.White.copy(alpha = .18f) else Color.White
            Box(
                Modifier
                    .graphicsLayer { alpha = 1f - ringAlpha }
                    .then(
                        if (s.glass) Modifier.glassCapsule()
                        else Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = .4f))
                    )
                    .padding(horizontal = 2.dp),
            ) {
                if (selected >= 0) {
                    Box(
                        Modifier
                            .padding(4.dp)
                            .size(40.dp)
                            .graphicsLayer {
                                val step = 48.dp.toPx()
                                translationX = if (rtl) -pillAt.value * step else pillAt.value * step
                            }
                            .background(pillColor, CircleShape)
                    )
                }
                Row(Modifier.selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
                    state.lensChips.forEachIndexed { i, chip ->
                        LensChip(chip, Modifier.testTag("lens_${chip.label}")) { Haptics.segment(view); actions.lensChip(i) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LensChip(chip: LensChipUi, modifier: Modifier, onClick: () -> Unit) {
    val s = skin
    val scale by animateFloatAsState(if (chip.selected) 1.12f else 1f, s.bouncy(), label = "chip")
    val label = if (chip.selected) "${chip.label}×" else chip.label
    val description = stringResource(R.string.ui_zoom_chip, chip.label)
    Box(
        modifier
            .size(48.dp)
            .selectable(
                selected = chip.selected, role = Role.RadioButton, onClick = onClick,
                interactionSource = remember { MutableInteractionSource() }, indication = null,
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        // The selected background is the sliding pill drawn by LensRow.
        val bg = if (chip.selected || s.glass) Color.Transparent else Color.Black.copy(alpha = .3f)
        Box(
            Modifier
                .size(40.dp)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .uprightGlyph(LocalGlyphRotation.current)
                .background(bg, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                label,
                style = TextStyle(
                    color = when {
                        !chip.selected -> s.fg
                        s.glass -> s.accent
                        else -> Color(0xFF111111)
                    },
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                ),
                maxLines = 1,
            )
        }
    }
}

/** Arc dial with a tick every 0.1x (log scale) and the current ratio. Drawn, not composed. */
@Composable
private fun ZoomRing(state: CameraUiState, modifier: Modifier) {
    val s = skin
    val live = LocalLiveUi.current
    val minZ = state.minZoom.coerceAtLeast(0.1f)
    val maxZ = state.maxZoom.coerceAtLeast(minZ + 0.01f)
    Box(modifier, contentAlignment = Alignment.TopCenter) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val pxPerLog = w / 2.2f
            // Read in the draw phase: zooming redraws the dial without recomposing.
            val center = ln(live.zoomRatio)
            var r = (floor(minZ * 10) / 10f).coerceAtLeast(0.1f)
            while (r <= maxZ + 1e-3f) {
                val x = w / 2 + (ln(r) - center) * pxPerLog
                if (x in 0f..w) {
                    val major = abs(r - Math.round(r)) < 1e-3f || abs(r * 2 - Math.round(r * 2)) < 1e-3f
                    val fade = 1f - abs(x - w / 2) / (w / 2)
                    drawLine(
                        s.fg.copy(alpha = fade * if (major) 1f else .5f),
                        Offset(x, size.height - if (major) 22.dp.toPx() else 14.dp.toPx()),
                        Offset(x, size.height - 6.dp.toPx()),
                        strokeWidth = if (major) 2f else 1.2f,
                    )
                }
                r = Math.round((r + 0.1f) * 10) / 10f
            }
            drawLine(s.accent, Offset(w / 2, size.height - 28.dp.toPx()), Offset(w / 2, size.height - 4.dp.toPx()), strokeWidth = 4f)
        }
        ZoomLabel(live)
    }
}

@Composable
private fun ZoomLabel(live: LiveUiValues) {
    val s = skin
    run {
        BasicText(
            "%.1f×".format(live.zoomRatio),
            Modifier.padding(top = 2.dp).semantics { liveRegion = LiveRegionMode.Polite },
            style = TextStyle(color = s.accentText, fontSize = 14.sp, fontWeight = FontWeight.Bold),
        )
    }
}

// ---------------------------------------------------------------- mode carousel

@Composable
private fun modeLabel(mode: UiMode) = stringResource(
    when (mode) {
        UiMode.MORE -> R.string.camera_mode_more
        UiMode.NIGHT -> R.string.ui_mode_night
        UiMode.PORTRAIT -> R.string.ui_mode_portrait
        UiMode.PHOTO -> R.string.camera_mode_photo
        UiMode.VIDEO -> R.string.camera_mode_video
        UiMode.PRO -> R.string.ui_mode_pro
        UiMode.QR -> R.string.camera_mode_qr
    }
)

@Composable
private fun ModeCarousel(state: CameraUiState, actions: CameraUiActions, ui: OverlayUiState) {
    CountRecompositions("ModeCarousel")
    val s = skin
    val view = LocalView.current
    val modes = state.modes
    val selected = modes.indexOf(state.uiMode).coerceAtLeast(0)
    val position = animateFloatAsState(selected.toFloat(), s.bouncy(), label = "carousel")
    val enabled = state.cameraState == CameraState.IDLE
    val threshold = with(LocalDensity.current) { 40.dp.toPx() }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var drag by remember { mutableFloatStateOf(0f) }
    val pillWidth = remember { mutableFloatStateOf(0f) }
    // Animated values below are read in layout and draw, so the carousel does not recompose per frame.
    val pillAnimated = animateDpAsState(
        with(LocalDensity.current) { pillWidth.floatValue.toDp() }, s.bouncy(), label = "pill",
    )

    val select: (UiMode) -> Unit = { mode ->
        if (mode == UiMode.MORE) ui.sheet = Sheet.MORE
        else if (mode != state.uiMode) {
            Haptics.segment(view)
            actions.selectMode(mode)
        }
    }
    val currentMode by androidx.compose.runtime.rememberUpdatedState(state.uiMode)
    val modesNow by androidx.compose.runtime.rememberUpdatedState(modes)
    val enabledNow by androidx.compose.runtime.rememberUpdatedState(enabled)
    val selectNow by androidx.compose.runtime.rememberUpdatedState(select)

    Box(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .pointerInput("carousel") {
                detectHorizontalDragGestures(
                    onDragStart = { drag = 0f; actions.modeDragStarted() },
                    onDragEnd = {
                        if (abs(drag) > threshold && enabledNow) {
                            val forward = (drag < 0) != rtl
                            UiModes.swipe(currentMode, forward, modesNow)?.let(selectNow)
                        }
                    },
                ) { change, amount -> change.consume(); drag += amount }
            }
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    Brush.horizontalGradient(
                        0f to Color.Transparent, 0.2f to Color.Black, 0.8f to Color.Black, 1f to Color.Transparent,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        // Selected-mode pill, fixed at the center; the labels slide under it.
        Box(
            Modifier
                .layout { measurable, _ ->
                    val p = measurable.measure(Constraints.fixed(pillAnimated.value.roundToPx().coerceAtLeast(0), 36.dp.roundToPx()))
                    layout(p.width, p.height) { p.place(0, 0) }
                }
                .clip(RoundedCornerShape(50))
                .then(
                    if (s.glass) Modifier else Modifier.background(s.accentBrush)
                )
        )
        Layout(
            modifier = Modifier.fillMaxSize().selectableGroup(),
            content = {
                modes.forEachIndexed { i, mode ->
                    val on = i == selected
                    val color = animateColorAsState(if (on) s.modeOn else s.muted, s.medium(), label = "mode")
                    BasicText(
                        modeLabel(mode),
                        Modifier
                            .testTag("mode_${mode.name}")
                            .heightIn(min = 48.dp)
                            .widthIn(max = 140.dp)
                            .selectable(
                                selected = on, enabled = enabled, role = Role.Tab,
                                interactionSource = remember { MutableInteractionSource() }, indication = null,
                            ) { select(mode) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        style = TextStyle(
                            fontSize = s.tokens.type.modeSize.sp, fontWeight = s.modeWeight,
                            textAlign = TextAlign.Center, shadow = if (on && !s.glass) null else LabelShadow,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = { color.value },
                    )
                }
            },
        ) { measurables, constraints ->
            val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
            val starts = FloatArray(placeables.size)
            var x = 0f
            placeables.forEachIndexed { i, p -> starts[i] = x; x += p.width }
            fun center(i: Int) = starts[i] + placeables[i].width / 2f
            if (placeables.isNotEmpty()) pillWidth.floatValue = placeables[selected].width.toFloat()
            layout(constraints.maxWidth, constraints.maxHeight) {
                if (placeables.isEmpty()) return@layout
                val pos = position.value.coerceIn(0f, placeables.lastIndex.toFloat())
                val a = floor(pos).toInt()
                val b = (a + 1).coerceAtMost(placeables.lastIndex)
                val c = center(a) + (center(b) - center(a)) * (pos - a)
                val shift = constraints.maxWidth / 2f - c
                placeables.forEachIndexed { i, p ->
                    p.placeRelative((starts[i] + shift).toInt(), (constraints.maxHeight - p.height) / 2)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- bottom row

@Composable
private fun BottomRow(state: CameraUiState, actions: CameraUiActions) {
    CountRecompositions("BottomRow")
    val s = skin
    val view = LocalView.current
    val recording = state.cameraState.isRecordingVideo
    val idle = state.cameraState == CameraState.IDLE
    Row(
        Modifier.fillMaxWidth().height(104.dp).padding(horizontal = 28.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
            when {
                state.cameraMode == CameraMode.QR && state.googleLens -> RoundButton(
                    Glyphs.scan, stringResource(R.string.google_lens_button_description), true, onClick = actions::googleLens,
                )

                !recording && !state.singleCapture && state.cameraMode != CameraMode.QR -> Thumb(state, idle, actions::gallery)
            }
        }
        Box(Modifier.size((s.tokens.shape.shutter + 8).dp), contentAlignment = Alignment.Center) {
            if (state.cameraMode != CameraMode.QR) Shutter(state) {
                when {
                    state.cameraMode == CameraMode.VIDEO && !recording -> Haptics.recordStart(view)
                    recording -> Haptics.recordStop(view)
                    else -> Haptics.shutter(view)
                }
                actions.shutter()
            }
        }
        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
            if (recording) {
                RoundButton(
                    if (state.recordingPaused) Glyphs.play else Glyphs.pause,
                    stringResource(if (state.recordingPaused) R.string.ui_resume_recording else R.string.ui_pause_recording),
                    true,
                ) { Haptics.segment(view); actions.pauseResume() }
            } else if (state.canFlip) {
                val spin = remember { Animatable(0f) }
                val scope = rememberCoroutineScope()
                RoundButton(Glyphs.flip, stringResource(R.string.flip_camera_button_description), idle, spinState = spin.asState()) {
                    Haptics.segment(view)
                    scope.launch { spin.animateTo(spin.value + 180f, s.slow()) }
                    actions.flip()
                }
            }
        }
    }
}

@Composable
private fun RoundButton(
    glyph: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    spinState: State<Float>? = null,
    onClick: () -> Unit,
) {
    val s = skin
    val rotation = LocalGlyphRotation.current
    Box(
        Modifier
            .size(52.dp)
            .semantics { contentDescription = description }
            .pressable(enabled, onClick = onClick)
            .graphicsLayer { alpha = if (enabled) 1f else .4f }
            .then(if (s.glass) Modifier.glassCapsule(circle = true) else Modifier.background(s.chip, CircleShape)),
        contentAlignment = Alignment.Center,
    ) {
        Glyph(
            glyph, s.fg,
            Modifier.graphicsLayer { rotationZ = rotation.value + (spinState?.value ?: 0f) },
            size = 26.dp,
        )
    }
}

@Composable
private fun Shutter(state: CameraUiState, onClick: () -> Unit) {
    val s = skin
    val recording = state.cameraState.isRecordingVideo
    val video = state.cameraMode == CameraMode.VIDEO
    val busy = state.cameraState == CameraState.TAKING_PHOTO
    val size = s.tokens.shape.shutter.dp
    val pulse = remember { Animatable(1f) }
    // Shrinks on touch-down, before the click is recognised; driven from the pointer handler and
    // read in the draw phase, so a press never recomposes the shutter.
    val press = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val pressEnabled by androidx.compose.runtime.rememberUpdatedState(state.shutterEnabled || recording)
    LaunchedEffect(state.shutterPresses) {
        if (state.shutterPresses == 0 || video) return@LaunchedEffect
        pulse.animateTo(0.86f, tween(s.motion.fastMs))
        pulse.animateTo(1f, s.medium())
    }
    val coreColor by animateColorAsState(if (video) s.record else Color.White, s.medium(), label = "core")
    val coreSize by animateDpAsState(if (recording) 28.dp else size - 16.dp, s.medium(), label = "coreSize")
    val coreRadius by animateDpAsState(if (recording) 6.dp else size, s.medium(), label = "coreRadius")
    val description = stringResource(
        when {
            recording -> R.string.ui_stop_recording
            video -> R.string.ui_start_recording
            else -> R.string.ui_take_photo
        }
    )
    // Runs only while busy and is read in the draw phase: an idle camera screen draws no frames.
    val sweep = remember { Animatable(0f) }
    val view = LocalView.current
    val ready = state.shutterEnabled && !busy
    LaunchedEffect(ready) {
        if (ready) dev.opencamera.app.PerfTrace.endAfterNextFrame(view, dev.opencamera.app.PerfTrace.SHUTTER_TO_READY)
    }
    LaunchedEffect(busy) {
        if (!busy) return@LaunchedEffect sweep.snapTo(0f)
        while (true) {
            sweep.animateTo(360f, tween(900, easing = LinearEasing))
            sweep.snapTo(0f)
        }
    }
    val ring = s.tokens.shape.shutterRing.dp
    val videoRing = Brush.sweepGradient(listOf(Color(0xFFFF3D60), Color(0xFFFF8A4C), Color(0xFFFF3D60)))
    val ringBrush = if (s.glass) Brush.linearGradient(listOf(Color.White, Color.White)) else if (video) videoRing else s.accentBrush
    Box(
        Modifier
            .size(size)
            .testTag("shutter")
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (!pressEnabled) return@awaitEachGesture
                    scope.launch { press.animateTo(0.9f, tween(s.motion.fastMs)) }
                    waitForUpOrCancellation(PointerEventPass.Initial)
                    scope.launch { press.animateTo(1f, s.medium()) }
                }
            }
            .clickable(
                remember { MutableInteractionSource() }, indication = null,
                enabled = state.shutterEnabled || recording, role = Role.Button, onClick = onClick,
            )
            .graphicsLayer {
                alpha = if (state.shutterEnabled || recording) 1f else .5f
                scaleX = press.value; scaleY = press.value
            }
            .drawBehind {
                val w = ring.toPx()
                drawCircle(ringBrush, radius = this.size.minDimension / 2 - w / 2, style = Stroke(w))
                if (busy) {
                    drawArc(
                        s.accent, sweep.value, 90f, false,
                        topLeft = Offset(w / 2, w / 2), size = Size(this.size.width - w, this.size.height - w),
                        style = Stroke(w * 1.2f),
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(coreSize)
                .graphicsLayer { scaleX = pulse.value; scaleY = pulse.value }
                .clip(RoundedCornerShape(coreRadius))
                .background(coreColor)
        )
    }
}

@Composable
private fun Thumb(state: CameraUiState, enabled: Boolean, onClick: () -> Unit) {
    val s = skin
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, state.galleryUri) {
        val uri = state.galleryUri ?: run { value = null; return@produceState }
        // MediaStore keeps a small thumbnail; decoding the full photo for it took ~0.3 s at start.
        value = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri.authority == MediaStore.AUTHORITY) {
                withContext(Dispatchers.IO) { context.contentResolver.loadThumbnail(uri, android.util.Size(144, 144), null) }
            } else null
        }.getOrNull() ?: runCatching {
            val request = ImageRequest.Builder(context).data(uri).size(144)
                .decoderFactory(VideoFrameDecoder.Factory()).build()
            SingletonImageLoader.get(context).execute(request).image?.toBitmap()
        }.getOrNull()
    }
    val pop = remember { Animatable(1f) }
    LaunchedEffect(state.captures) {
        if (state.captures == 0) return@LaunchedEffect
        pop.snapTo(0.4f)
        pop.animateTo(1f, s.bouncy())
    }
    // Background save / merge ring: determinate when the merge reports progress, else a spinner.
    val live = LocalLiveUi.current
    val spin = remember { Animatable(0f) }
    LaunchedEffect(state.saving) {
        if (!state.saving) return@LaunchedEffect spin.snapTo(0f)
        while (true) {
            spin.animateTo(360f, tween(900, easing = LinearEasing))
            spin.snapTo(0f)
        }
    }
    val shape = if (s.glass) RoundedCornerShape(10.dp) else CircleShape
    Box(
        Modifier
            .size(if (s.glass) 52.dp else 48.dp)
            .drawWithContent {
                drawContent()
                if (!state.saving) return@drawWithContent
                val w = 3.dp.toPx()
                val progress = live.saveProgress
                drawArc(
                    s.accent, if (progress == null) spin.value - 90f else -90f,
                    if (progress == null) 90f else 360f * progress.coerceIn(0.02f, 1f), false,
                    topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w),
                    style = Stroke(w),
                )
            }
            .testTag("gallery")
            .semantics {
                contentDescription = context.getString(R.string.gallery_button_description)
                if (state.saving) stateDescription = context.getString(R.string.ui_saving)
            }
            .pressable(enabled, onClick = onClick)
            .uprightGlyph(LocalGlyphRotation.current)
            .graphicsLayer { scaleX = pop.value; scaleY = pop.value }
            .then(
                if (s.glass) Modifier.clip(shape).background(s.chip)
                else Modifier.clip(shape).background(s.chip).border(2.dp, s.accentBrush, shape)
            ),
        contentAlignment = Alignment.Center,
    ) {
        val b = bitmap
        if (b != null && !state.galleryLocked) {
            Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Glyph(if (state.galleryLocked) Glyphs.lock else Glyphs.image, s.fg, size = 22.dp)
        }
    }
}

// ---------------------------------------------------------------- looks strip

@Composable
private fun LooksStrip(state: CameraUiState, actions: CameraUiActions) {
    CountRecompositions("LooksStrip")
    val s = skin
    val view = LocalView.current
    LaunchedEffect(Unit) { dev.opencamera.app.PerfTrace.endAfterNextFrame(view, dev.opencamera.app.PerfTrace.OPEN_LOOKS) }
    LazyRow(
        Modifier.fillMaxWidth().height(96.dp).testTag("looks_strip").selectableGroup(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(state.looks, key = { _, it -> it.id }) { i, look ->
            val on = look.id == state.lookId
            val shape = if (s.glass) RoundedCornerShape(s.radiusS) else CircleShape
            Column(
                Modifier
                    .width(64.dp)
                    .selectable(
                        on, role = Role.RadioButton,
                        interactionSource = remember { MutableInteractionSource() }, indication = null,
                    ) { Haptics.segment(view); actions.selectLook(look.id) }
                    .uprightGlyph(LocalGlyphRotation.current),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .size(60.dp)
                        .clip(shape)
                        .background(lookSwatch(look.id, i))
                        .border(2.dp, if (on) s.accentBrush else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent)), shape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (look.id == LOOK_COLOR_MATCH) Glyph(Glyphs.palette, Color.White, size = 26.dp)
                }
                Spacer(Modifier.height(4.dp))
                BasicText(
                    look.name, style = TextStyle(color = if (on) s.fg else s.muted, fontSize = 11.sp, textAlign = TextAlign.Center),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private enum class StyleAxis(val label: Int) { TONE(R.string.photo_style_tone), WARMTH(R.string.photo_style_warmth), COLOR(R.string.photo_style_color) }

private val STYLE_PRESET_LABELS = mapOf(
    "standard" to R.string.photo_style_standard, "rich" to R.string.photo_style_rich,
    "vibrant" to R.string.photo_style_vibrant, "warm" to R.string.photo_style_warm,
    "cool" to R.string.photo_style_cool, "muted" to R.string.photo_style_muted,
)

/** Style presets, the axis being tuned (plus save) and one dial for it. */
@Composable
private fun StylePanel(style: PhotoStyle, actions: CameraUiActions) {
    CountRecompositions("StylePanel")
    var axis by remember { mutableStateOf(StyleAxis.TONE) }
    val presetIndex = PhotoStyles.presetIndex(style)
    // A light scrim keeps the chips and the dial readable over a bright scene.
    Column(
        Modifier
            .padding(horizontal = 8.dp)
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .testTag("style_panel"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        dev.opencamera.app.develop.ui.compose.ChipRow(
            PhotoStyles.PRESETS, label = { stringResource(STYLE_PRESET_LABELS.getValue(it.first)) },
            selected = { PhotoStyles.PRESETS.indexOf(it) == presetIndex },
        ) { _, p -> actions.setStyle(p.second) }
        val save = stringResource(R.string.photo_style_save)
        dev.opencamera.app.develop.ui.compose.ChipRow(
            StyleAxis.entries + listOf(null),
            label = { a -> if (a == null) save else stringResource(a.label) },
            selected = { it == axis },
            dirty = { a -> a != null && valueOf(style, a) != 0 },
        ) { _, a -> if (a == null) actions.saveStylePreset() else axis = a }
        dev.opencamera.app.develop.ui.compose.RulerDial(
            label = stringResource(axis.label),
            value = valueOf(style, axis).toFloat(),
            min = -100f, max = 100f, step = 1f, default = 0f,
            format = { PhotoStyles.label(it.toInt()) },
            onChange = { v ->
                val n = PhotoStyles.fromSlider(v)
                actions.setStyle(
                    when (axis) {
                        StyleAxis.TONE -> style.copy(tone = n)
                        StyleAxis.WARMTH -> style.copy(warmth = n)
                        StyleAxis.COLOR -> style.copy(color = n)
                    },
                )
            },
            onCommit = {},
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

private fun valueOf(s: PhotoStyle, axis: StyleAxis) = when (axis) {
    StyleAxis.TONE -> s.tone
    StyleAxis.WARMTH -> s.warmth
    StyleAxis.COLOR -> s.color
}

/** "Photo looks blurred / eyes closed" with a retake button; hides itself after a few seconds. */
@Composable
private fun ShotWarning(message: Int, id: Long, actions: CameraUiActions) {
    if (message == 0) return
    val s = skin
    val view = LocalView.current
    LaunchedEffect(id) {
        delay(SHOT_WARNING_MS)
        actions.dismissShotWarning()
    }
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(24.dp))
            .padding(start = 16.dp, end = 4.dp)
            .heightIn(min = 48.dp)
            .testTag("shot_warning")
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            stringResource(message),
            Modifier.weight(1f, fill = false),
            style = TextStyle(color = Color.White, fontSize = 13.sp),
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Box(
            Modifier
                .padding(start = 8.dp)
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button) {
                    Haptics.segment(view)
                    actions.dismissShotWarning()
                    actions.shutter()
                }
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                stringResource(R.string.shot_check_retake),
                style = TextStyle(color = s.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
            )
        }
    }
}

private const val SHOT_WARNING_MS = 5000L

const val LOOK_COLOR_MATCH = "__color_match__"

private const val MODE_DIP = 0.32f

/** Keeps labels readable over a bright preview (contrast against white frames). */
val LabelShadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = .75f), Offset(0f, 1f), 6f)

/** A stand-in swatch per look (landscape-like gradient tinted by the look id). */
private fun lookSwatch(id: String, index: Int): Brush {
    if (id == "none") return Brush.verticalGradient(listOf(Color(0xFF6A8FB8), Color(0xFFC9B28D), Color(0xFF3B2D24)))
    if (id == PhotoStyles.LOOK_ID) return Brush.linearGradient(listOf(Color(0xFF2B3A55), Color(0xFFE8B86D), Color(0xFFD9534F)))
    if (id == LOOK_COLOR_MATCH) return Brush.sweepGradient(listOf(Color(0xFFFF7A45), Color(0xFF4C8DFF), Color(0xFF34C759), Color(0xFFFF7A45)))
    val hue = ((id.hashCode() and 0x7fffffff) % 360).toFloat()
    val mono = id.contains("mono") || id.contains("noir") || id.contains("bw")
    val top = if (mono) Color(0xFF9A9A9A) else Color.hsv(hue, .45f, .85f)
    val bottom = if (mono) Color(0xFF2A2A2A) else Color.hsv((hue + 30f + index * 7) % 360f, .55f, .35f)
    return Brush.verticalGradient(listOf(top, bottom))
}

@Composable
fun SheetTitle(text: String) {
    BasicText(
        text, Modifier.padding(bottom = 12.dp),
        style = TextStyle(color = skin.fg, fontSize = skin.tokens.type.titleSize.sp, fontWeight = FontWeight.SemiBold),
    )
}



/**
 * The first composition of [ProPanel] costs about 100 ms of measure and layout (class loading,
 * text layout, JIT), which landed on the first swipe into Pro. Once the preview has settled the
 * panel is composed and measured a single time without being placed, so it never draws or takes
 * input, then dropped.
 */
@Composable
private fun ProPanelWarmUp(pro: ProUiState, actions: CameraUiActions) {
    var phase by remember { mutableIntStateOf(if (proWarmedUp) 2 else 0) }
    if (phase == 2) return
    LaunchedEffect(Unit) {
        delay(PRO_WARM_UP_DELAY_MS)
        phase = 1
        withFrameNanos { }
        withFrameNanos { }
        proWarmedUp = true
        phase = 2
    }
    if (phase == 1) {
        Box(
            Modifier
                .layout { measurable, constraints ->
                    measurable.measure(constraints)
                    layout(0, 0) {}
                }
                .clearAndSetSemantics {},
        ) { ProPanel(pro, actions) }
    }
}

private var proWarmedUp = false
private const val PRO_WARM_UP_DELAY_MS = 1500L
