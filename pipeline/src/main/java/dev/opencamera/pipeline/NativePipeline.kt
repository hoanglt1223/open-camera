/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline

object NativePipeline {
    init {
        System.loadLibrary("opencamera_pipeline")
    }

    external fun nativeVersion(): String
}