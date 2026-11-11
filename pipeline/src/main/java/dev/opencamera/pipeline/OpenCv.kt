/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline

import android.util.Log
import org.opencv.android.OpenCVLoader

/** Loads OpenCV once. Panorama and Document modes hide themselves when this returns false. */
object OpenCv {
    private const val TAG = "OpenCv"

    val available: Boolean by lazy {
        try {
            OpenCVLoader.initLocal()
        } catch (e: Throwable) {
            Log.w(TAG, "OpenCV unavailable", e)
            false
        }
    }
}
