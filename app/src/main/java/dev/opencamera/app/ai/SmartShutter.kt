/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.ai

import android.graphics.PointF
import kotlin.math.hypot

/**
 * Decisions of the smart timer, fed one analysed viewfinder frame at a time:
 * - an open palm held up for [needed] frames starts a short countdown (the palm itself is in
 *   the picture, so the shot waits for the hand to come down);
 * - everyone with open eyes and a smile for [needed] frames takes the photo at once.
 */
class SmartShutter(private val needed: Int = 2) {
    enum class Trigger { NONE, PALM, SMILE }

    /** Openness (0 closed .. 1 open, least-open eye) and smile (0..1) of one face. */
    data class FaceState(val eyes: Float, val smile: Float)

    private var palmRun = 0
    private var smileRun = 0

    /** [palmScore] null or [faces] null mean "not analysed" (model missing or failed). */
    fun onFrame(palmScore: Float?, faces: List<FaceState>?): Trigger {
        palmRun = if (palmScore != null && palmScore >= PALM_SCORE) palmRun + 1 else 0
        smileRun = if (!faces.isNullOrEmpty() && faces.all { it.eyes >= EYES_OPEN && it.smile >= SMILING }) smileRun + 1 else 0
        return when {
            palmRun >= needed -> Trigger.PALM.also { reset() }
            smileRun >= needed -> Trigger.SMILE.also { reset() }
            else -> Trigger.NONE
        }
    }

    fun reset() {
        palmRun = 0
        smileRun = 0
    }

    companion object {
        const val PALM_SCORE = 0.6f
        const val EYES_OPEN = 0.5f
        const val SMILING = 0.5f

        // Eye corners and lids, mouth corners and lips, face sides and height (478-point mesh).
        private val LEFT_EYE = intArrayOf(33, 133, 159, 145)
        private val RIGHT_EYE = intArrayOf(362, 263, 386, 374)

        /** Lid gap over eye width; about 0.12 closed and 0.24 or more open. [xy] holds x, y per landmark. */
        fun eyeAspect(xy: FloatArray, eye: IntArray): Float {
            val width = dist(xy, eye[0], eye[1])
            return if (width <= 0f) 1f else dist(xy, eye[2], eye[3]) / width
        }

        /** Least-open eye as 0..1, same scale as the motion-photo best take. */
        fun eyes(xy: FloatArray): Float {
            val ear = minOf(eyeAspect(xy, LEFT_EYE), eyeAspect(xy, RIGHT_EYE))
            return ((ear - 0.12f) / 0.12f).coerceIn(0f, 1f)
        }

        /**
         * Smile 0..1 from mouth width over face width (about 0.32-0.39 neutral, 0.42 and more
         * smiling on portraits) and the mouth corners rising above the lip centre.
         */
        fun smile(xy: FloatArray): Float {
            val faceW = dist(xy, 234, 454)
            val faceH = dist(xy, 10, 152)
            if (faceW <= 0f || faceH <= 0f) return 0f
            val width = ((dist(xy, 61, 291) / faceW - 0.39f) / 0.06f).coerceIn(0f, 1f)
            val lipMid = (xy[13 * 2 + 1] + xy[14 * 2 + 1]) / 2f
            val corners = (xy[61 * 2 + 1] + xy[291 * 2 + 1]) / 2f
            val lift = (((lipMid - corners) / faceH) / 0.06f).coerceIn(0f, 1f)
            return maxOf(width, 0.5f * width + 0.5f * lift)
        }

        /** Null for a mesh without the full topology. */
        fun faceState(xy: FloatArray): FaceState? =
            if (xy.size < MESH_POINTS * 2) null else FaceState(eyes(xy), smile(xy))

        fun faceState(lm: List<PointF>): FaceState? =
            faceState(FloatArray(lm.size * 2) { if (it % 2 == 0) lm[it / 2].x else lm[it / 2].y })

        private const val MESH_POINTS = 468

        private fun dist(xy: FloatArray, a: Int, b: Int) = hypot(xy[a * 2] - xy[b * 2], xy[a * 2 + 1] - xy[b * 2 + 1])
    }
}
