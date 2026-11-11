/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.develop

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CompoundButton
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import dev.opencamera.app.R
import dev.opencamera.app.develop.DevelopActivity.Mode
import dev.opencamera.app.develop.auto.AutoAdjust
import dev.opencamera.app.develop.mask.AiMaskProvider
import dev.opencamera.app.develop.model.BrushShape
import dev.opencamera.app.develop.model.ColorRangeShape
import dev.opencamera.app.develop.model.EditStack
import dev.opencamera.app.develop.model.Group
import dev.opencamera.app.develop.model.LocalMask
import dev.opencamera.app.develop.model.LuminanceRangeShape
import dev.opencamera.app.develop.model.MaskComponent
import dev.opencamera.app.develop.model.MaskOp
import dev.opencamera.app.develop.model.MaskShape
import dev.opencamera.app.develop.model.Param
import dev.opencamera.app.develop.model.Params
import dev.opencamera.app.develop.model.ToolStep
import dev.opencamera.app.develop.preset.Preset
import dev.opencamera.app.develop.render.Geometry
import dev.opencamera.app.develop.tools.ToolHost
import dev.opencamera.app.develop.tools.ToolRegistry
import dev.opencamera.app.develop.ui.ColorWheelView
import dev.opencamera.app.develop.ui.CurveView
import dev.opencamera.app.develop.ui.ParamSlider
import dev.opencamera.app.develop.ui.dp
import dev.opencamera.app.processing.ProcessingService
import dev.opencamera.pipeline.ai.AiOps
import dev.opencamera.pipeline.watermark.WatermarkTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Builds the tool panels below the preview. One instance per panel switch. */
class DevelopPanels(private val a: DevelopActivity) {
    private val panel = a.panel
    private val theme = a.theme

    companion object {
        val TABS: List<Pair<Int, DevelopPanels.() -> Unit>> = listOf(
            R.string.develop_tab_auto to { auto() },
            R.string.develop_tab_presets to { presets() },
            R.string.develop_tab_light to { group(Group.LIGHT) },
            R.string.develop_tab_color to { group(Group.COLOR) },
            R.string.develop_tab_curve to { curve() },
            R.string.develop_tab_hsl to { hsl() },
            R.string.develop_tab_grading to { grading() },
            R.string.develop_tab_detail to { detail() },
            R.string.develop_tab_effects to { group(Group.EFFECTS) },
            R.string.develop_tab_crop to { crop() },
            R.string.develop_tab_masks to { masks() },
            R.string.develop_tab_retouch to { retouch() },
        )

        private val LABELS = mapOf(
            Params.EXPOSURE to R.string.develop_p_exposure, Params.CONTRAST to R.string.develop_p_contrast,
            Params.HIGHLIGHTS to R.string.develop_p_highlights, Params.SHADOWS to R.string.develop_p_shadows,
            Params.WHITES to R.string.develop_p_whites, Params.BLACKS to R.string.develop_p_blacks,
            Params.TEMP to R.string.develop_p_temp, Params.TINT to R.string.develop_p_tint,
            Params.VIBRANCE to R.string.develop_p_vibrance, Params.SATURATION to R.string.develop_p_saturation,
            Params.CLARITY to R.string.develop_p_clarity, Params.TEXTURE to R.string.develop_p_texture,
            Params.DEHAZE to R.string.develop_p_dehaze, Params.SHARPEN to R.string.develop_p_sharpen_amount,
            Params.SHARPEN_RADIUS to R.string.develop_p_sharpen_radius, Params.SHARPEN_DETAIL to R.string.develop_p_sharpen_detail,
            Params.SHARPEN_MASKING to R.string.develop_p_sharpen_masking, Params.NR_LUMA to R.string.develop_p_nr_luma,
            Params.NR_COLOR to R.string.develop_p_nr_color, Params.VIGNETTE to R.string.develop_p_vignette_amount,
            Params.VIGNETTE_MIDPOINT to R.string.develop_p_vignette_midpoint, Params.VIGNETTE_ROUNDNESS to R.string.develop_p_vignette_roundness,
            Params.VIGNETTE_FEATHER to R.string.develop_p_vignette_feather, Params.VIGNETTE_HIGHLIGHTS to R.string.develop_p_vignette_highlights,
            Params.GRAIN to R.string.develop_p_grain_amount, Params.GRAIN_SIZE to R.string.develop_p_grain_size,
            Params.GRAIN_ROUGHNESS to R.string.develop_p_grain_roughness, Params.LOOK_INTENSITY to R.string.develop_p_look_intensity,
            Params.PC_HIGHLIGHTS to R.string.develop_p_pc_highlights, Params.PC_LIGHTS to R.string.develop_p_pc_lights,
            Params.PC_DARKS to R.string.develop_p_pc_darks, Params.PC_SHADOWS to R.string.develop_p_pc_shadows,
            Params.GRADE_BLENDING to R.string.develop_p_grade_blending, Params.GRADE_BALANCE to R.string.develop_p_grade_balance,
            Params.CROP_ANGLE to R.string.develop_p_crop_angle, Params.PERSPECTIVE_V to R.string.develop_p_persp_v,
            Params.PERSPECTIVE_H to R.string.develop_p_persp_h,
        )
        val BAND_LABELS = listOf(R.string.develop_band_red, R.string.develop_band_orange, R.string.develop_band_yellow, R.string.develop_band_green,
            R.string.develop_band_aqua, R.string.develop_band_blue, R.string.develop_band_purple, R.string.develop_band_magenta)
        private val RANGE_LABELS = listOf(R.string.develop_grade_shadows, R.string.develop_grade_midtones, R.string.develop_grade_highlights, R.string.develop_grade_global)
        private val BAND_COLORS = intArrayOf(0xffe04040.toInt(), 0xffe08a30.toInt(), 0xffe0d040.toInt(), 0xff40c040.toInt(),
            0xff40d0d0.toInt(), 0xff4060e0.toInt(), 0xff9040e0.toInt(), 0xffe040b0.toInt())
    }

