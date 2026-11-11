/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.retouch

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import dev.opencamera.ml.ImageTensors
import dev.opencamera.ml.MlException
import dev.opencamera.ml.MlRuntime
import dev.opencamera.ml.Tensor
import dev.opencamera.pipeline.OpenCv
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class InpaintMethod(val id: String) {
    MIGAN("migan"), LAMA("lama"), PATCHMATCH("patchmatch"), TELEA("telea");

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id }
    }
}

/** Which inpainting backends to try, in order. Pure; unit-tested. */
object InpaintPlan {
    /** Spots below this fraction of the image go to Telea directly. */
    const val SMALL_FRACTION = 0.01f

    fun chain(requested: InpaintMethod?, areaFraction: Float, available: Set<InpaintMethod>): List<InpaintMethod> {
        val wanted = when (requested) {
            null -> if (areaFraction < SMALL_FRACTION) listOf(InpaintMethod.TELEA) else listOf(InpaintMethod.MIGAN, InpaintMethod.PATCHMATCH, InpaintMethod.TELEA)
            InpaintMethod.LAMA -> listOf(InpaintMethod.LAMA, InpaintMethod.MIGAN, InpaintMethod.PATCHMATCH, InpaintMethod.TELEA)
            InpaintMethod.MIGAN -> listOf(InpaintMethod.MIGAN, InpaintMethod.PATCHMATCH, InpaintMethod.TELEA)
            InpaintMethod.PATCHMATCH -> listOf(InpaintMethod.PATCHMATCH, InpaintMethod.TELEA)
            InpaintMethod.TELEA -> listOf(InpaintMethod.TELEA)
        }
        return wanted.filter { it in available }
    }

    /**
     * Square crop around [bounds] with as much context again on each side (at least [minContext]
     * px), clamped to the image. Square keeps the 512 x 512 models undistorted.
     */
    fun cropFor(bounds: Rect, w: Int, h: Int, minContext: Int = 32): Rect {
        val side0 = max(bounds.width(), bounds.height())
        val side = min(min(w, h), max(side0 * 2, side0 + 2 * minContext))
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        val left = (cx - side / 2).coerceIn(0, w - side)
        val top = (cy - side / 2).coerceIn(0, h - side)
        return Rect(left, top, left + side, top + side)
    }
}

/**
 * Object removal on a crop around the mask: MI-GAN (bundled), big-lama (optional download),
 * PatchMatch (native, no model) and Telea (OpenCV). Models see the crop scaled to 512 x 512;
 * the result is scaled back and only the (slightly grown, feathered) mask area is replaced,
 * so detail outside the selection stays untouched at full resolution.
 */
class Inpainter(private val runtime: MlRuntime) {
    class Output(val method: InpaintMethod, val ms: Long, val fallbackReason: String?)

    fun available(): Set<InpaintMethod> = buildSet {
        if (runtime.isAvailable(MIGAN)) add(InpaintMethod.MIGAN)
        if (runtime.isAvailable(LAMA)) add(InpaintMethod.LAMA)
        if (NativeRetouch.available) add(InpaintMethod.PATCHMATCH)
        if (OpenCv.available) add(InpaintMethod.TELEA)
    }

    fun lamaDownloaded() = runtime.isAvailable(LAMA)

    /** Fault injection for device tests of the fallback chain (debug entry point only). */
    @Volatile
    var faultInjector: ((InpaintMethod) -> Unit)? = null

