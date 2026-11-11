/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.profiles

/** Fixed set of device quirks; the capture layer owns the behaviour of each key. */
enum class Quirk {
    /** The same physical ultrawide sensor is exposed under more than one camera id. */
    ULTRAWIDE_DUP_ID,

    /** A non-ultrawide sensor is exposed under more than one camera id. */
    DUPLICATE_CAMERA_ID,

    /**
     * Lenses are missing from getCameraIdList(); they were found by probing ids 0-15.
     * Their stops are shown only when the id (or a duplicate, or a logical camera that
     * contains it) can be opened.
     */
    HIDDEN_PHYSICAL_IDS,

    /**
     * Zoom through the listed logical camera (CONTROL_ZOOM_RATIO) instead of switching ids:
     * every stop becomes a [StopMode.LOGICAL] stop on that camera.
     */
    USE_LOGICAL_ZOOM,

    NO_ZSL,
    JPEG_ORIENTATION_WRONG,
    HDR_VIDEO_BROKEN,
    RAW_BLACK_LEVEL_WRONG;

    companion object {
        /** Null for keys added by a newer schema, so old app builds skip them. */
        fun fromKey(key: String) = entries.firstOrNull { it.name == key }
    }
}