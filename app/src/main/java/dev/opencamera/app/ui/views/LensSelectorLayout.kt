/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.ui.views

import android.content.Context
import android.icu.text.DecimalFormat
import android.icu.text.DecimalFormatSymbols
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.Button
import androidx.appcompat.widget.LinearLayoutCompat
import androidx.core.view.children
import androidx.core.view.setMargins
import dev.opencamera.app.R
import dev.opencamera.app.ext.px
import dev.opencamera.app.ext.smoothRotate
import dev.opencamera.app.models.Camera
import dev.opencamera.app.models.CameraState
import dev.opencamera.app.models.Rotation
import dev.opencamera.profiles.LensChip
import dev.opencamera.profiles.LensPlan
import java.util.Locale

class LensSelectorLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : LinearLayoutCompat(context, attrs, defStyleAttr) {
    private val layoutInflater by lazy { context.getSystemService(LayoutInflater::class.java) }

    private lateinit var activeCamera: Camera

    private var usesLogicalZoomRatio = false
    private var currentZoomRatio = 1.0f

    private val buttonToApproximateZoomRatio = mutableMapOf<Button, Float>()

    private val buttonToCamera = mutableMapOf<Button, Camera>()
    private val buttonToZoomRatio = mutableMapOf<Button, Float>()
    private val buttonToChip = mutableMapOf<Button, Pair<LensChip, Camera>>()

    private var screenRotation = Rotation.ROTATION_0

    var onCameraChangeCallback: (camera: Camera) -> Unit = {}
    var onZoomRatioChangeCallback: (zoomRatio: Float) -> Unit = {}
    var onResetZoomRatioCallback: () -> Unit = {}
    var onLensChipCallback: (chip: LensChip, camera: Camera) -> Unit = { _, _ -> }

    /** Called after the buttons or their selection changed; the skinned overlay mirrors them. */
    var onButtonsChanged: (() -> Unit)? = null

    /** Label (without the selection mark) and selection of each button, in order. */
    fun snapshot(): List<Pair<String, Boolean>> = children.map {
        val b = it as Button
        b.text.toString().removeSuffix("\u00d7") to b.isSelected
    }.toList()

    fun clickAt(index: Int) {
        getChildAt(index)?.performClick()
    }

    /** Device profile zoom stops; replaces the per-camera buttons of [setCamera]. */
    fun setChips(activeCamera: Camera, chips: List<Pair<LensChip, Camera>>) {
        this.activeCamera = activeCamera
        clearButtons()

        for (entry in chips) {
            val button = inflateButton().apply {
                setOnClickListener { view ->
                    buttonToChip[view]?.let { (chip, camera) -> onLensChipCallback(chip, camera) }
                }
            }
            addView(button)
            buttonToChip[button] = entry
            buttonToApproximateZoomRatio[button] = entry.first.ratio
        }

        updateButtonsAttributes()
    }

    private fun clearButtons() {
        removeAllViews()
        buttonToApproximateZoomRatio.clear()

        buttonToCamera.clear()
        buttonToZoomRatio.clear()
        buttonToChip.clear()
    }

    fun setCamera(activeCamera: Camera, availableCameras: Collection<Camera>) {
        this.activeCamera = activeCamera

        clearButtons()

        usesLogicalZoomRatio = activeCamera.logicalZoomRatios.size > 1

        if (usesLogicalZoomRatio) {
            for ((approximateZoomRatio, exactZoomRatio) in activeCamera.logicalZoomRatios) {
                val button = inflateButton().apply {
                    setOnClickListener {
                        if (!isSelected) {
                            buttonToZoomRatio[it]?.let(onZoomRatioChangeCallback)
                        } else {
                            onResetZoomRatioCallback()
                        }
                    }
                    text = formatZoomRatio(approximateZoomRatio)
                }

                addView(button)
                buttonToZoomRatio[button] = exactZoomRatio
                buttonToApproximateZoomRatio[button] = approximateZoomRatio
            }
        } else {
            for (camera in availableCameras.sortedBy { it.intrinsicZoomRatio }) {
                val button = inflateButton().apply {
                    setOnClickListener {
                        if (!isSelected) {
                            buttonToCamera[it]?.let(onCameraChangeCallback)
                        } else {
                            onResetZoomRatioCallback()
                        }
                    }
                    text = formatZoomRatio(camera.intrinsicZoomRatio)
                }

                addView(button)
                buttonToCamera[button] = camera
                buttonToApproximateZoomRatio[button] = camera.intrinsicZoomRatio
            }
        }

        updateButtonsAttributes()
    }

