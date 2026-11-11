/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.develop.ui.compose

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import dev.opencamera.app.R
import dev.opencamera.app.develop.DevelopActivity
import dev.opencamera.app.develop.DevelopActivity.Mode
import dev.opencamera.app.develop.tools.RetouchTools
import dev.opencamera.app.retouch.RetouchActivity
import dev.opencamera.app.ui.overlay.Haptics
import dev.opencamera.app.ui.overlay.pressable
import dev.opencamera.app.ui.overlay.skin
import dev.opencamera.pipeline.retouch.BlemishTool
import dev.opencamera.pipeline.retouch.InputKind
import dev.opencamera.pipeline.retouch.LensProfiles
import dev.opencamera.pipeline.retouch.ReshapeTool
import dev.opencamera.pipeline.retouch.RetouchParams
import dev.opencamera.pipeline.retouch.RetouchResult
import dev.opencamera.pipeline.retouch.RetouchTool
import dev.opencamera.pipeline.retouch.RetouchUnavailable
import dev.opencamera.pipeline.retouch.WhiteBalanceTool
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/** Retouch tools and models are not thread-safe: every run goes through one worker. */
private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "editor-retouch") }.asCoroutineDispatcher()

/** Results of one edit with several methods, shown side by side (object removal, white balance, geometry). */
class RetouchGrid(val before: Bitmap, val labels: List<String>, val onPick: (Int) -> Unit, val onCancel: () -> Unit) {
    val results = mutableStateListOf<Bitmap?>().apply { repeat(labels.size) { add(null) } }
    val notes = mutableStateListOf<String?>().apply { repeat(labels.size) { add(null) } }
    val failed = mutableStateListOf<Boolean>().apply { repeat(labels.size) { add(false) } }
    var zoomed by mutableStateOf(-1)
}

private fun reasonOf(a: DevelopActivity, e: Throwable) = when (e) {
    is RetouchUnavailable -> a.getString(R.string.retouch_unavailable, e.message)
    is OutOfMemoryError -> a.getString(R.string.retouch_unavailable, "out of memory")
    else -> a.getString(R.string.ai_editor_failed)
}

private fun runTool(a: DevelopActivity, tool: RetouchTool, params: RetouchParams, label: String) {
    val src = a.retouchSource() ?: return
    a.ui.busy.value = true
    a.lifecycleScope.launch {
        val t0 = SystemClock.elapsedRealtime()
        val r = withContext(worker) { runCatching { applyOnCopy(tool, src, params) } }
        a.ui.busy.value = false
        r.onSuccess { res ->
            Log.i("EditorRetouch", "${tool.id} ${res.method} ${SystemClock.elapsedRealtime() - t0} ms")
            a.commitToolResult(RetouchTools.step(tool.id, res.resolved ?: params), label, res.bitmap)
            a.ui.clearRetouchInput()
            a.toast(a.getString(R.string.retouch_applied, res.method, res.note.orEmpty()))
        }.onFailure { a.toast(reasonOf(a, it)) }
    }
}

/** Runs [tool] on a mutable copy of [src]; the copy is recycled when it is not the result. */
private fun applyOnCopy(tool: RetouchTool, src: Bitmap, params: RetouchParams): RetouchResult {
    val copy = src.copy(Bitmap.Config.ARGB_8888, true)
    val result = try {
        tool.apply(copy, params)
    } catch (e: Throwable) {
        copy.recycle()
        throw e
    }
    if (result.bitmap !== copy) copy.recycle()
    return result
}

/** Comparison results the user did not keep; recycled once the grid is no longer drawn. */
private fun recycleAfterFrame(a: DevelopActivity, bitmaps: List<Bitmap>) {
    if (bitmaps.isEmpty()) return
    a.lifecycleScope.launch {
        kotlinx.coroutines.android.awaitFrame()
        kotlinx.coroutines.android.awaitFrame()
        bitmaps.forEach { if (!it.isRecycled) it.recycle() }
    }
}