    /**
     * Fills [mask] in [image] in place, trying the chain for [requested]. Throws
     * [RetouchUnavailable] when every backend failed (the image is unchanged then).
     */
    fun inpaint(image: Bitmap, mask: HoleMask, requested: InpaintMethod?, strict: Boolean = false, patchBudgetMs: Double = 6000.0): Output {
        if (mask.isEmpty) throw RetouchUnavailable("empty selection")
        val fraction = mask.area.toFloat() / (mask.width.toFloat() * mask.height)
        var chain = InpaintPlan.chain(requested, fraction, available())
        if (strict && requested != null) chain = chain.take(1).filter { it == requested }
        if (chain.isEmpty()) throw RetouchUnavailable("${requested?.id ?: "inpainting"} unavailable")
        var reason: String? = null
        for (m in chain) {
            val t0 = SystemClock.elapsedRealtime()
            try {
                faultInjector?.invoke(m)
                run(image, mask, m, patchBudgetMs)
                return Output(m, SystemClock.elapsedRealtime() - t0, reason)
            } catch (e: OutOfMemoryError) {
                reason = "${m.id}: out of memory"
            } catch (e: MlException) {
                reason = "${m.id}: ${e.reason}"
            } catch (e: RetouchUnavailable) {
                reason = "${m.id}: ${e.message}"
            } catch (e: Exception) {
                Log.w(TAG, "Inpaint ${m.id} failed", e)
                reason = "${m.id}: ${e.javaClass.simpleName}"
            }
            Log.w(TAG, "Inpaint fallback after $reason")
        }
        throw RetouchUnavailable(reason ?: "inpainting failed")
    }

    /** Pixels an [inpaint] call may change, for undo patches. */
    fun affectedRect(image: Bitmap, mask: HoleMask): Rect =
        InpaintPlan.cropFor(mask.dilated(grow(image)).bounds, image.width, image.height)

    private fun grow(image: Bitmap) = max(2, max(image.width, image.height) / 400)

    private fun run(image: Bitmap, mask: HoleMask, method: InpaintMethod, patchBudgetMs: Double) {
        val grown = mask.dilated(grow(image))
        val crop = InpaintPlan.cropFor(grown.bounds, image.width, image.height)
        val workSide = when (method) {
            InpaintMethod.MIGAN, InpaintMethod.LAMA -> MODEL_SIDE
            InpaintMethod.PATCHMATCH -> min(crop.width(), PATCH_SIDE)
            InpaintMethod.TELEA -> min(crop.width(), TELEA_SIDE)
        }
        val src = Bitmap.createBitmap(image, crop.left, crop.top, crop.width(), crop.height())
        val work = ImageTensors.scaled(src, workSide, workSide).let { if (it === src) src.copy(Bitmap.Config.ARGB_8888, true) else it }
        val cropMask = grown.crop(crop)
        val workMask = scaleMask(cropMask, workSide)
        try {
            when (method) {
                InpaintMethod.MIGAN -> model(work, workMask, MIGAN)
                InpaintMethod.LAMA -> model(work, workMask, LAMA)
                InpaintMethod.PATCHMATCH -> {
                    val code = NativeRetouch.patchMatch(work, workMask.data, PATCH_RADIUS, patchBudgetMs)
                    if (code == NativeRetouch.TIMEOUT) throw RetouchUnavailable("timeout")
                    if (code != NativeRetouch.OK) throw RetouchUnavailable("native $code")
                }
                InpaintMethod.TELEA -> telea(work, workMask, radius = max(3.0, workSide / 128.0))
            }
            composite(image, crop, work, cropMask)
        } finally {
            src.recycle()
            work.recycle()
        }
    }

    private fun model(work: Bitmap, mask: HoleMask, id: String) {
        val n = work.width
        val px = ImageTensors.pixels(work)
        val img = ImageTensors.argbToNchw(px, n, n)
        val hole = Tensor(intArrayOf(1, 1, n, n))
        if (id == MIGAN) {
            for (i in img.data.indices) img.data[i] *= 255f
            for (i in hole.data.indices) hole.data[i] = if (mask.data[i].toInt() != 0) 0f else 255f
        } else {
            for (i in hole.data.indices) hole.data[i] = if (mask.data[i].toInt() != 0) 1f else 0f
        }
        val out = runtime.run(id, listOf(img, hole)).first()
        val d = out.data
        for (i in d.indices) d[i] /= 255f
        work.setPixels(ImageTensors.nchwToArgb(out, n, n), 0, n, 0, 0, n, n)
    }