    fun onZoomRatioChanged(zoomRatio: Float) {
        currentZoomRatio = zoomRatio
        updateButtonsAttributes()
    }

    fun setCameraState(cameraState: CameraState) {
        children.forEach { view ->
            view.isSoundEffectsEnabled = cameraState == CameraState.IDLE
        }
    }

    fun setScreenRotation(screenRotation: Rotation) {
        this.screenRotation = screenRotation

        val rotation = screenRotation.compensationValue.toFloat()

        for (button in buttonToApproximateZoomRatio.keys) {
            button.smoothRotate(rotation)
        }
    }

    private fun inflateButton(): Button {
        val button = layoutInflater.inflate(R.layout.lens_selector_button, this, false) as Button
        return button.apply {
            layoutParams = LayoutParams(32.px, 32.px).apply {
                setMargins(5)
            }
        }
    }

    private fun updateButtonsAttributes() {
        updateButtonsAttributesInternal()
        onButtonsChanged?.invoke()
    }

    private fun updateButtonsAttributesInternal() {
        if (buttonToChip.isNotEmpty()) {
            val active = LensPlan.activeChip(
                buttonToChip.values.map { it.first }, activeCamera.cameraId, currentZoomRatio
            )
            for ((button, entry) in buttonToChip) {
                val selected = entry.first == active
                button.isSelected = selected
                val label = entry.first.label.removeSuffix("x")
                button.text = if (selected) "$label\u00d7" else label
                button.rotation = screenRotation.compensationValue.toFloat()
            }
        } else if (usesLogicalZoomRatio) {
            buttonToZoomRatio.asSequence().let {
                val firstButton = it.first().key
                val lastButton = it.last().key

                var activeButton: Button? = null
                for ((button, exactZoomRatio) in it) {
                    if (currentZoomRatio >= exactZoomRatio) {
                        activeButton = button
                    } else if (activeButton != null) {
                        break
                    } else if (button == firstButton || button == lastButton) {
                        activeButton = button
                        break
                    }
                }

                for ((button, _) in it) {
                    updateButtonAttributes(button, button == activeButton)
                }
            }
        } else {
            for ((button, camera) in buttonToCamera) {
                updateButtonAttributes(button, camera == activeCamera)
            }
        }
    }

    @Suppress("SetTextI18n")
    private fun updateButtonAttributes(button: Button, currentCamera: Boolean) {
        button.isSelected = currentCamera
        val formattedZoomRatio = formatZoomRatio(buttonToApproximateZoomRatio[button]!!)
        button.text = if (currentCamera) {
            "${formattedZoomRatio}×"
        } else {
            formattedZoomRatio
        }
        button.rotation = screenRotation.compensationValue.toFloat()
    }

    private fun formatZoomRatio(zoomRatio: Float): String =
        if (zoomRatio < 1f) {
            ZOOM_RATIO_FORMATTER_SUB_ONE.format(zoomRatio)
        } else {
            ZOOM_RATIO_FORMATTER.format(zoomRatio)
        }

    companion object {
        private val DECIMAL_FORMAT_SYMBOLS = DecimalFormatSymbols(Locale.US)

        private val ZOOM_RATIO_FORMATTER = DecimalFormat("0.#", DECIMAL_FORMAT_SYMBOLS)
        private val ZOOM_RATIO_FORMATTER_SUB_ONE = DecimalFormat(".#", DECIMAL_FORMAT_SYMBOLS)
    }
}