/** Runs every available method on copies and opens the comparison grid; fewer than two methods apply directly. */
private fun compare(a: DevelopActivity, tool: RetouchTool, base: RetouchParams, label: String) {
    val src = a.retouchSource() ?: return
    val methods = tool.methods().filter { it.available }.take(4)
    if (methods.size < 2) {
        runTool(a, tool, base, label)
        return
    }
    val strict = base.copy(values = base.values + ("strict" to 1f))
    val resolved = arrayOfNulls<RetouchResult>(methods.size)
    var job: Job? = null
    val grid = RetouchGrid(src, methods.map { it.id }, onPick = { i ->
        val r = resolved[i] ?: return@RetouchGrid
        a.commitToolResult(RetouchTools.step(tool.id, r.resolved ?: strict.copy(method = methods[i].id)), label, r.bitmap)
        a.ui.clearRetouchInput()
        a.ui.grid.value = null
        recycleAfterFrame(a, resolved.filterIndexed { k, other -> k != i && other != null && other.bitmap !== r.bitmap }.map { it!!.bitmap })
    }, onCancel = {
        job?.cancel()
        a.ui.grid.value = null
        recycleAfterFrame(a, resolved.mapNotNull { it?.bitmap })
    })
    a.ui.grid.value = grid
    job = a.lifecycleScope.launch {
        methods.forEachIndexed { i, m ->
            val t0 = SystemClock.elapsedRealtime()
            val r = withContext(worker) {
                if (!isActive) return@withContext null
                runCatching { applyOnCopy(tool, src, strict.copy(method = m.id)) }
            } ?: return@launch
            r.onSuccess {
                resolved[i] = it
                grid.results[i] = it.bitmap
                grid.notes[i] = "${SystemClock.elapsedRealtime() - t0} ms"
            }.onFailure {
                Log.w("EditorRetouch", "Compare ${tool.id} ${m.id} dropped: ${it.message}")
                grid.failed[i] = true
                grid.notes[i] = reasonOf(a, it)
            }
        }
    }
}

