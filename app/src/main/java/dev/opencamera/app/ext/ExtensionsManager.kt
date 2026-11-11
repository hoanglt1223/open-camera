/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.ext

import androidx.camera.core.CameraSelector
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import dev.opencamera.capture.MultiFrameSetting
import dev.opencamera.vendorbridge.OemFeature

val extensionModes = IntProgression.fromClosedRange(ExtensionMode.NONE, ExtensionMode.AUTO, 1)

internal fun ExtensionsManager.getSupportedModes(
    cameraSelector: CameraSelector,
) = extensionModes.filter { isExtensionAvailable(cameraSelector, it) }.toSet()

fun OemFeature.toExtensionMode() = when (this) {
    OemFeature.AUTO -> ExtensionMode.AUTO
    OemFeature.FACE_RETOUCH -> ExtensionMode.FACE_RETOUCH
    OemFeature.BOKEH -> ExtensionMode.BOKEH
    OemFeature.HDR -> ExtensionMode.HDR
    OemFeature.NIGHT -> ExtensionMode.NIGHT
}

fun oemFeatureOf(extensionMode: Int) =
    OemFeature.entries.firstOrNull { it.toExtensionMode() == extensionMode }

/** The phone's processing that can stand in for a multi-frame setting. */
fun MultiFrameSetting.oemFeature() = when (this) {
    MultiFrameSetting.HDR_AUTO -> OemFeature.AUTO
    MultiFrameSetting.HDR_ON -> OemFeature.HDR
    MultiFrameSetting.NIGHT -> OemFeature.NIGHT
    MultiFrameSetting.OFF, MultiFrameSetting.ASTRO -> null
}
