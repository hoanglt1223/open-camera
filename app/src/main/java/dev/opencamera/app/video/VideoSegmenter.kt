/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.video

import android.graphics.Bitmap

/**
 * Person mask for bokeh video. The segmentation model belongs to the AI phase; until a
 * provider is registered, [VideoSegmenters.current] is null and the bokeh mode stays hidden.
 */
interface VideoSegmenter {
    /** Foreground probability per pixel (0..255), [frame] sized, or null when it fails. */
    fun mask(frame: Bitmap): ByteArray?
}

object VideoSegmenters {
    @Volatile
    var current: VideoSegmenter? = null
}
