/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.ui.overlay

import android.graphics.Bitmap
import android.os.Build
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.opencamera.app.ui.skin.BlurPolicy
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Glass: the screen outside the preview box (status strip, bottom controls) shows the live
 * scene frosted, so the bars read as glass over the camera instead of black. The frost is a
 * small asynchronous PixelCopy of the preview taken about six times a second. With
 * [BlurPolicy.FULL] (API 31+) a larger sample is blurred on the GPU with a RenderEffect; otherwise
 * a tiny sample is stretched with bilinear filtering, which costs nothing but shows banding on
 * high-contrast scenes.
 */
@Composable
fun BoxScope.GlassBackdrop(rect: RectF?, actions: CameraUiActions) {
    CountRecompositions("GlassBackdrop")
    val s = skin
    if (!s.glass || s.blur == BlurPolicy.NONE || rect == null || rect.isEmpty) return
    val view = LocalView.current
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    val drawn = remember { mutableIntStateOf(0) }
    val gpuBlur = s.blur == BlurPolicy.FULL && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val sampleWidth = if (gpuBlur) GPU_SAMPLE_WIDTH else SAMPLE_WIDTH
    val aspect = rect.height() / rect.width().coerceAtLeast(1f)
    LaunchedEffect(aspect, sampleWidth) {
        val owner = view.findViewTreeLifecycleOwner() ?: return@LaunchedEffect
        val h = (sampleWidth * aspect).roundToInt().coerceIn(1, sampleWidth * 4)
        // Two buffers: the one on screen is never written while it is drawn.
        val buffers = Array(2) { Bitmap.createBitmap(sampleWidth, h, Bitmap.Config.ARGB_8888) }
        var next = 0
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val target = buffers[next]
                if (actions.previewSample(target)) {
                    frame = target
                    drawn.intValue++
                    next = 1 - next
                }
                delay(SAMPLE_INTERVAL_MS)
            }
        }
    }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val feather = with(density) { FEATHER_DP.dp.toPx() }
    val blurPx = with(density) { BLUR_DP.dp.toPx() }
    Box(Modifier.matchParentSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        Canvas(
            Modifier.matchParentSize().then(
                if (gpuBlur) Modifier.graphicsLayer { renderEffect = BlurEffect(blurPx, blurPx, TileMode.Clamp) } else Modifier,
            ),
        ) {
            drawn.intValue // redraw, without recomposing, when a new sample lands
            val b = frame ?: return@Canvas
            val img = b.asImageBitmap()
            val H = size.height
            // The blurred frame continues the scene past the preview's edges: its top and bottom
            // rows are stretched over the strips above and below, so the preview reads as full-bleed.
            fun band(srcTop: Float, srcBottom: Float, dstTop: Float, dstBottom: Float) {
                if (dstBottom <= dstTop) return
                val sy = (srcTop * b.height).roundToInt().coerceIn(0, b.height - 1)
                val sh = ((srcBottom - srcTop) * b.height).roundToInt().coerceIn(1, b.height - sy)
                drawImage(
                    img, IntOffset(0, sy), IntSize(b.width, sh),
                    IntOffset(rect.left.roundToInt(), dstTop.roundToInt()),
                    IntSize(rect.width().roundToInt(), (dstBottom - dstTop).roundToInt()),
                    filterQuality = FilterQuality.Low,
                )
            }
            // The blur reaches about one radius past each band, so overlap the preview a little more.
            val reach = if (gpuBlur) blurPx else 0f
            band(0f, EDGE_BAND, 0f, rect.top + feather + reach)
            band(1f - EDGE_BAND * 2, 1f, rect.bottom - feather - reach, H)
        }
        // Drawn into the same offscreen layer, so DstIn masks the blurred bands as well.
        Canvas(Modifier.matchParentSize()) {
            if (frame == null) return@Canvas
            val H = size.height
            drawRect(Color.Black.copy(alpha = FROST_TINT))
            drawRect(Color.White.copy(alpha = FROST_LIGHT))
            // Fade the frost out over the preview's edges and keep the preview itself clear.
            fun f(y: Float) = (y / H).coerceIn(0f, 1f)
            drawRect(
                Brush.verticalGradient(
                    0f to Color.Black, f(rect.top) to Color.Black, f(rect.top + feather) to Color.Transparent,
                    f(rect.bottom - feather) to Color.Transparent, f(rect.bottom) to Color.Black, 1f to Color.Black,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
    }
}

private const val SAMPLE_WIDTH = 18
private const val GPU_SAMPLE_WIDTH = 72
private const val BLUR_DP = 24
private const val SAMPLE_INTERVAL_MS = 160L
private const val FROST_TINT = 0.22f
private const val FROST_LIGHT = 0.04f
private const val EDGE_BAND = 0.12f
private const val FEATHER_DP = 56