    private fun label(key: String) = LABELS[key]?.let { a.getString(it) } ?: key

    // ---- building blocks ---------------------------------------------------------------

    private fun header(text: String, group: Group? = null) {
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(TextView(a).apply {
            this.text = text; setTextColor(theme.onSurface); textSize = 16f
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (group != null) {
            row.addView(SwitchCompat(a).apply {
                isChecked = group !in a.stack.disabled
                contentDescription = a.getString(R.string.develop_group_enabled)
                minimumHeight = a.dp(48f).toInt()
                setOnCheckedChangeListener { _, on ->
                    val d = if (on) a.stack.disabled - group else a.stack.disabled + group
                    a.change(a.stack.copy(disabled = d), "Toggle ${group.name.lowercase()}")
                    a.seal()
                }
            })
        }
        panel.addView(row)
    }

    private fun slider(p: Param, text: String = label(p.key), get: () -> Float = { a.stack[p.key] },
                       set: (Float) -> EditStack = { a.stack.with(p.key, it) }): ParamSlider {
        val s = ParamSlider(a, p, text, { v, _ -> a.change(set(v), text, "slider-${p.key}") }, { a.seal() })
        s.set(get())
        a.sliders[p.key] = s
        panel.addView(s, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return s
    }

    private fun button(text: String, onClick: () -> Unit) = Button(a).apply {
        this.text = text
        minimumHeight = a.dp(48f).toInt()
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View): LinearLayout {
        val r = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        for (v in views) r.addView(v, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        panel.addView(HorizontalScrollView(a).apply { addView(r) })
        return r
    }

    private fun chips(labels: List<String>, selected: Int, colors: IntArray? = null, onPick: (Int) -> Unit): LinearLayout {
        val buttons = labels.mapIndexed { i, l ->
            Button(a, null, android.R.attr.borderlessButtonStyle).apply {
                text = l
                minimumHeight = a.dp(48f).toInt()
                setTextColor(colors?.get(i) ?: if (i == selected) theme.accent else theme.onSurface)
                if (i == selected) paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
                setOnClickListener { onPick(i) }
            }
        }
        return row(*buttons.toTypedArray())
    }

    private fun refreshSliders() {
        for ((k, s) in a.sliders) s.set(a.stack[k])
    }

    // ---- panels ------------------------------------------------------------------------

    fun group(g: Group) {
        val title = when (g) { Group.LIGHT -> R.string.develop_tab_light; Group.COLOR -> R.string.develop_tab_color; else -> R.string.develop_tab_effects }
        header(a.getString(title), g)
        for (p in Params.inGroup(g)) slider(p)
        a.refreshPanel = { refreshSliders() }
    }

    fun detail() {
        header(a.getString(R.string.develop_tab_detail), Group.DETAIL)
        for (p in Params.inGroup(Group.DETAIL)) slider(p)
        panel.addView(button(a.getString(R.string.develop_ai_denoise)) {
            val i = a.info ?: return@button
            if (i.uri.authority != android.provider.MediaStore.AUTHORITY || android.os.Build.VERSION.SDK_INT < 29) {
                a.toast(a.getString(R.string.develop_ai_denoise_unavailable)); return@button
            }
            ProcessingService.enqueue(a, i.uri, null, 1f, WatermarkTemplate.NONE.key,
                AiOps(denoise = 0.7f, keepSource = true, budgetMs = 180_000L))
            a.toast(a.getString(R.string.develop_ai_denoise_queued))
        })
        a.refreshPanel = { refreshSliders() }
    }

    fun auto() {
        header(a.getString(R.string.develop_tab_auto))
        fun apply(values: Map<String, Float>, label: String) {
            if (values.isEmpty()) return
            a.change(a.stack.withAll(values), label)
            a.seal()
            a.toast(a.getString(R.string.develop_auto_done, values.size))
        }
        val px = { a.engine.proxyPixels() }
        panel.addView(button(a.getString(R.string.develop_auto_all)) {
            px()?.let { p -> bg({ AutoAdjust.all(p, a.info?.wide == true) }) { apply(it, "Auto") } }
        })
        panel.addView(button(a.getString(R.string.develop_auto_tone)) {
            px()?.let { p -> bg({ AutoAdjust.tone(AutoAdjust.samples(p)) }) { apply(it, "Auto tone") } }
        })
        panel.addView(button(a.getString(R.string.develop_auto_wb)) {
            px()?.let { p -> bg({ AutoAdjust.whiteBalance(AutoAdjust.samples(p), a.info?.wide == true) }) { apply(it, "Auto white balance") } }
        })
        panel.addView(button(a.getString(R.string.develop_auto_straighten)) {
            px()?.let { p ->
                bg({ AutoAdjust.straighten(p, a.engine.proxyWidth, a.engine.proxyHeight) }) { angle ->
                    if (angle == 0f) a.toast(a.getString(R.string.develop_straighten_none)) else {
                        a.change(Geometry.fitCrop(a.stack.with(Params.CROP_ANGLE, angle), a.engine.proxyWidth, a.engine.proxyHeight), "Auto straighten")
                        a.seal()
                    }
                }
            }
        })
        panel.addView(TextView(a).apply { setText(R.string.develop_auto_ai_unavailable); setTextColor(theme.onSurfaceMuted) })
        a.refreshPanel = {}
    }

    private fun <T> bg(work: () -> T, done: (T) -> Unit) {
        a.lifecycleScope.launch {
            val r = withContext(Dispatchers.Default) { runCatching(work).getOrNull() }
            if (r != null) done(r)
        }
    }

    fun presets() {
        header(a.getString(R.string.develop_tab_presets), Group.LOOK)
        var current: Preset? = null
        var base = a.stack
        val strength = slider(Param("preset.strength", Group.LOOK, 0f, 100f, 100f), a.getString(R.string.develop_preset_strength),
            get = { 100f }) { v -> current?.applyTo(base, v / 100f) ?: a.stack }
        slider(Params[Params.LOOK_INTENSITY]!!)
        val list = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        panel.addView(HorizontalScrollView(a).apply { addView(list) })
        fun fill() {
            list.removeAllViews()
            list.addView(presetTile(null, a.getString(R.string.develop_none)) {
                a.change(a.stack.copy(lookId = null), "No look"); a.seal()
            })
            for (p in a.presets.all()) {
                list.addView(presetTile(p, p.name) {
                    base = a.stack.pasteFrom(EditStack(), p.groups)
                    current = p
                    strength.set(100f)
                    a.change(p.applyTo(a.stack), "Preset ${p.name}")
                    a.seal()
                }.apply {
                    setOnLongClickListener { DevelopDialogs(a).presetMenu(p) { fill() }; true }
                })
            }
        }
        fill()
        row(
            button(a.getString(R.string.develop_preset_save)) { DevelopDialogs(a).savePreset { fill() } },
            button(a.getString(R.string.develop_preset_import)) { DevelopDialogs(a).importPreset { fill() } },
            button(a.getString(R.string.develop_copy)) { DevelopDialogs(a).copyEdits() },
            button(a.getString(R.string.develop_paste)) { DevelopDialogs(a).pasteEdits() },
            button(a.getString(R.string.develop_batch)) { DevelopDialogs(a).pickBatch() },
        )
        a.refreshPanel = { refreshSliders() }
    }

    /** A preset thumbnail (rendered lazily on the 256 px proxy) with its name. */
    private fun presetTile(p: Preset?, name: String, onClick: () -> Unit): LinearLayout {
        val size = a.dp(76f).toInt()
        val img = ImageView(a).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(theme.background) }
        val tile = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(a.dp(4f).toInt(), 0, a.dp(4f).toInt(), 0)
            addView(img, LinearLayout.LayoutParams(size, size))
            addView(TextView(a).apply { text = name; setTextColor(theme.onSurface); maxLines = 2; textSize = 11f; gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(size, WRAP_CONTENT))
            contentDescription = name
            isClickable = true
            setOnClickListener { onClick() }
        }
        val stack = if (p == null) a.stack.copy(lookId = null) else p.applyTo(a.stack)
        a.lifecycleScope.launch {
            val look = if (stack.lookId != null) withContext(Dispatchers.IO) { runCatching { a.looks.params(stack.lookId, 1f) }.getOrNull() } else null
            a.engine.post {
                val saved = a.engine.look
                a.engine.look = look
                a.engine.thumbnail(stack) { bmp -> bmp?.let { img.setImageBitmap(it) } }
                a.engine.post { a.engine.look = saved }
            }
        }
        return tile
    }

    fun curve() {
        header(a.getString(R.string.develop_tab_curve), Group.CURVE)
        var channel = "rgb"
        val names = listOf(R.string.develop_curve_rgb, R.string.develop_curve_red, R.string.develop_curve_green, R.string.develop_curve_blue).map { a.getString(it) }
        val colors = intArrayOf(Color.WHITE, 0xffff6060.toInt(), 0xff60ff60.toInt(), 0xff6090ff.toInt())
        val view = CurveView(a) { pts, done ->
            a.change(a.stack.withCurve(channel, pts), "Curve", "curve-$channel")
            if (done) a.seal()
        }
        fun show(i: Int) {
            channel = EditStack.CURVE_CHANNELS[i]
            view.channelName = names[i]
            view.color = colors[i]
            view.points = a.stack.curve(channel)
        }
        chips(names, 0, colors) { show(it) }
        panel.addView(view, LinearLayout.LayoutParams(MATCH_PARENT, a.dp(240f).toInt()))
        row(
            button(a.getString(R.string.develop_curve_points)) { DevelopDialogs(a).curvePoints(channel) { view.points = a.stack.curve(channel) } },
            button(a.getString(R.string.develop_reset)) { a.change(a.stack.withCurve(channel, EditStack.IDENTITY_CURVE), "Reset curve"); a.seal(); view.points = a.stack.curve(channel) },
        )
        for (p in Params.inGroup(Group.CURVE)) slider(p)
        show(0)
        a.refreshPanel = { refreshSliders(); view.points = a.stack.curve(channel) }
    }

    fun hsl() {
        header(a.getString(R.string.develop_tab_hsl), Group.HSL)
        var what = "hue"
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        fun fill(highlight: Int = -1) {
            box.removeAllViews()
            a.sliders.keys.removeAll { it.startsWith("hsl.") }
            Params.HSL_BANDS.forEachIndexed { i, b ->
                val p = Params[Params.hsl(b, what)]!!
                val s = ParamSlider(a, p, a.getString(BAND_LABELS[i]), { v, _ -> a.change(a.stack.with(p.key, v), "HSL", "slider-${p.key}") }, { a.seal() })
                s.set(a.stack[p.key])
                s.slider.trackActiveTintList = android.content.res.ColorStateList.valueOf(BAND_COLORS[i])
                if (i == highlight) s.setBackgroundColor(0x33ffffff)
                a.sliders[p.key] = s
                box.addView(s)
            }
        }
        chips(listOf(R.string.develop_hsl_hue, R.string.develop_hsl_sat, R.string.develop_hsl_lum).map { a.getString(it) }, 0) { i ->
            what = listOf("hue", "sat", "lum")[i]; fill()
        }
        panel.addView(SwitchCompat(a).apply {
            setText(R.string.develop_hsl_pick); setTextColor(theme.onSurface); minimumHeight = a.dp(48f).toInt()
            setOnCheckedChangeListener { _: CompoundButton, on: Boolean -> a.switchMode(if (on) Mode.PICK_HSL else Mode.NONE) }
        })
        a.gesture.onHslPick = { band -> fill(band) }
        panel.addView(box)
        fill()
        a.refreshPanel = { refreshSliders() }
    }

    fun grading() {
        header(a.getString(R.string.develop_tab_grading), Group.GRADING)
        var range = 0
        lateinit var lum: ParamSlider
        val wheel = ColorWheelView(a) { h, s, done ->
            val r = Params.GRADE_RANGES[range]
            a.change(a.stack.with(Params.grade(r, "hue"), h).with(Params.grade(r, "sat"), s), "Grading", "wheel-$r")
            if (done) a.seal()
        }
        val lumBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        fun show(i: Int) {
            range = i
            val r = Params.GRADE_RANGES[i]
            wheel.label = a.getString(RANGE_LABELS[i])
            wheel.set(a.stack[Params.grade(r, "hue")], a.stack[Params.grade(r, "sat")])
            lumBox.removeAllViews()
            val p = Params[Params.grade(r, "lum")]!!
            lum = ParamSlider(a, p, a.getString(R.string.develop_grade_lum), { v, _ -> a.change(a.stack.with(p.key, v), "Grading", "slider-${p.key}") }, { a.seal() })
            lum.set(a.stack[p.key])
            a.sliders[p.key] = lum
            lumBox.addView(lum)
        }
        chips(RANGE_LABELS.map { a.getString(it) }, 0) { show(it) }
        panel.addView(wheel, LinearLayout.LayoutParams(MATCH_PARENT, a.dp(200f).toInt()))
        panel.addView(button(a.getString(R.string.develop_grade_enter)) {
            DevelopDialogs(a).gradeValues(Params.GRADE_RANGES[range]) { show(range) }
        })
        panel.addView(lumBox)
        slider(Params[Params.GRADE_BLENDING]!!)
        slider(Params[Params.GRADE_BALANCE]!!)
        show(0)
        a.refreshPanel = { refreshSliders(); show(range) }
    }

    fun crop() {
        header(a.getString(R.string.develop_tab_crop), Group.GEOMETRY)
        a.switchMode(Mode.CROP)
        val w = a.engine.proxyWidth; val h = a.engine.proxyHeight
        val ratios = listOf(0f, -1f, 1f, 4f / 5f, 3f / 2f, 16f / 9f, 9f / 16f, 65f / 24f)
        val names = listOf(a.getString(R.string.develop_crop_free), a.getString(R.string.develop_crop_original), "1:1", "4:5", "3:2", "16:9", "9:16", "65:24")
        chips(names, ratios.indexOf(a.stack[Params.CROP_ASPECT]).coerceAtLeast(0)) { i ->
            a.change(Geometry.applyAspect(a.stack, w, h, ratios[i]), "Aspect"); a.seal(); a.updateOverlay()
        }
        row(
            button(a.getString(R.string.develop_rotate)) {
                val s = a.stack.with(Params.CROP_ROT90, ((a.stack[Params.CROP_ROT90].toInt() + 1) and 3).toFloat())
                a.change(Geometry.applyAspect(s, w, h, s[Params.CROP_ASPECT]).let { if (s[Params.CROP_ASPECT] == 0f) Geometry.fitCrop(s, w, h) else it }, "Rotate")
                a.seal()
            },
            button(a.getString(R.string.develop_flip_h)) { a.change(a.stack.with(Params.CROP_FLIP_H, 1f - a.stack[Params.CROP_FLIP_H]), "Flip"); a.seal() },
            button(a.getString(R.string.develop_flip_v)) { a.change(a.stack.with(Params.CROP_FLIP_V, 1f - a.stack[Params.CROP_FLIP_V]), "Flip"); a.seal() },
            button(a.getString(R.string.develop_auto_straighten)) {
                a.engine.proxyPixels()?.let { p ->
                    bg({ AutoAdjust.straighten(p, w, h) }) { angle ->
                        if (angle == 0f) a.toast(a.getString(R.string.develop_straighten_none))
                        else { a.change(Geometry.fitCrop(a.stack.with(Params.CROP_ANGLE, angle), w, h), "Auto straighten"); a.seal(); refreshSliders() }
                    }
                }
            },
            button(a.getString(R.string.develop_reset)) { a.change(a.stack.pasteFrom(EditStack(), setOf(Group.GEOMETRY)), "Reset crop"); a.seal(); refreshSliders() },
        )
        for (key in listOf(Params.CROP_ANGLE, Params.PERSPECTIVE_V, Params.PERSPECTIVE_H)) {
            slider(Params[key]!!) { v -> Geometry.fitCrop(a.stack.with(key, v), w, h) }
        }
        a.refreshPanel = { refreshSliders(); a.updateOverlay() }
    }

    fun masks() {
        header(a.getString(R.string.develop_tab_masks))
        val list = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        panel.addView(HorizontalScrollView(a).apply { addView(list) })
        val detail = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(detail)
        lateinit var refresh: () -> Unit
        fun add(shape: MaskShape?, invert: Boolean = false) {
            if (a.stack.masks.size >= 4) { a.toast(a.getString(R.string.develop_mask_limit)); return }
            val id = "m" + System.currentTimeMillis().toString(36)
            val comps = listOfNotNull(shape?.let { MaskComponent(MaskOp.ADD, it, invert) })
            val m = LocalMask(id, a.getString(R.string.develop_mask_n, a.stack.masks.size + 1), comps)
            a.change(a.stack.copy(masks = a.stack.masks + m), "Add mask")
            a.seal()
            a.selectedMask = a.stack.masks.size - 1
            refresh()
        }
        fun addAi(kind: String, label: String, invert: Boolean = false) {
            val i = a.info ?: return
            val reason = a.ai.unavailableReason(kind)
            if (reason != null) {
                val why = a.getString(if (reason == AiMaskProvider.Reason.NO_SKY_MODEL) R.string.develop_mask_no_sky_model else R.string.develop_mask_no_model)
                a.toast(a.getString(R.string.develop_mask_ai_missing, label, why)); return
            }
            a.status.text = a.getString(R.string.develop_mask_working, label)
            val proxyPx = a.engine.proxyPixels() ?: return
            val pw = a.engine.proxyWidth; val ph = a.engine.proxyHeight
            a.lifecycleScope.launch {
                val shape = withContext(Dispatchers.Default) {
                    runCatching {
                        val bmp = android.graphics.Bitmap.createBitmap(proxyPx, pw, ph, android.graphics.Bitmap.Config.ARGB_8888)
                        val t0 = android.os.SystemClock.elapsedRealtime()
                        a.ai.compute(kind, bmp, a.store.assetDir(i.ref)).also {
                            android.util.Log.i("DevelopActivity", "AI mask $kind ${pw}x$ph in ${android.os.SystemClock.elapsedRealtime() - t0} ms")
                        }
                    }.getOrNull()
                }
                if (shape == null) a.toast(a.getString(R.string.develop_mask_ai_missing, label, a.getString(R.string.develop_mask_no_model)))
                else add(shape, invert)
            }
        }
        refresh = {
            list.removeAllViews()
            detail.removeAllViews()
            a.stack.masks.forEachIndexed { i, m ->
                list.addView(Button(a, null, android.R.attr.borderlessButtonStyle).apply {
                    text = m.name
                    setTextColor(if (i == a.selectedMask) theme.accent else theme.onSurface)
                    minimumHeight = a.dp(48f).toInt()
                    setOnClickListener { a.selectedMask = i; refresh() }
                })
            }
            val addLabels = listOf(R.string.develop_mask_brush, R.string.develop_mask_linear, R.string.develop_mask_radial,
                R.string.develop_mask_luminance, R.string.develop_mask_color, R.string.develop_mask_subject,
                R.string.develop_mask_person, R.string.develop_mask_sky, R.string.develop_mask_background)
            list.addView(Button(a).apply {
                setText(R.string.develop_mask_add)
                minimumHeight = a.dp(48f).toInt()
                setOnClickListener {
                    androidx.appcompat.app.AlertDialog.Builder(a).setItems(addLabels.map { a.getString(it) }.toTypedArray()) { _, which ->
                        when (which) {
                            0 -> add(BrushShape(emptyList()))
                            1 -> add(null).also { a.switchMode(Mode.LINEAR) }
                            2 -> add(null).also { a.switchMode(Mode.RADIAL) }
                            3 -> add(LuminanceRangeShape(0.6f, 1f))
                            4 -> add(null).also { a.switchMode(Mode.PICK_COLOR) }
                            5 -> addAi(AiMaskProvider.KIND_SUBJECT, a.getString(addLabels[5]))
                            6 -> addAi(AiMaskProvider.KIND_PERSON, a.getString(addLabels[6]))
                            7 -> addAi(AiMaskProvider.KIND_SKY, a.getString(addLabels[7]))
                            8 -> addAi(AiMaskProvider.KIND_SUBJECT, a.getString(addLabels[8]), invert = true)
                        }
                    }.show()
                }
            })
            a.stack.masks.getOrNull(a.selectedMask)?.let { maskDetail(it, detail) { refresh() } }
            a.updateOverlay()
        }
        a.gesture.onColorPick = { hue, sat -> a.stack.masks.getOrNull(a.selectedMask)?.let { a.gesture.pickColorFor(it, hue, sat); refresh() } }
        if (a.selectedMask >= a.stack.masks.size) a.selectedMask = a.stack.masks.size - 1
        refresh()
        a.refreshPanel = { refresh() }
    }

    private fun maskDetail(m: LocalMask, box: LinearLayout, refresh: () -> Unit) {
        val g = a.gesture
        fun addTo(v: View) = box.addView(v)
        val modes = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        fun modeButton(text: Int, mode: Mode) = Button(a, null, android.R.attr.borderlessButtonStyle).apply {
            setText(text); minimumHeight = a.dp(48f).toInt()
            setTextColor(if (a.mode == mode) theme.accent else theme.onSurface)
            setOnClickListener { a.switchMode(if (a.mode == mode) Mode.NONE else mode); refresh() }
        }
        modes.addView(modeButton(R.string.develop_mask_brush, Mode.BRUSH))
        modes.addView(modeButton(R.string.develop_mask_linear, Mode.LINEAR))
        modes.addView(modeButton(R.string.develop_mask_radial, Mode.RADIAL))
        modes.addView(modeButton(R.string.develop_mask_color, Mode.PICK_COLOR))
        addTo(HorizontalScrollView(a).apply { addView(modes) })
        val switches = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        switches.addView(SwitchCompat(a).apply {
            setText(R.string.develop_mask_enabled); isChecked = m.enabled; setTextColor(theme.onSurface)
            setOnCheckedChangeListener { _, on -> g.replaceMask(m.copy(enabled = on), "Mask on/off"); a.seal() }
        })
        switches.addView(SwitchCompat(a).apply {
            setText(R.string.develop_mask_show); isChecked = g.showMask; setTextColor(theme.onSurface)
            setOnCheckedChangeListener { _, on -> g.showMask = on; a.updateOverlay() }
        })
        switches.addView(SwitchCompat(a).apply {
            setText(R.string.develop_brush_erase); isChecked = g.erase; setTextColor(theme.onSurface)
            setOnCheckedChangeListener { _, on -> g.erase = on }
        })
        addTo(HorizontalScrollView(a).apply { addView(switches) })
        val ops = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        fun setLastOp(op: MaskOp) {
            if (m.components.size < 2) return
            val c = m.components.toMutableList(); c[c.size - 1] = c.last().copy(op = op)
            g.replaceMask(m.copy(components = c), "Mask combine"); a.seal(); refresh()
        }
        ops.addView(button(a.getString(R.string.develop_mask_add_part)) { setLastOp(MaskOp.ADD) })
        ops.addView(button(a.getString(R.string.develop_mask_subtract_part)) { setLastOp(MaskOp.SUBTRACT) })
        ops.addView(button(a.getString(R.string.develop_mask_intersect_part)) { setLastOp(MaskOp.INTERSECT) })
        ops.addView(button(a.getString(R.string.develop_mask_invert)) {
            if (m.components.isEmpty()) return@button
            val c = m.components.toMutableList(); c[c.size - 1] = c.last().copy(invert = !c.last().invert)
            g.replaceMask(m.copy(components = c), "Mask invert"); a.seal()
        })
        ops.addView(button(a.getString(R.string.develop_mask_delete)) {
            a.change(a.stack.copy(masks = a.stack.masks.filter { it.id != m.id }), "Delete mask"); a.seal()
            a.selectedMask = a.stack.masks.size - 1; a.switchMode(Mode.NONE); refresh()
        })
        addTo(HorizontalScrollView(a).apply { addView(ops) })
        fun local(p: Param, text: String, get: () -> Float, set: (Float) -> Unit) {
            val s = ParamSlider(a, p, text, { v, _ -> set(v) }, { a.seal() })
            s.set(get())
            addTo(s)
        }
        if (a.mode == Mode.BRUSH || m.components.any { it.shape is BrushShape }) {
            local(Param("brush.size", Group.LIGHT, 0.5f, 25f, 5f, 0.1f), a.getString(R.string.develop_brush_size), { g.brushSize * 100f }) { g.brushSize = it / 100f }
            local(Param("brush.feather", Group.LIGHT, 0f, 100f, 50f), a.getString(R.string.develop_brush_feather), { g.brushFeather * 100f }) { g.brushFeather = it / 100f }
            local(Param("brush.flow", Group.LIGHT, 1f, 100f, 100f), a.getString(R.string.develop_brush_flow), { g.brushFlow * 100f }) { g.brushFlow = it / 100f }
        }
        m.components.forEachIndexed { ci, c ->
            fun update(shape: MaskShape) {
                val base = curMask(m.id) ?: m
                val list = base.components.toMutableList(); list[ci] = list[ci].copy(shape = shape)
                g.replaceMask(base.copy(components = list), "Range", "range-${m.id}-$ci")
            }
            when (val s = c.shape) {
                is LuminanceRangeShape -> {
                    local(Param("r.lo", Group.LIGHT, 0f, 100f, 60f), a.getString(R.string.develop_range_low), { s.lo * 100 }) { update((curMask(m.id)?.components?.get(ci)?.shape as? LuminanceRangeShape ?: s).copy(lo = it / 100f)) }
                    local(Param("r.hi", Group.LIGHT, 0f, 100f, 100f), a.getString(R.string.develop_range_high), { s.hi * 100 }) { update((curMask(m.id)?.components?.get(ci)?.shape as? LuminanceRangeShape ?: s).copy(hi = it / 100f)) }
                    local(Param("r.sm", Group.LIGHT, 1f, 50f, 10f), a.getString(R.string.develop_range_smooth), { s.smooth * 100 }) { update((curMask(m.id)?.components?.get(ci)?.shape as? LuminanceRangeShape ?: s).copy(smooth = it / 100f)) }
                }
                is ColorRangeShape -> local(Param("r.tol", Group.LIGHT, 2f, 100f, 30f), a.getString(R.string.develop_range_tolerance), { s.tolerance * 100 }) {
                    update((curMask(m.id)?.components?.get(ci)?.shape as? ColorRangeShape ?: s).copy(tolerance = it / 100f))
                }
                else -> Unit
            }
        }
        for (key in Params.LOCAL) {
            val gp = Params[key]!!
            val range = if (key == Params.EXPOSURE) 4f else 100f
            val p = Param("local.$key", gp.group, -range, range, 0f, if (key == Params.EXPOSURE) 0.01f else 1f)
            local(p, label(key), { curMask(m.id)?.values?.get(key) ?: 0f }) { v ->
                val cur = curMask(m.id) ?: return@local
                g.replaceMask(cur.copy(values = cur.values + (key to v)), "Mask ${label(key)}", "local-${m.id}-$key")
            }
        }
    }

    private fun curMask(id: String) = a.stack.masks.firstOrNull { it.id == id }

    fun retouch() {
        header(a.getString(R.string.develop_tab_retouch))
        val tools = ToolRegistry.all()
        if (tools.isEmpty()) {
            panel.addView(TextView(a).apply { setText(R.string.develop_retouch_none); setTextColor(theme.onSurfaceMuted) })
            a.refreshPanel = {}
            return
        }
        val i = a.info ?: return
        val host = object : ToolHost {
            override val proxy get() = null
            override fun commit(step: ToolStep, label: String) {
                val others = a.stack.tools.filter { it.toolId != step.toolId || it.params != step.params }
                a.change(a.stack.copy(tools = others + step), label); a.seal()
            }
            override val assetDir: File get() = a.store.assetDir(i.ref)
        }
        for (t in tools) {
            panel.addView(TextView(a).apply { text = t.label(a); setTextColor(theme.onSurface); textSize = 15f })
            val v = runCatching { t.createPanel(a, host) }.getOrNull()
            if (v != null) panel.addView(v) else panel.addView(button(t.label(a)) { host.commit(ToolStep(t.id, "{}"), t.label(a)) })
        }
        a.refreshPanel = {}
    }
}
