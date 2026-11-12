/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app

import android.os.Build
import android.view.View
import androidx.tracing.Trace

/**
 * Async trace sections from a tap to the moment its result is on screen, read by the
 * macrobenchmark module. Each name is open at most once; a disabled tracer makes these no-ops.
 */
object PerfTrace {
    const val MODE_TO_PREVIEW = "OcModeTapToPreview"
    const val LENS_TO_PREVIEW = "OcLensTapToPreview"
    /**
     * Photo mode shutter tap until the shutter takes the next shot: the sensor part is done
     * (CameraX capture started for a single frame, every frame in for a burst). Encoding, saving
     * and merging run after it in the background and are not part of this section.
     */
    const val SHUTTER_TO_READY = "OcShutterToReady"
    const val OPEN_SETTINGS = "OcOpenSettings"
    const val OPEN_LOOKS = "OcOpenLooks"
    const val OPEN_PRO = "OcOpenPro"
    const val OPEN_GALLERY = "OcOpenGallery"
    const val BIND = "OcBind"

    private val open = HashSet<String>()

    fun begin(name: String) {
        if (!Trace.isEnabled()) return
        synchronized(open) {
            // A section the app never closed (no rebind after a tap) is restarted, not nested.
            if (!open.add(name)) Trace.endAsyncSection(name, 0)
        }
        Trace.beginAsyncSection(name, 0)
    }

    fun end(name: String) {
        if (synchronized(open) { open.remove(name) }) Trace.endAsyncSection(name, 0)
    }

    /** Ends [name] once the next frame of [view]'s window has been committed. */
    fun endAfterNextFrame(view: View, name: String) {
        if (synchronized(open) { name !in open }) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            view.viewTreeObserver.registerFrameCommitCallback { end(name) }
            view.invalidate()
        } else {
            view.post { end(name) }
        }
    }
}
