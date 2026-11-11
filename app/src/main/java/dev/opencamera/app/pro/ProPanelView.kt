/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.pro

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.isVisible
import dev.opencamera.app.R
import kotlin.math.roundToInt

/**
 * Pro controls: a row of value chips, and an editor (options + slider) for the selected one.
 */
class ProPanelView(context: Context) : LinearLayout(context) {
    enum class Control { ISO, SHUTTER, WB, FOCUS, EV, METER }

    interface Listener {
        fun onSettingsChanged(settings: ProSettings)
        fun onAfLockChanged(locked: Boolean)
        fun onCycleFormat()
        fun onReset()
    }

    var listener: Listener? = null

    private val density = resources.displayMetrics.density
    private val accent = 0xFF26A69A.toInt()

    private var caps: ProCapabilities? = null
    private var settings = ProSettings()
    private var afLocked = false
    private var formatLabel: String? = null
    private var selected: Control? = null

    private var isoStops = listOf<Int>()
    private var shutterStops = listOf<Long>()

    private val chipRow = LinearLayout(context)
    private val optionRow = LinearLayout(context)
    private val optionScroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(optionRow)
    }
    private val autoButton = chip(context.getString(R.string.pro_auto)) { onAuto() }
    private val valueText = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER
        minWidth = (72 * density).toInt()
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val seekBar = SeekBar(context)
    private val sliderRow = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(autoButton)
        addView(seekBar, LayoutParams(0, (48 * density).toInt(), 1f))
        addView(valueText)
    }
    private val editor = LinearLayout(context).apply {
        orientation = VERTICAL
        addView(optionScroll)
        addView(sliderRow)
        isVisible = false
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(0x99000000.toInt())
        val pad = (8 * density).toInt()
        setPadding(pad, pad / 2, pad, pad / 2)
        addView(editor)
        addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(chipRow)
        })

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) onSlider(progress)
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
    }

    fun bind(caps: ProCapabilities, settings: ProSettings, afLocked: Boolean, formatLabel: String?) {
        if (this.caps !== caps) {
            this.caps = caps
            isoStops = caps.ranges.iso?.let { ExposureMath.isoStops(it) }.orEmpty()
            shutterStops = caps.ranges.exposureNs?.let { ExposureMath.shutterStops(it) }.orEmpty()
            if (selected?.let { available(it) } == false) selected = null
        }
        this.settings = settings
        this.afLocked = afLocked
        this.formatLabel = formatLabel
        render()
    }

    private fun available(control: Control): Boolean {
        val c = caps ?: return false
        return when (control) {
            Control.ISO, Control.SHUTTER -> c.manualExposure
            Control.FOCUS -> c.manualFocus
            Control.EV -> c.ranges.ev.first < c.ranges.ev.last
            Control.METER -> c.maxAeRegions > 0
            Control.WB -> true
        }
    }

    private fun update(newSettings: ProSettings) {
        settings = caps?.let { newSettings.clampTo(it.ranges) } ?: newSettings
        render()
        listener?.onSettingsChanged(settings)
    }

    private fun render() {
        val c = caps ?: return
        chipRow.removeAllViews()
        Control.entries.filter { available(it) }.forEach { control ->
            val (label, value) = describe(control)
            chipRow.addView(chip("$label $value", selected == control).apply {
                contentDescription = context.getString(
                    R.string.pro_value_description, accessibleLabel(control), value,
                )
                setOnClickListener {
                    selected = if (selected == control) null else control
                    render()
                }
            })
        }
        if (!settings.manualExposure) {
            chipRow.addView(toggle(R.string.pro_ae_lock, R.string.pro_ae_lock_description,
                settings.aeLock) { update(settings.copy(aeLock = !settings.aeLock)) })
        }
        if (settings.focusDiopters == null) {
            chipRow.addView(toggle(R.string.pro_af_lock, R.string.pro_af_lock_description,
                afLocked) { listener?.onAfLockChanged(!afLocked) })
        }
        chipRow.addView(toggle(R.string.pro_peaking, R.string.pro_peaking_description,
            settings.peaking) { update(settings.copy(peaking = !settings.peaking)) })
        chipRow.addView(toggle(R.string.pro_zebra, R.string.pro_zebra_description,
            settings.zebra) { update(settings.copy(zebra = !settings.zebra)) })
        chipRow.addView(toggle(R.string.pro_histogram, R.string.pro_histogram,
            settings.histogram) { update(settings.copy(histogram = !settings.histogram)) })
        formatLabel?.let { label ->
            chipRow.addView(chip(label) { listener?.onCycleFormat() }.apply {
                contentDescription = context.getString(
                    R.string.pro_value_description,
                    context.getString(R.string.pro_format_description), label,
                )
            })
        }
        chipRow.addView(chip(context.getString(R.string.pro_reset)) {
            selected = null
            listener?.onReset()
        }.apply { contentDescription = context.getString(R.string.pro_reset_description) })

        renderEditor(c)
    }

    private fun renderEditor(c: ProCapabilities) {
        val control = selected
        editor.isVisible = control != null
        control ?: return

        optionRow.removeAllViews()
        when (control) {
            Control.WB -> listOf(
                WbMode.INCANDESCENT to R.string.pro_wb_incandescent,
                WbMode.FLUORESCENT to R.string.pro_wb_fluorescent,
                WbMode.DAYLIGHT to R.string.pro_wb_daylight,
                WbMode.CLOUDY to R.string.pro_wb_cloudy,
                WbMode.SHADE to R.string.pro_wb_shade,
            ).forEach { (mode, res) ->
                optionRow.addView(chip(context.getString(res), settings.wbMode == mode) {
                    update(settings.copy(wbMode = mode))
                })
            }

            Control.METER -> listOf(
                MeteringMode.CENTER to R.string.pro_meter_center,
                MeteringMode.SPOT to R.string.pro_meter_spot,
                MeteringMode.MATRIX to R.string.pro_meter_matrix,
            ).forEach { (mode, res) ->
                optionRow.addView(chip(context.getString(res), settings.metering == mode) {
                    update(settings.copy(metering = mode))
                })
            }

            else -> Unit
        }
        optionScroll.isVisible = optionRow.childCount > 0

        val (max, progress) = when (control) {
            Control.ISO -> isoStops.lastIndex to
                    ExposureMath.nearestIndex(isoStops, settings.iso ?: DEFAULT_ISO)
            Control.SHUTTER -> shutterStops.lastIndex to
                    ExposureMath.nearestIndex(shutterStops, settings.exposureNs ?: DEFAULT_EXPOSURE_NS)
            Control.WB -> (ProRanges.KELVIN_MAX - ProRanges.KELVIN_MIN) / KELVIN_STEP to
                    (settings.kelvin - ProRanges.KELVIN_MIN) / KELVIN_STEP
            Control.FOCUS -> FOCUS_STEPS to
                    ((settings.focusDiopters ?: 0f) / c.ranges.minFocusDiopters * FOCUS_STEPS).roundToInt()
            Control.EV -> (c.ranges.ev.last - c.ranges.ev.first) to (settings.evIndex - c.ranges.ev.first)
            Control.METER -> 0 to 0
        }
        sliderRow.isVisible = control != Control.METER
        seekBar.isEnabled = !(control == Control.EV && settings.manualExposure)
        seekBar.max = max
        seekBar.progress = progress
        val (label, value) = describe(control)
        valueText.text = value
        seekBar.contentDescription = accessibleLabel(control)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) seekBar.stateDescription = value
        autoButton.chipSelected = isAuto(control)
        autoButton.contentDescription = context.getString(
            R.string.pro_value_description, label, context.getString(R.string.pro_auto),
        )
    }

    private fun isAuto(control: Control) = when (control) {
        Control.ISO, Control.SHUTTER -> !settings.manualExposure
        Control.WB -> settings.wbMode == WbMode.AUTO
        Control.FOCUS -> settings.focusDiopters == null
        Control.EV -> settings.evIndex == 0
        Control.METER -> settings.metering == MeteringMode.CENTER
    }

    private fun onAuto() = when (selected) {
        Control.ISO, Control.SHUTTER -> update(settings.copy(iso = null, exposureNs = null))
        Control.WB -> update(settings.copy(wbMode = WbMode.AUTO))
        Control.FOCUS -> update(settings.copy(focusDiopters = null))
        Control.EV -> update(settings.copy(evIndex = 0))
        else -> Unit
    }

    private fun onSlider(progress: Int) {
        val c = caps ?: return
        when (selected) {
            Control.ISO -> update(settings.copy(
                iso = isoStops[progress],
                exposureNs = settings.exposureNs ?: DEFAULT_EXPOSURE_NS,
            ))
            Control.SHUTTER -> update(settings.copy(
                iso = settings.iso ?: DEFAULT_ISO,
                exposureNs = shutterStops[progress],
            ))
            Control.WB -> update(settings.copy(
                wbMode = WbMode.KELVIN,
                kelvin = ProRanges.KELVIN_MIN + progress * KELVIN_STEP,
            ))
            Control.FOCUS -> update(settings.copy(
                focusDiopters = progress.toFloat() / FOCUS_STEPS * c.ranges.minFocusDiopters,
            ))
            Control.EV -> update(settings.copy(evIndex = c.ranges.ev.first + progress))
            else -> Unit
        }
    }

    private fun describe(control: Control): Pair<String, String> {
        val c = caps!!
        val auto = context.getString(R.string.pro_auto)
        return when (control) {
            Control.ISO -> context.getString(R.string.pro_iso) to (settings.iso?.toString() ?: auto)
            Control.SHUTTER -> context.getString(R.string.pro_shutter) to
                    (settings.exposureNs?.let { ExposureMath.formatShutter(it) } ?: auto)
            Control.WB -> context.getString(R.string.pro_wb) to when (settings.wbMode) {
                WbMode.AUTO -> auto
                WbMode.KELVIN -> "${settings.kelvin}K"
                WbMode.INCANDESCENT -> context.getString(R.string.pro_wb_incandescent)
                WbMode.FLUORESCENT -> context.getString(R.string.pro_wb_fluorescent)
                WbMode.DAYLIGHT -> context.getString(R.string.pro_wb_daylight)
                WbMode.CLOUDY -> context.getString(R.string.pro_wb_cloudy)
                WbMode.SHADE -> context.getString(R.string.pro_wb_shade)
            }
            Control.FOCUS -> context.getString(R.string.pro_focus) to (settings.focusDiopters?.let {
                ExposureMath.dioptersToMeters(it)?.let { m -> "%.2fm".format(m) }
                    ?: context.getString(R.string.pro_focus_infinity)
            } ?: auto)
            Control.EV -> context.getString(R.string.pro_ev) to
                    ExposureMath.formatEv(settings.evIndex * c.evStep)
            Control.METER -> context.getString(R.string.pro_meter) to context.getString(
                when (settings.metering) {
                    MeteringMode.CENTER -> R.string.pro_meter_center
                    MeteringMode.SPOT -> R.string.pro_meter_spot
                    MeteringMode.MATRIX -> R.string.pro_meter_matrix
                }
            )
        }
    }

    private fun accessibleLabel(control: Control) = context.getString(
        when (control) {
            Control.ISO -> R.string.pro_iso
            Control.SHUTTER -> R.string.pro_shutter
            Control.WB -> R.string.pro_wb_description
            Control.FOCUS -> R.string.pro_focus
            Control.EV -> R.string.pro_ev_description
            Control.METER -> R.string.pro_meter_description
        }
    )

    private fun toggle(labelRes: Int, descriptionRes: Int, on: Boolean, onClick: () -> Unit) =
        chip(context.getString(labelRes), on, onClick).apply {
            contentDescription = context.getString(
                R.string.pro_value_description,
                context.getString(descriptionRes),
                context.getString(if (on) R.string.pro_state_on else R.string.pro_state_off),
            )
        }

    private fun chip(text: String, active: Boolean = false, onClick: (() -> Unit)? = null) =
        TextView(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            minHeight = (48 * density).toInt()
            val h = (12 * density).toInt()
            setPadding(h, 0, h, 0)
            isSelected = active
            background = GradientDrawable().apply {
                cornerRadius = 18 * density
                setColor(if (active) accent else 0x33FFFFFF)
            }
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, (40 * density).toInt()).apply {
                val m = (4 * density).toInt()
                setMargins(m, m, m, m)
            }
            onClick?.let { setOnClickListener { it() } }
            isFocusable = true
        }

    private var TextView.chipSelected: Boolean
        get() = isSelected
        set(value) {
            isSelected = value
            (background as? GradientDrawable)?.setColor(if (value) accent else 0x33FFFFFF)
        }

    private companion object {
        const val DEFAULT_ISO = 400
        const val DEFAULT_EXPOSURE_NS = 16_666_667L
        const val KELVIN_STEP = 100
        const val FOCUS_STEPS = 100
    }
}
