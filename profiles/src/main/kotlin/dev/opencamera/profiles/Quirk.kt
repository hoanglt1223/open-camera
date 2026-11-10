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

    NO_ZSL,
    JPEG_ORIENTATION_WRONG,
    HDR_VIDEO_BROKEN,
    RAW_BLACK_LEVEL_WRONG;

    companion object {
        fun fromKey(key: String) = entries.first { it.name == key }
    }
}