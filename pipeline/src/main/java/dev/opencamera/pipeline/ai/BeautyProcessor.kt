/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import dev.opencamera.ml.ImageTensors
import dev.opencamera.ml.tasks.Classical
import dev.opencamera.ml.tasks.FaceMesh
import dev.opencamera.ml.tasks.Mask
import dev.opencamera.ml.tasks.Segmenter
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Light skin smoothing. The skin mask is the face-mesh oval minus eyes, brows and lips,
 * intersected with the multiclass skin layer when that model is downloaded. Faces are found on
 * an upright copy; nothing is changed when no face is found. Off by default in the app.
 */
class BeautyProcessor(private val faceMesh: FaceMesh, private val segmenter: Segmenter) {
    sealed class Outcome {
        data class Applied(val faces: Int) : Outcome()
        data object NoFace : Outcome()
        data class Unavailable(val reason: String) : Outcome()
    }

    fun process(full: Bitmap, rotation: Int, strength: Float): Outcome {
        if (strength <= 0f) return Outcome.Applied(0)
        if (!NativeAi.available) return Outcome.Unavailable("native")
        if (!faceMesh.available) return Outcome.Unavailable("face mesh model")
        val mask = skinMask(full, rotation) ?: return Outcome.NoFace
        val code = NativeAi.smoothSkin(full, mask.first.data, mask.first.width, mask.first.height, strength)
        return if (code == 0) Outcome.Applied(mask.second) else Outcome.Unavailable("native $code")
    }

    /** Skin mask over the unrotated [full] image plus the face count; null without faces. */
    fun skinMask(full: Bitmap, rotation: Int): Pair<Mask, Int>? {
        val k = WORK.toFloat() / max(full.width, full.height)
        val small = ImageTensors.scaled(full, (full.width * k).roundToInt(), (full.height * k).roundToInt())
        val upright = Masks.rotated(small, rotation)
        try {
            val faces = faceMesh.detect(upright) ?: return null
            if (faces.isEmpty()) return null
            val mw = upright.width
            val mh = upright.height
            val canvasBitmap = Bitmap.createBitmap(mw, mh, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(canvasBitmap)
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
            val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                style = Paint.Style.FILL_AND_STROKE
            }
            for (f in faces) {
                canvas.drawPath(polygon(hull(f.region(FaceMesh.FACE_OVAL))), fill)
                clear.strokeWidth = f.box.width() * 0.04f
                for (region in listOf(FaceMesh.LEFT_EYE, FaceMesh.RIGHT_EYE, FaceMesh.LEFT_BROW, FaceMesh.RIGHT_BROW, FaceMesh.LIPS)) {
                    canvas.drawPath(polygon(hull(f.region(region))), clear)
                }
            }
            val px = ImageTensors.pixels(canvasBitmap)
            canvasBitmap.recycle()
            var data = FloatArray(mw * mh) { (px[it] ushr 24) / 255f }
            segmenter.segment(upright, preferMulticlass = true)?.takeIf { it.faceSkin != null }?.let { seg ->
                val skin = BokehProcessor.resample(seg.faceSkin!!, mw, mh)
                val body = BokehProcessor.resample(seg.bodySkin!!, mw, mh)
                for (i in data.indices) data[i] *= (skin.data[i] + body.data[i]).coerceIn(0f, 1f)
            }
            data = Classical.boxBlur(data, mw, mh, max(1, mw / 200))
            return Masks.unrotate(Mask(mw, mh, data), rotation, small.width, small.height) to faces.size
        } finally {
            if (upright !== small) upright.recycle()
            if (small !== full) small.recycle()
        }
    }

    private fun polygon(points: List<PointF>) = Path().apply {
        points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
        close()
    }

    companion object {
        private const val WORK = 768

        /** Convex hull (monotone chain), counter-clockwise. */
        fun hull(points: List<PointF>): List<PointF> {
            val p = points.sortedWith(compareBy({ it.x }, { it.y }))
            if (p.size < 3) return p
            fun cross(o: PointF, a: PointF, b: PointF) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
            val lower = ArrayList<PointF>()
            for (pt in p) {
                while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), pt) <= 0) lower.removeAt(lower.size - 1)
                lower += pt
            }
            val upper = ArrayList<PointF>()
            for (pt in p.asReversed()) {
                while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), pt) <= 0) upper.removeAt(upper.size - 1)
                upper += pt
            }
            return lower.dropLast(1) + upper.dropLast(1)
        }
    }
}
