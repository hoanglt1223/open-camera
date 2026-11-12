/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.ui.overlay

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.opencamera.app.ui.skin.BlurPolicy
import dev.opencamera.app.ui.skin.SkinTokens
import dev.opencamera.app.ui.skin.Skins

/** Compose view of [SkinTokens] plus the runtime choices (blur, motion scale). */
class Skin(val tokens: SkinTokens, val blur: BlurPolicy, animatorScale: Float) {
    private val c = tokens.colors
    val id get() = tokens.id
    val glass get() = tokens.shape.glass
    val accent = Color(c.accent)
    val accentBrush: Brush = c.accentEnd?.let { Brush.linearGradient(listOf(Color(c.accent), Color(it))) }
        ?: Brush.linearGradient(listOf(accent, accent))
    /** Readable accent for text and thin strokes (the light end of a gradient). */
    val accentText = Color(c.accentEnd ?: c.accent)
    val fg = Color(c.foreground)
    val muted = Color(c.muted)
    val surface = Color(c.surface)
    val sheet = Color(c.sheet)
    val chip = Color(c.chip)
    val stroke = Color(c.stroke)
    val modeOn = Color(c.modeOn)
    val switchOn = Color(c.switchOn)
    val record = Color(c.record)
    val radiusS = tokens.radii.small.dp
    val radiusM = tokens.radii.medium.dp
    val radiusL = tokens.radii.large.dp
    val modeWeight = FontWeight(tokens.type.modeWeight)
    // Compose already stretches every animation by the system animator duration scale; scaling
    // the tokens as well would apply it twice. Only "animations off" (0) is folded in here, so
    // springs and the zero-duration checks below see it too.
    val motion = tokens.scaledMotion(if (animatorScale <= 0f) 0f else 1f)
    private val easing = motion.easing.let { CubicBezierEasing(it[0], it[1], it[2], it[3]) }

    fun <T> fast(): FiniteAnimationSpec<T> = tween(motion.fastMs, easing = easing)
    fun <T> medium(): FiniteAnimationSpec<T> = tween(motion.mediumMs, easing = easing)
    fun <T> slow(): FiniteAnimationSpec<T> = tween(motion.slowMs, easing = easing)

    /**
     * The one transition of the camera screen: a fade plus a short upward slide. Used for the
     * panels (looks, Pro, sheets) and the lens row / Pro panel swap on a mode change; the mode
     * and lens pills slide with [bouncy]. [offset] maps the content height to the slide distance.
     */
    fun enter(offset: (Int) -> Int = { it / 3 }): EnterTransition =
        fadeIn(medium()) + slideInVertically(medium(), offset)

    fun exit(offset: (Int) -> Int = { it / 3 }): ExitTransition =
        fadeOut(medium()) + slideOutVertically(medium(), offset)

    /** Lens chips, mode pill and thumbnail: a spring on Origin, the standard curve on Glass. */
    fun <T> bouncy(): FiniteAnimationSpec<T> = if (tokens.motion.springy && motion.mediumMs > 0) {
        spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow)
    } else {
        medium()
    }
}

val LocalSkin = staticCompositionLocalOf { Skin(Skins.DEFAULT, BlurPolicy.NONE, 1f) }

val skin: Skin
    @Composable @ReadOnlyComposable get() = LocalSkin.current

/** Haptic map of the camera; no VIBRATE permission needed. */
object Haptics {
    fun tick(view: View) = perform(
        view, if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK,
    )

    fun segment(view: View) = perform(
        view, if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.KEYBOARD_TAP,
    )

    fun shutter(view: View) = perform(
        view, if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY,
    )

    fun recordStart(view: View) = perform(view, HapticFeedbackConstants.LONG_PRESS)

    fun recordStop(view: View) = perform(
        view, if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY,
    )

    fun longPress(view: View) = perform(view, HapticFeedbackConstants.LONG_PRESS)

    fun reject(view: View) = perform(
        view, if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS,
    )

    private fun perform(view: View, constant: Int) {
        runCatching { view.performHapticFeedback(constant) }
    }
}