@Composable
internal fun RetouchPanel(a: DevelopActivity) {
    val ui = a.ui
    val box = RetouchTools.toolbox()
    if (!ui.retouchAvailable.value || box == null) {
        PanelLabel(stringResource(R.string.editor_retouch_unavailable))
        ChipRow(listOf(stringResource(R.string.editor_classic_retouch)), { it }, { false }) { _, _ ->
            a.info?.uri?.let { uri ->
                runCatching { a.startActivity(Intent(Intent.ACTION_EDIT).setDataAndType(uri, "image/jpeg").setClass(a, RetouchActivity::class.java).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
            }
        }
        return
    }
    var showReshape by remember { mutableStateOf(false) }
    val tools = box.tools.sortedBy { RetouchTools.ORDER.indexOf(it.id) }.filter { it.id != "reshape" || showReshape }
    var selected by remember { mutableStateOf("erase") }
    val tool = tools.firstOrNull { it.id == selected } ?: tools.first()
    val label = stringResource(RetouchTools.labelOf(tool.id))
    val values = remember(tool.id) { mutableStateOf(mapOf<String, Float>()) }
    var strength by remember(tool.id) { mutableFloatStateOf(tool.defaultStrength) }
    var tapSelect by remember(tool.id) { mutableStateOf(false) }

    DisposableEffect(tool.id, tapSelect) {
        ui.clearRetouchInput()
        val mode = when {
            tool.id == "erase" && !tapSelect -> Mode.RETOUCH_BRUSH
            tool.input == InputKind.TAP || tool.id == "erase" || tool.id == "redeye" || tool.id == "whitebalance" -> Mode.RETOUCH_TAP
            tool.id == "reshape" && values.value.containsKey("mode") -> Mode.RETOUCH_BRUSH
            else -> Mode.NONE
        }
        a.switchMode(mode)
        ui.onRetouchTap = when (tool.id) {
            "blemish" -> { p -> runTool(a, tool, RetouchParams(taps = listOf(p), values = values.value), label) }
            "redeye" -> { p -> runTool(a, tool, RetouchParams(taps = listOf(p)), label) }
            "whitebalance" -> { p -> runTool(a, tool, RetouchParams(method = WhiteBalanceTool.TAP, strength = strength, taps = listOf(p)), label) }
            else -> null
        }
        onDispose { ui.onRetouchTap = null; ui.clearRetouchInput(); if (a.mode == Mode.RETOUCH_BRUSH || a.mode == Mode.RETOUCH_TAP) a.switchMode(Mode.NONE) }
    }

    val reason = remember(tool.id) { tool.unavailableReason() }
    if (reason != null) {
        PanelLabel(a.getString(R.string.retouch_unavailable, reason))
    } else when (tool.id) {
        "erase" -> {
            RulerDial(stringResource(R.string.editor_brush_size), ui.retouchBrush.floatValue * 1000f, 5f, 65f, 1f, 30f, { "${it.toInt()}" },
                { ui.retouchBrush.floatValue = it / 1000f }, {})
            val acts = listOf(
                stringResource(R.string.editor_brush) to { tapSelect = false; ui.retouchErase.value = false },
                stringResource(R.string.editor_unbrush) to { tapSelect = false; ui.retouchErase.value = true },
                stringResource(R.string.editor_tap_select) to { tapSelect = true },
                stringResource(R.string.editor_clear) to { ui.clearRetouchInput() },
                stringResource(R.string.editor_remove) + " ▸" to {
                    if (ui.retouchStrokes.isEmpty() && ui.retouchTaps.isEmpty()) a.toast(a.getString(R.string.retouch_hint_brush))
                    else compare(a, tool, RetouchParams(strokes = ui.retouchStrokes.toList(), taps = ui.retouchTaps.toList()), label)
                },
            )
            ChipRow(acts.indices.toList(), { acts[it].first }, { i -> (i == 0 && !tapSelect && !ui.retouchErase.value) || (i == 1 && !tapSelect && ui.retouchErase.value) || (i == 2 && tapSelect) || i == 4 }) { _, i -> acts[i].second() }
        }
        "blemish" -> {
            val v = values.value["radius"]?.let { (it / BlemishTool.DEFAULT_RADIUS - 0.4f) / 2f * 100f } ?: 30f
            RulerDial(stringResource(R.string.retouch_spot_size), v, 0f, 100f, 1f, 30f, { "${it.toInt()}" },
                { values.value = mapOf("radius" to BlemishTool.DEFAULT_RADIUS * (0.4f + it / 100f * 2f)) }, {})
            PanelLabel(stringResource(R.string.retouch_hint_tap))
        }
        "skin", "teeth", "eyes" -> {
            RulerDial(stringResource(R.string.editor_strength), strength * 100f, 0f, tool.maxStrength * 100f, 1f, tool.defaultStrength * 100f, { "${it.toInt()}" },
                { strength = it / 100f }, {})
            ChipRow(listOf(stringResource(R.string.editor_apply)), { it }, { true }) { _, _ -> runTool(a, tool, RetouchParams(strength = strength), label) }
        }
        "redeye" -> {
            PanelLabel(stringResource(R.string.retouch_hint_tap))
            ChipRow(listOf(stringResource(R.string.retouch_auto)), { it }, { true }) { _, _ -> runTool(a, tool, RetouchParams(), label) }
        }
        "whitebalance" -> {
            RulerDial(stringResource(R.string.editor_strength), strength * 100f, 0f, 100f, 1f, 100f, { "${it.toInt()}" }, { strength = it / 100f }, {})
            ChipRow(listOf(stringResource(R.string.retouch_compare) + " ▸"), { it }, { true }) { _, _ -> compare(a, tool, RetouchParams(strength = strength), label) }
        }
        "geometry" -> GeometryControls(a, tool, values, label)
        "reshape" -> ReshapeControls(a, tool, values, label)
    }
    val reshapeLabel = stringResource(R.string.editor_reshape_show)
    ChipRow(tools.map { it.id } + "__reshape", { if (it == "__reshape") reshapeLabel else stringResource(RetouchTools.labelOf(it)) },
        { it == tool.id || (it == "__reshape" && showReshape) }, { id -> a.stack.tools.any { it.toolId == id } }) { _, id ->
        if (id == "__reshape") { showReshape = !showReshape; if (!showReshape && selected == "reshape") selected = "erase" } else selected = id
    }
}

@Composable
private fun GeometryControls(a: DevelopActivity, tool: RetouchTool, values: androidx.compose.runtime.MutableState<Map<String, Float>>, label: String) {
    var which by remember { mutableStateOf("angle") }
    val lens = remember { a.retouchSource()?.let { LensProfiles.forPhoto(a, a.info?.let { i -> runCatching { a.contentResolver.openInputStream(i.uri)?.use { androidx.exifinterface.media.ExifInterface(it) } }.getOrNull() }, it.width, it.height) } }
    val ranges = mapOf("angle" to 15f, "vertical" to 1f, "horizontal" to 1f, "k1" to 0.2f)
    val r = ranges.getValue(which)
    val step = if (which == "angle") 0.1f else 0.01f
    val names = mapOf("angle" to stringResource(R.string.retouch_angle), "vertical" to stringResource(R.string.retouch_vertical),
        "horizontal" to stringResource(R.string.retouch_horizontal), "k1" to stringResource(R.string.retouch_lens_manual))
    RulerDial(names.getValue(which), values.value[which] ?: 0f, -r, r, step, 0f, { formatValue(it, step, if (which == "angle") "°" else "") },
        { values.value = values.value + (which to it) }, {})
    val acts = buildList {
        addAll(listOf("angle", "vertical", "horizontal"))
        if (lens == null) add("k1") else add("lens")
        add("auto"); add("apply")
    }
    ChipRow(acts, {
        when (it) {
            "lens" -> stringResource(R.string.retouch_lens_profile)
            "auto" -> stringResource(R.string.retouch_auto) + " ▸"
            "apply" -> stringResource(R.string.editor_apply)
            else -> names.getValue(it)
        }
    }, { it == which }, { values.value.containsKey(it) }) { _, id ->
        when (id) {
            "lens" -> values.value = values.value + lens.orEmpty()
            "auto" -> compare(a, tool, RetouchParams(values = values.value), label)
            "apply" -> runTool(a, tool, RetouchParams(values = values.value), label)
            else -> which = id
        }
    }
}

@Composable
private fun ReshapeControls(a: DevelopActivity, tool: RetouchTool, values: androidx.compose.runtime.MutableState<Map<String, Float>>, label: String) {
    var which by remember { mutableStateOf("slim") }
    PanelLabel(stringResource(R.string.retouch_reshape_notice))
    val names = mapOf("slim" to stringResource(R.string.retouch_slim_face), "eyes" to stringResource(R.string.retouch_bigger_eyes), "nose" to stringResource(R.string.retouch_slim_nose))
    if (which in names) {
        RulerDial(names.getValue(which), (values.value[which] ?: 0f) * 100f, 0f, 100f, 1f, 0f, { "${it.toInt()}" },
            { values.value = values.value - "mode" + (which to it / 100f) }, {})
    }
    val modes = mapOf("push" to ReshapeTool.MODE_PUSH, "bloat" to ReshapeTool.MODE_BLOAT, "pinch" to ReshapeTool.MODE_PINCH)
    val acts = listOf("slim", "eyes", "nose", "push", "bloat", "pinch", "apply")
    ChipRow(acts, {
        when (it) {
            "push" -> stringResource(R.string.retouch_liquify_push)
            "bloat" -> stringResource(R.string.retouch_liquify_bloat)
            "pinch" -> stringResource(R.string.retouch_liquify_pinch)
            "apply" -> stringResource(R.string.editor_apply)
            else -> names.getValue(it)
        }
    }, { it == which }) { _, id ->
        when (id) {
            in modes -> {
                which = id
                values.value = mapOf("mode" to modes.getValue(id).toFloat())
                a.ui.clearRetouchInput()
                a.ui.retouchBrush.floatValue = 0.06f
                a.switchMode(Mode.RETOUCH_BRUSH)
            }
            "apply" -> {
                val strokes = a.ui.retouchStrokes.toList()
                val p = if (strokes.isNotEmpty() && values.value.containsKey("mode")) RetouchParams(strength = 0.5f, strokes = strokes, values = values.value)
                else RetouchParams(values = values.value - "mode")
                runTool(a, tool, p, label)
            }
            else -> { which = id; a.switchMode(Mode.NONE) }
        }
    }
}

/** Comparison grid (2-4 results) with a draggable before/after divider on the chosen result. */
@Composable
internal fun RetouchGridOverlay(grid: RetouchGrid) {
    val s = skin
    val view = LocalView.current
    Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding().pointerInput(Unit) { detectTapGestures { } }) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText(stringResource(if (grid.zoomed < 0) R.string.editor_results else R.string.editor_before_after), Modifier.weight(1f),
                style = TextStyle(color = s.fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold))
            EditorIcon(EditorGlyphs.close, stringResource(R.string.retouch_cancel)) { if (grid.zoomed >= 0) grid.zoomed = -1 else grid.onCancel() }
        }
        val z = grid.zoomed
        if (z < 0) {
            val shown = grid.labels.indices.filter { !grid.failed[it] }
            Column(Modifier.weight(1f).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                shown.chunked(2).forEach { rowItems ->
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowItems.forEach { i ->
                            val res = grid.results[i]
                            val d = "${grid.labels[i]} ${grid.notes[i].orEmpty()}"
                            Box(
                                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(s.radiusS)).background(s.chip)
                                    .semantics { contentDescription = d; role = Role.Button }
                                    .pressable(res != null) { Haptics.segment(view); grid.zoomed = i },
                            ) {
                                Image((res ?: grid.before).asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, alpha = if (res == null) 0.35f else 1f)
                                BasicText(if (res == null) "${grid.labels[i]} …" else d, Modifier.align(Alignment.BottomStart).background(Color(0x99000000)).padding(6.dp),
                                    style = TextStyle(color = Color.White, fontSize = 12.sp))
                            }
                        }
                        if (rowItems.size == 1) Box(Modifier.weight(1f))
                    }
                }
            }
        } else {
            val after = grid.results[z]
            if (after != null) SplitCompare(grid.before, after, Modifier.weight(1f).fillMaxWidth().padding(8.dp))
            val use = stringResource(R.string.editor_use_result, grid.labels[z])
            ChipRow(listOf(use, stringResource(R.string.retouch_back_to_grid)), { it }, { it == use }) { i, _ ->
                if (i == 0) { Haptics.shutter(view); grid.onPick(z) } else grid.zoomed = -1
            }
        }
    }
}

@Composable
private fun SplitCompare(before: Bitmap, after: Bitmap, modifier: Modifier) {
    val s = skin
    var split by remember { mutableFloatStateOf(0.5f) }
    val beforeImg = remember(before) { before.asImageBitmap() }
    val afterImg = remember(after) { after.asImageBitmap() }
    val desc = stringResource(R.string.editor_before_after)
    BoxWithConstraints(modifier.semantics { contentDescription = desc }.pointerInput(Unit) {
        detectDragGestures { ch, _ -> ch.consume(); split = (ch.position.x / size.width).coerceIn(0f, 1f) }
    }) {
        Image(afterImg, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        Image(beforeImg, null, Modifier.fillMaxSize().drawWithContent { clipRect(right = size.width * split) { this@drawWithContent.drawContent() } }, contentScale = ContentScale.Fit)
        val x = with(LocalDensity.current) { (constraints.maxWidth * split).toDp() }
        Box(Modifier.offset(x = x - 1.dp).width(2.dp).fillMaxHeight().background(s.accent))
    }
}
