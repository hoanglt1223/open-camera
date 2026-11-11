/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.retouch

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/** Base for tools that edit face regions found by [FaceRegions]. */
abstract class FaceRegionTool(protected val regions: FaceRegions) : RetouchTool {
    override val input = InputKind.NONE

    override fun unavailableReason(): String? = when {
        !NativeRetouch.available -> "native library missing"
        !regions.available -> "face model unavailable"
        else -> null
    }

    protected fun requireReady() {
        unavailableReason()?.let { throw RetouchUnavailable(it) }
    }

    /** Runs [edit] on the mask of each face; the undo patch covers all masks. */
    protected fun perFace(image: Bitmap, mask: (FaceGeometry) -> RegionMask?, edit: (RegionMask) -> Int): RetouchResult {
        requireReady()
        val masks = regions.faces(image).mapNotNull(mask)
        if (masks.isEmpty()) throw RetouchUnavailable("region not found")
        val union = Rect(masks[0].pixelRect).apply { masks.forEach { union(it.pixelRect) } }
        val undo = UndoPatch.local(image, union)
        for (m in masks) {
            val code = edit(m)
            if (code < 0) {
                undo.restore(image)
                throw RetouchUnavailable("native $code")
            }
        }
        return RetouchResult(image, undo, "default", note = "${masks.size} face(s)")
    }
}

/** Skin smoothing: guided-filter frequency separation inside the face-mesh skin mask. */
class SkinSmoothTool(regions: FaceRegions) : FaceRegionTool(regions) {
    override val id = "skin"
    override val defaultStrength = 0.35f

    override fun apply(image: Bitmap, params: RetouchParams): RetouchResult {
        val s = params.strength.coerceIn(0f, maxStrength)
        return perFace(image, { f -> regions.skin(f, image) }) { m ->
            // Guided filter radius follows the face size so the result is resolution independent.
            NativeRetouch.smoothSkin(image, m.values, m.mw, m.mh, m.rectArray, s, max(2, (m.rect.width() / 30f).roundToInt()))
        }
    }
}

/** Teeth whitening: lower b*, lift L* inside the inner-lip polygon, bright pixels only. */
class TeethWhitenTool(regions: FaceRegions) : FaceRegionTool(regions) {
    override val id = "teeth"
    override val defaultStrength = 0.5f

    override fun apply(image: Bitmap, params: RetouchParams): RetouchResult {
        val s = params.strength.coerceIn(0f, maxStrength)
        return perFace(image, regions::teeth) { m -> NativeRetouch.whitenTeeth(image, m.values, m.mw, m.mh, m.rectArray, s) }
    }
}

/** Eye brightening: L* lift and local contrast on the irises; hue unchanged. */
class EyeBrightenTool(regions: FaceRegions) : FaceRegionTool(regions) {
    override val id = "eyes"
    override val defaultStrength = 0.4f

    override fun apply(image: Bitmap, params: RetouchParams): RetouchResult {
        val s = params.strength.coerceIn(0f, maxStrength)
        return perFace(image, regions::irises) { m -> NativeRetouch.brightenEyes(image, m.values, m.mw, m.mh, m.rectArray, s) }
    }
}

/**
 * Red-eye removal. Without taps it corrects both pupils of every face from the iris landmarks;
 * a tap corrects the pupil under it (nearest iris radius when a face is found, else a default).
 */
class RedEyeTool(private val regions: FaceRegions) : RetouchTool {
    override val id = "redeye"
    override val input = InputKind.TAP
    override val defaultStrength = 1f

    override fun unavailableReason(): String? = if (!NativeRetouch.available) "native library missing" else null

    override fun apply(image: Bitmap, params: RetouchParams): RetouchResult {
        unavailableReason()?.let { throw RetouchUnavailable(it) }
        val irises = runCatching { regions.faces(image).flatMap { it.irises() } }.getOrDefault(emptyList())
        val long = max(image.width, image.height)
        val circles = if (params.taps.isEmpty()) {
            if (irises.isEmpty()) throw RetouchUnavailable("no eyes found; tap the eye")
            irises.map { (c, r) -> Triple(c.x, c.y, r * 1.15f) }
        } else {
            params.taps.map { t ->
                val x = t.x * image.width
                val y = t.y * image.height
                val near = irises.minByOrNull { hypot(it.first.x - x, it.first.y - y) }
                val r = near?.takeIf { hypot(it.first.x - x, it.first.y - y) < it.second * 3 }?.second?.times(1.15f)
                    ?: (long * DEFAULT_RADIUS)
                Triple(x, y, r)
            }
        }
        val rect = Rect()
        circles.forEach { (x, y, r) -> rect.union(Rect((x - r).toInt() - 1, (y - r).toInt() - 1, (x + r).toInt() + 2, (y + r).toInt() + 2)) }
        val undo = UndoPatch.local(image, rect)
        var fixed = 0
        for ((x, y, r) in circles) fixed += NativeRetouch.redEye(image, x, y, r).coerceAtLeast(0)
        return RetouchResult(image, undo, "default", note = if (fixed == 0) "no red pixels" else "$fixed px")
    }

    private companion object {
        const val DEFAULT_RADIUS = 0.01f
    }
}
