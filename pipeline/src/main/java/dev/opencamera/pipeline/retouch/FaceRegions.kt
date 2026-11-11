/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.retouch

import android.graphics.Bitmap
import android.graphics.Rect
import dev.opencamera.ml.ImageTensors
import dev.opencamera.ml.tasks.FaceMesh
import dev.opencamera.pipeline.ai.BeautyProcessor
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** Face geometry in image pixels of the bitmap it was detected on. */
class FaceGeometry(val landmarks: List<NPoint>, val width: Float) {
    fun region(indices: IntArray) = indices.map { landmarks[it] }

    /** The two irises as (centre, radius); empty when the mesh has no iris points. */
    fun irises(): List<Pair<NPoint, Float>> {
        if (landmarks.size < 478) return emptyList()
        return listOf(468, 473).map { c ->
            val centre = landmarks[c]
            val r = (1..4).map { hypot(landmarks[c + it].x - centre.x, landmarks[c + it].y - centre.y) }.average().toFloat()
            centre to r
        }
    }
}

/**
 * Finds faces with the phase 5 face mesh on a downscaled copy and builds region masks (skin,
 * teeth, irises) in full image pixels, so the same params work on preview and export.
 */
class FaceRegions(private val faceMesh: FaceMesh) {
    val available get() = faceMesh.available

    private var cacheKey: Long = -1
    private var cache: List<FaceGeometry> = emptyList()

    /** Faces of [image]; throws [RetouchUnavailable] when the model is missing or no face is found. */
    @Synchronized
    fun faces(image: Bitmap): List<FaceGeometry> {
        if (!faceMesh.available) throw RetouchUnavailable("face model unavailable")
        val key = contentKey(image)
        if (key == cacheKey) return cache
        val k = DETECT_SIDE.toFloat() / max(image.width, image.height)
        val small = if (k < 1f) ImageTensors.scaled(image, (image.width * k).roundToInt(), (image.height * k).roundToInt()) else image
        val scale = if (small === image) 1f else image.width.toFloat() / small.width
        val found = try {
            faceMesh.detect(small) ?: throw RetouchUnavailable("face model unavailable")
        } finally {
            if (small !== image) small.recycle()
        }
        if (found.isEmpty()) throw RetouchUnavailable("no face found")
        cache = found.map { f -> FaceGeometry(f.landmarks.map { NPoint(it.x * scale, it.y * scale) }, f.box.width() * scale) }
        cacheKey = key
        return cache
    }

    /**
     * Cheap identity of the pixels (size plus a 32 x 32 sample grid): preview copies of the same
     * image share their faces, while any edit that moves features changes the key.
     */
    private fun contentKey(image: Bitmap): Long {
        var h = (image.width.toLong() shl 32) xor image.height.toLong()
        for (j in 0 until 32) for (i in 0 until 32) {
            val x = (image.width - 1) * i / 31
            val y = (image.height - 1) * j / 31
            h = h * 31 + image.getPixel(x, y)
        }
        return h
    }

    fun skin(face: FaceGeometry, image: Bitmap): RegionMask? {
        val oval = hull(face.region(FaceMesh.FACE_OVAL))
        val holes = listOf(FaceMesh.LEFT_EYE, FaceMesh.RIGHT_EYE, FaceMesh.LEFT_BROW, FaceMesh.RIGHT_BROW, FaceMesh.LIPS)
            .map { grow(hull(face.region(it)), 1.25f) }
        val mask = RegionMask.of(listOf(oval), pad = face.width * 0.02f, maxSide = 384, feather = 0.03f, holes = holes) ?: return null
        weightBySkinTone(mask, image)
        return mask
    }

    fun teeth(face: FaceGeometry): RegionMask? =
        RegionMask.of(listOf(face.region(INNER_LIPS)), pad = face.width * 0.01f, maxSide = 192, feather = 0.03f)

    fun irises(face: FaceGeometry): RegionMask? {
        val circles = face.irises().map { (c, r) -> circle(c, r * 1.05f) }
        return if (circles.isEmpty()) null else RegionMask.of(circles, pad = face.width * 0.01f, maxSide = 256, feather = 0.03f)
    }

    /**
     * Multiplies the mesh mask by a YCrCb skin likelihood so beards, glasses and hair inside
     * the face oval are left alone.
     */
    private fun weightBySkinTone(mask: RegionMask, image: Bitmap) {
        val r = mask.pixelRect.apply { intersect(0, 0, image.width, image.height) }
        if (r.isEmpty) return
        val small = ImageTensors.scaled(image, mask.mw, mask.mh, Rect(r))
        val px = ImageTensors.pixels(small)
        if (small !== image) small.recycle()
        for (i in mask.values.indices) {
            if (mask.values[i] <= 0f) continue
            mask.values[i] *= SkinTone.likelihood(px[i])
        }
    }

    companion object {
        private const val DETECT_SIDE = 1024
        val INNER_LIPS = intArrayOf(78, 191, 80, 81, 82, 13, 312, 311, 310, 415, 308, 324, 318, 402, 317, 14, 87, 178, 88, 95)

        fun hull(points: List<NPoint>): List<NPoint> =
            BeautyProcessor.hull(points.map { android.graphics.PointF(it.x, it.y) }).map { NPoint(it.x, it.y) }

        fun grow(poly: List<NPoint>, k: Float): List<NPoint> {
            val cx = poly.map { it.x }.average().toFloat()
            val cy = poly.map { it.y }.average().toFloat()
            return poly.map { NPoint(cx + (it.x - cx) * k, cy + (it.y - cy) * k) }
        }

        fun circle(c: NPoint, r: Float, n: Int = 24) =
            (0 until n).map { i -> NPoint(c.x + r * cos(i * 2 * Math.PI / n).toFloat(), c.y + r * sin(i * 2 * Math.PI / n).toFloat()) }
    }
}

/** Classical skin-colour likelihood in YCrCb (Chai and Ngan box, softened). */
object SkinTone {
    fun likelihood(argb: Int): Float {
        val r = (argb shr 16 and 0xff).toFloat()
        val g = (argb shr 8 and 0xff).toFloat()
        val b = (argb and 0xff).toFloat()
        val cr = 128f + 0.5f * r - 0.418688f * g - 0.081312f * b
        val cb = 128f - 0.168736f * r - 0.331264f * g + 0.5f * b
        fun soft(v: Float, lo: Float, hi: Float, edge: Float) =
            ((v - lo + edge) / edge).coerceIn(0f, 1f) * ((hi - v + edge) / edge).coerceIn(0f, 1f)
        return soft(cr, 133f, 173f, 10f) * soft(cb, 77f, 127f, 10f)
    }
}
