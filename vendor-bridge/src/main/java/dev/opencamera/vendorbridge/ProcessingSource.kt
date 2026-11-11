/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.vendorbridge

/**
 * Processing the phone maker exposes through CameraX / Camera2 extensions.
 * [camera2Extension] is the matching `CameraExtensionCharacteristics.EXTENSION_*` value.
 */
enum class OemFeature(val key: String, val camera2Extension: Int, val label: String) {
    AUTO("auto", 0, "Auto"),
    FACE_RETOUCH("face_retouch", 1, "Face retouch"),
    BOKEH("bokeh", 2, "Bokeh"),
    HDR("hdr", 3, "HDR"),
    NIGHT("night", 4, "Night");

    companion object {
        fun fromCamera2Extension(value: Int) = entries.firstOrNull { it.camera2Extension == value }
    }
}

/** Who processes a mode that both the app and the phone can do. */
enum class ProcessingSource(val key: String) {
    OPEN("open"),
    PHONE("phone");

    companion object {
        fun fromKey(key: String?) = entries.firstOrNull { it.key == key }
    }
}

object SourcePolicy {
    /**
     * The open pipeline when the user chose it; else the phone's processing when the bridge is
     * on, the phone exposes it and it has not failed this session; else the open pipeline.
     */
    fun resolve(
        choice: ProcessingSource,
        bridgeEnabled: Boolean,
        oemAvailable: Boolean,
        oemFailedThisSession: Boolean,
    ): ProcessingSource = when {
        choice == ProcessingSource.OPEN -> ProcessingSource.OPEN
        bridgeEnabled && oemAvailable && !oemFailedThisSession -> ProcessingSource.PHONE
        else -> ProcessingSource.OPEN
    }

    /** Text for EXIF ImageDescription and the UI. */
    fun label(source: ProcessingSource, feature: String) = when (source) {
        ProcessingSource.PHONE -> "OEM $feature"
        ProcessingSource.OPEN -> "Open Camera $feature"
    }
}