    private fun composite(image: Bitmap, crop: Rect, work: Bitmap, cropMask: HoleMask) {
        val w = crop.width()
        val h = crop.height()
        val up = if (work.width == w && work.height == h) work else ImageTensors.scaled(work, w, h)
        try {
            val res = ImageTensors.pixels(up)
            val orig = IntArray(w * h).also { image.getPixels(it, 0, w, crop.left, crop.top, w, h) }
            val feather = max(1, max(w, h) / 256)
            val alpha = MaskMath.boxBlur(FloatArray(w * h) { if (cropMask.data[it].toInt() != 0) 1f else 0f }, w, h, feather)
            for (i in orig.indices) {
                // Keep the full selection replaced; the feather only extends outward.
                val a = if (cropMask.data[i].toInt() != 0) 1f else min(1f, alpha[i] * 2f)
                if (a <= 0f) continue
                orig[i] = blend(orig[i], res[i], a)
            }
            image.setPixels(orig, 0, w, crop.left, crop.top, w, h)
        } finally {
            if (up !== work) up.recycle()
        }
    }

    companion object {
        private const val TAG = "Inpainter"
        const val MIGAN = "migan"
        const val LAMA = "lama"
        const val MODEL_SIDE = 512
        private const val PATCH_SIDE = 512
        private const val TELEA_SIDE = 512
        private const val PATCH_RADIUS = 3

        fun blend(a: Int, b: Int, t: Float): Int {
            val r = ((a shr 16 and 0xff) * (1 - t) + (b shr 16 and 0xff) * t).roundToInt()
            val g = ((a shr 8 and 0xff) * (1 - t) + (b shr 8 and 0xff) * t).roundToInt()
            val bl = ((a and 0xff) * (1 - t) + (b and 0xff) * t).roundToInt()
            return (0xff shl 24) or (r shl 16) or (g shl 8) or bl
        }

        fun scaleMask(m: HoleMask, side: Int): HoleMask {
            if (m.width == side && m.height == side) return m
            val out = ByteArray(side * side)
            // Any selected source pixel under a target pixel selects it (no thin-stroke loss).
            for (y in 0 until side) {
                val y0 = y * m.height / side
                val y1 = max(y0 + 1, (y + 1) * m.height / side)
                for (x in 0 until side) {
                    val x0 = x * m.width / side
                    val x1 = max(x0 + 1, (x + 1) * m.width / side)
                    var hit = false
                    var yy = y0
                    while (!hit && yy < min(y1, m.height)) {
                        var xx = x0
                        while (xx < min(x1, m.width)) {
                            if (m.data[yy * m.width + xx].toInt() != 0) { hit = true; break }
                            xx++
                        }
                        yy++
                    }
                    if (hit) out[y * side + x] = 1
                }
            }
            return HoleMask(side, side, out)
        }

        /** OpenCV Telea fast marching inpainting, in place on [work]. */
        fun telea(work: Bitmap, mask: HoleMask, radius: Double) {
            if (!OpenCv.available) throw RetouchUnavailable("OpenCV unavailable")
            val rgba = Mat()
            val rgb = Mat()
            val m = Mat(mask.height, mask.width, org.opencv.core.CvType.CV_8UC1)
            val out = Mat()
            try {
                Utils.bitmapToMat(work, rgba)
                Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
                m.put(0, 0, ByteArray(mask.data.size) { if (mask.data[it].toInt() != 0) 255.toByte() else 0 })
                Photo.inpaint(rgb, m, out, radius, Photo.INPAINT_TELEA)
                Imgproc.cvtColor(out, rgba, Imgproc.COLOR_RGB2RGBA)
                Utils.matToBitmap(rgba, work)
            } finally {
                rgba.release()
                rgb.release()
                m.release()
                out.release()
            }
        }

    }
}
