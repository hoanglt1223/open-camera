/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.develop.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import dev.opencamera.app.R
import dev.opencamera.app.develop.DevelopActivity
import dev.opencamera.app.develop.DevelopActivity.Mode
import dev.opencamera.app.develop.mask.AiMaskProvider
import dev.opencamera.app.develop.model.BrushShape
import dev.opencamera.app.develop.model.LocalMask
import dev.opencamera.app.develop.model.LuminanceRangeShape
import dev.opencamera.app.develop.model.MaskComponent
import dev.opencamera.app.develop.model.MaskOp
import dev.opencamera.app.develop.model.MaskShape
import dev.opencamera.app.develop.model.Params
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Local masks: AI (subject, person, sky, background), brush, linear, radial, ranges; per-mask adjustments. */
@Composable
internal fun MasksPanel(a: DevelopActivity) {
    a.ui.revision.intValue
    val g = a.gesture
    var localKey by remember { mutableStateOf(Params.EXPOSURE) }
    DisposableEffect(Unit) {
        g.showMask = true
        a.updateOverlay()
        onDispose { g.showMask = false; a.switchMode(Mode.NONE); a.updateOverlay() }
    }
    if (a.selectedMask >= a.stack.masks.size) a.selectedMask = a.stack.masks.size - 1
    val mask = a.stack.masks.getOrNull(a.selectedMask)

    fun add(shape: MaskShape?, invert: Boolean = false) {
        if (a.stack.masks.size >= 4) { a.toast(a.getString(R.string.develop_mask_limit)); return }
        val id = "m" + System.currentTimeMillis().toString(36)
        val comps = listOfNotNull(shape?.let { MaskComponent(MaskOp.ADD, it, invert) })
        a.change(a.stack.copy(masks = a.stack.masks + LocalMask(id, a.getString(R.string.develop_mask_n, a.stack.masks.size + 1), comps)), "Add mask")
        a.seal()
        a.selectedMask = a.stack.masks.size - 1
        a.updateOverlay()
    }
    fun addAi(kind: String, label: String, invert: Boolean = false) {
        val i = a.info ?: return
        val reason = a.ai.unavailableReason(kind)
        if (reason != null) {
            val why = a.getString(if (reason == AiMaskProvider.Reason.NO_SKY_MODEL) R.string.develop_mask_no_sky_model else R.string.develop_mask_no_model)
            a.toast(a.getString(R.string.develop_mask_ai_missing, label, why)); return
        }
        val px = a.engine.proxyPixels() ?: return
        val pw = a.engine.proxyWidth
        val ph = a.engine.proxyHeight
        a.ui.busy.value = true
        a.status.text = a.getString(R.string.develop_mask_working, label)
        a.lifecycleScope.launch {
            val shape = withContext(Dispatchers.Default) {
                runCatching { a.ai.compute(kind, android.graphics.Bitmap.createBitmap(px, pw, ph, android.graphics.Bitmap.Config.ARGB_8888), a.store.assetDir(i.ref)) }.getOrNull()
            }
            a.ui.busy.value = false
            if (shape == null) a.toast(a.getString(R.string.develop_mask_ai_missing, label, a.getString(R.string.develop_mask_no_model))) else add(shape, invert)
        }
    }

    // Mask list followed by the "new mask" kinds; AI kinds without a model are greyed out.
    val newKinds = listOf(
        R.string.develop_mask_subject to { l: String -> addAi(AiMaskProvider.KIND_SUBJECT, l) },
        R.string.develop_mask_person to { l: String -> addAi(AiMaskProvider.KIND_PERSON, l) },
        R.string.develop_mask_sky to { l: String -> addAi(AiMaskProvider.KIND_SKY, l) },
        R.string.develop_mask_background to { l: String -> addAi(AiMaskProvider.KIND_SUBJECT, l, invert = true) },
        R.string.develop_mask_brush to { _: String -> add(BrushShape(emptyList())); a.switchMode(Mode.BRUSH) },
        R.string.develop_mask_linear to { _: String -> add(null); a.switchMode(Mode.LINEAR) },
        R.string.develop_mask_radial to { _: String -> add(null); a.switchMode(Mode.RADIAL) },
        R.string.develop_mask_luminance to { _: String -> add(LuminanceRangeShape(0.6f, 1f)) },
        R.string.develop_mask_color to { _: String -> add(null); a.switchMode(Mode.PICK_COLOR) },
    )
    val aiKinds = mapOf(R.string.develop_mask_subject to AiMaskProvider.KIND_SUBJECT, R.string.develop_mask_person to AiMaskProvider.KIND_PERSON,
        R.string.develop_mask_sky to AiMaskProvider.KIND_SKY, R.string.develop_mask_background to AiMaskProvider.KIND_SUBJECT)

    if (mask != null) {
        val label = paramLabel(localKey)
        val range = if (localKey == Params.EXPOSURE) 4f else 100f
        val step = if (localKey == Params.EXPOSURE) 0.02f else 1f
        RulerDial(label, mask.values[localKey] ?: 0f, -range, range, step, 0f, { formatValue(it, step) },
            onChange = { v ->
                val cur = a.stack.masks.firstOrNull { it.id == mask.id } ?: return@RulerDial
                g.replaceMask(cur.copy(values = cur.values + (localKey to v)), "Mask $label", "local-${mask.id}-$localKey")
            },
            onCommit = { a.seal() })
        ChipRow(Params.LOCAL, { paramLabel(it) }, { it == localKey }, { mask.values.containsKey(it) }) { _, k -> localKey = k }
        fun setLast(op: MaskOp? = null, invert: Boolean = false) {
            if (mask.components.isEmpty()) return
            val c = mask.components.toMutableList()
            c[c.size - 1] = c.last().let { if (invert) it.copy(invert = !it.invert) else it.copy(op = op ?: it.op) }
            g.replaceMask(mask.copy(components = c), "Mask combine"); a.seal()
        }
        val ops = listOf(
            stringResource(R.string.develop_mask_brush) to { a.switchMode(if (a.mode == Mode.BRUSH) Mode.NONE else Mode.BRUSH) },
            stringResource(R.string.develop_brush_erase) to { g.erase = !g.erase; a.switchMode(Mode.BRUSH) },
            stringResource(R.string.develop_mask_linear) to { a.switchMode(Mode.LINEAR) },
            stringResource(R.string.develop_mask_radial) to { a.switchMode(Mode.RADIAL) },
            stringResource(R.string.editor_mask_add) to { setLast(MaskOp.ADD) },
            stringResource(R.string.editor_mask_subtract) to { setLast(MaskOp.SUBTRACT) },
            stringResource(R.string.editor_mask_intersect) to { setLast(MaskOp.INTERSECT) },
            stringResource(R.string.editor_mask_invert) to { setLast(invert = true) },
            stringResource(R.string.editor_mask_overlay) to { g.showMask = !g.showMask; a.updateOverlay() },
            stringResource(R.string.develop_mask_enabled) to { g.replaceMask(mask.copy(enabled = !mask.enabled), "Mask on/off"); a.seal() },
            stringResource(R.string.editor_mask_delete) to {
                a.change(a.stack.copy(masks = a.stack.masks.filter { it.id != mask.id }), "Delete mask"); a.seal()
                a.selectedMask = a.stack.masks.size - 1; a.switchMode(Mode.NONE)
            },
        )
        val modeOf = mapOf(0 to Mode.BRUSH, 2 to Mode.LINEAR, 3 to Mode.RADIAL)
        ChipRow(ops.indices.toList(), { ops[it].first }, { i ->
            modeOf[i] == a.mode || (i == 1 && g.erase) || (i == 8 && g.showMask) || (i == 9 && mask.enabled)
        }) { _, i -> ops[i].second(); a.ui.revision.intValue++ }
    }
    val listItems: List<Any> = a.stack.masks + newKinds.map { it.first }
    val newLabel = stringResource(R.string.editor_mask_new)
    ChipRow(listItems, { item ->
        if (item is LocalMask) item.name else "+ " + stringResource(item as Int)
    }, { it is LocalMask && a.stack.masks.indexOf(it) == a.selectedMask }, { it is LocalMask && it.values.isNotEmpty() },
        enabled = { it !is Int || aiKinds[it]?.let { k -> a.ai.unavailableReason(k) == null } ?: true }) { _, item ->
        if (item is LocalMask) {
            a.selectedMask = a.stack.masks.indexOf(item); a.updateOverlay(); a.ui.revision.intValue++
        } else {
            val res = item as Int
            newKinds.first { it.first == res }.second(a.getString(res).ifEmpty { newLabel })
        }
    }
    g.onColorPick = { hue, sat -> a.stack.masks.getOrNull(a.selectedMask)?.let { g.pickColorFor(it, hue, sat) } }
}
