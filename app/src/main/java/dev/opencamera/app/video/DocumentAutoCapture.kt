/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.video

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Decides when to take a document page by itself: the detected outline must cover enough of the
 * frame and hold still for [stableDetections] detections in a row. After a shot it waits until
 * the page is taken away or moved a lot (the next page) before it can fire again.
 */
class DocumentAutoCapture(
    private val stableDetections: Int = 3,
    private val stillTolerance: Float = 0.02f,
    private val newPageMove: Float = 0.15f,
    private val minArea: Float = 0.15f,
) {
    private var last: FloatArray? = null
    private var stable = 0
    private var armed = true
    private var shot: FloatArray? = null

    /**
     * Feeds one detection ([quad] as 4 ordered corners in a [width] x [height] frame, or null).
     * Returns true when a page should be captured now.
     */
    fun onDetection(quad: FloatArray?, width: Int, height: Int): Boolean {
        if (quad == null || quad.size < 8 || width <= 0 || height <= 0) {
            last = null
            stable = 0
            armed = true
            return false
        }
        val diag = hypot(width.toFloat(), height.toFloat())
        val prev = last
        last = quad.copyOf()
        val s = shot
        if (!armed && s != null && maxMove(s, quad) / diag > newPageMove) armed = true
        if (!armed) return false
        if (area(quad) / (width.toFloat() * height) < minArea) {
            stable = 0
            return false
        }
        stable = if (prev != null && maxMove(prev, quad) / diag <= stillTolerance) stable + 1 else 0
        if (stable + 1 < stableDetections) return false
        armed = false
        stable = 0
        shot = quad.copyOf()
        return true
    }

    fun reset() {
        last = null
        stable = 0
        armed = true
        shot = null
    }

    private fun maxMove(a: FloatArray, b: FloatArray): Float {
        var m = 0f
        for (i in 0 until 4) m = max(m, hypot(a[i * 2] - b[i * 2], a[i * 2 + 1] - b[i * 2 + 1]))
        return m
    }

    /** Shoelace area of the quad. */
    private fun area(q: FloatArray): Float {
        var s = 0f
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            s += q[i * 2] * q[j * 2 + 1] - q[j * 2] * q[i * 2 + 1]
        }
        return abs(s) / 2f
    }
}
