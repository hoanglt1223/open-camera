/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.develop.ui.compose

import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import dev.opencamera.app.develop.model.EditHistory
import dev.opencamera.app.develop.ui.DevelopTheme
import dev.opencamera.app.ui.skin.SkinTokens
import dev.opencamera.pipeline.retouch.NPoint
import dev.opencamera.pipeline.retouch.Stroke

/** Editor categories in dock order. */
enum class Category { AUTO, ADJUST, LOOKS, CROP, RETOUCH, MASKS, COLOR, EXPORT }

/**
 * Observable state the Compose editor reads. The activity stays the owner of the edit stack and
 * engine; it bumps [revision] whenever the stack or history changes so composables re-read it.
 */
class EditorUiState {
    val revision = mutableIntStateOf(0)
    val canUndo = mutableStateOf(false)
    val canRedo = mutableStateOf(false)
    val status = mutableStateOf("")
    val ready = mutableStateOf(false)
    val raw = mutableStateOf(false)
    val busy = mutableStateOf(false)
    val showingOriginal = mutableStateOf(false)
    val retouchAvailable = mutableStateOf(false)
    val category = mutableStateOf(Category.ADJUST)
    val grid = mutableStateOf<RetouchGrid?>(null)

    // Retouch input collected on the photo.
    val retouchBrush = mutableFloatStateOf(0.03f)
    val retouchErase = mutableStateOf(false)
    val retouchStrokes = mutableStateListOf<Stroke>()
    val retouchTaps = mutableStateListOf<NPoint>()
    val liveStroke = mutableStateOf<List<NPoint>>(emptyList())
    /** Tap handler of the active retouch tool (blemish, red eye, white balance); null collects taps. */
    var onRetouchTap: ((NPoint) -> Unit)? = null

    fun retouchTap(p: FloatArray) {
        val n = NPoint(p[0].coerceIn(0f, 1f), p[1].coerceIn(0f, 1f))
        onRetouchTap?.invoke(n) ?: retouchTaps.add(n)
    }

    fun clearRetouchInput() {
        retouchStrokes.clear()
        retouchTaps.clear()
        liveStroke.value = emptyList()
    }

    fun changed(history: EditHistory) {
        canUndo.value = history.canUndo
        canRedo.value = history.canRedo
        revision.intValue++
    }

    fun loaded(raw: Boolean) {
        this.raw.value = raw
        ready.value = true
        revision.intValue++
    }
}

/** Editor colours derived from the camera skin, for the View parts (dialogs, overlay hints). */
fun skinDevelopTheme(t: SkinTokens): DevelopTheme = object : DevelopTheme {
    override val background = 0xff000000.toInt()
    override val surface = t.colors.sheet or 0xff000000.toInt()
    override val onSurface = t.colors.foreground
    override val onSurfaceMuted = t.colors.muted
    override val accent = t.colors.accent
    override val onAccent = 0xff111111.toInt()
    override val warning = t.colors.record
    override val cornerRadiusDp = t.radii.medium
}
