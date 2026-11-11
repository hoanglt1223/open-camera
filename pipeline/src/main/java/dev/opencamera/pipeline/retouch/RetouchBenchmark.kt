/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.retouch

import android.graphics.Bitmap
import android.os.SystemClock
import dev.opencamera.ml.ImageTensors
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Device benchmark of the retouch tools (median of N runs) plus the checks of the acceptance
 * criteria that need real images: skin texture kept, replay from JSON identical. Used by the
 * host's debug entry point; results are plain lines for logcat and the report.
 */
class RetouchBenchmark(private val toolbox: RetouchToolbox) {
    fun run(image: Bitmap, label: String, runs: Int, include: Set<String>): List<String> {
        val out = ArrayList<String>()
        val w = image.width
        val h = image.height
        val faces = runCatching { toolbox.regions.faces(image) }.getOrDefault(emptyList())
        val cheek = faces.firstOrNull()?.landmarks?.get(205)?.let { NPoint(it.x / w, it.y / h) } ?: NPoint(0.5f, 0.5f)
        val disc = Stroke(listOf(NPoint(0.5f, 0.55f)), 0.08f)
        val cases = listOf(
            "skin" to RetouchParams(strength = 0.35f),
            "blemish" to RetouchParams(taps = listOf(cheek)),
            "teeth" to RetouchParams(strength = 0.5f),
            "eyes" to RetouchParams(strength = 0.4f),
            "redeye" to RetouchParams(),
            "reshape" to RetouchParams(values = mapOf("slim" to 1f, "eyes" to 1f)),
            "erase:migan" to RetouchParams(method = "migan", strokes = listOf(disc), values = mapOf("strict" to 1f)),
            "erase:patchmatch" to RetouchParams(method = "patchmatch", strokes = listOf(disc), values = mapOf("strict" to 1f)),
            "erase:telea" to RetouchParams(method = "telea", strokes = listOf(disc), values = mapOf("strict" to 1f)),
            "erase:lama" to RetouchParams(method = "lama", strokes = listOf(disc), values = mapOf("strict" to 1f)),
            "whitebalance" to RetouchParams(method = WhiteBalanceTool.GRAY_WORLD, strength = 1f),
            "geometry" to RetouchParams(method = GeometryTool.BOTH),
        )
        for ((name, params) in cases) {
            if (include.isNotEmpty() && name.substringBefore(':') !in include && name !in include) continue
            val tool = toolbox.tool(name.substringBefore(':')) ?: continue
            val n = if (name == "erase:lama") max(1, runs / 5) else runs
            val times = ArrayList<Long>()
            var note = ""
            for (i in 0 until n) {
                val copy = image.copy(Bitmap.Config.ARGB_8888, true)
                val t0 = SystemClock.elapsedRealtime()
                try {
                    val r = tool.apply(copy, params)
                    times += SystemClock.elapsedRealtime() - t0
                    note = "${r.method} ${r.note.orEmpty()}"
                    if (r.bitmap !== copy) r.bitmap.recycle()
                } catch (e: RetouchUnavailable) {
                    note = "unavailable: ${e.message}"
                    copy.recycle()
                    break
                } catch (e: OutOfMemoryError) {
                    note = "OOM"
                    copy.recycle()
                    break
                }
                copy.recycle()
            }
            val median = times.sorted().getOrNull(times.size / 2)
            out += "$label ${w}x$h $name median=${median ?: "-"} ms runs=${times.size} [$note]"
        }
        if (include.isEmpty() || "skin" in include) faces.firstOrNull()?.let { out += textureCheck(image, it, label) }
        return out
    }

    /** High-frequency energy inside the skin mask after default smoothing, relative to before. */
    private fun textureCheck(image: Bitmap, face: FaceGeometry, label: String): String {
        val mask = toolbox.regions.skin(face, image) ?: return "$label texture: no mask"
        val copy = image.copy(Bitmap.Config.ARGB_8888, true)
        return try {
            toolbox.tool("skin")!!.apply(copy, RetouchParams(strength = toolbox.tool("skin")!!.defaultStrength))
            val before = hfEnergy(image, mask)
            val after = hfEnergy(copy, mask)
            "$label texture kept=%.0f%% (hf energy %.1f -> %.1f, criterion >= 50%%)".format(java.util.Locale.US, 100 * after / before, before, after)
        } finally {
            copy.recycle()
        }
    }

    private fun hfEnergy(image: Bitmap, mask: RegionMask): Double {
        val r = mask.pixelRect.apply { intersect(0, 0, image.width, image.height) }
        val w = r.width()
        val h = r.height()
        val px = IntArray(w * h).also { image.getPixels(it, 0, w, r.left, r.top, w, h) }
        val luma = FloatArray(w * h) { i -> val c = px[i]; 0.299f * (c shr 16 and 0xff) + 0.587f * (c shr 8 and 0xff) + 0.114f * (c and 0xff) }
        val low = MaskMath.boxBlur(luma, w, h, 2)
        var e = 0.0
        var n = 0.0
        for (y in 0 until h) for (x in 0 until w) {
            val mx = ((x + 0.5f) / w * mask.mw).toInt().coerceIn(0, mask.mw - 1)
            val my = ((y + 0.5f) / h * mask.mh).toInt().coerceIn(0, mask.mh - 1)
            val m = mask.values[my * mask.mw + mx]
            if (m < 0.8f) continue
            val d = luma[y * w + x] - low[y * w + x]
            e += d * d
            n++
        }
        return if (n > 0) e / n else 0.0
    }

    /** Applies [ops] through a session, then replays the JSON on the original; returns PSNR (dB). */
    fun replayCheck(image: Bitmap, ops: List<Pair<String, RetouchParams>>): Double {
        val session = RetouchSession(image, toolbox)
        for ((id, p) in ops) runCatching { session.apply(toolbox.tool(id)!!, p) }
        val stack = RetouchStack.fromJson(session.stack.toJson())!!
        val replayed = toolbox.replay(image.copy(Bitmap.Config.ARGB_8888, true), stack.active).bitmap
        return psnr(session.current, replayed).also { replayed.recycle() }
    }

    companion object {
        fun psnr(a: Bitmap, b: Bitmap): Double {
            if (a.width != b.width || a.height != b.height) return 0.0
            val pa = ImageTensors.pixels(a)
            val pb = ImageTensors.pixels(b)
            var se = 0.0
            for (i in pa.indices) for (s in intArrayOf(0, 8, 16)) {
                val d = (pa[i] shr s and 0xff) - (pb[i] shr s and 0xff)
                se += d * d
            }
            val mse = se / (pa.size * 3.0)
            return if (mse == 0.0) 99.0 else 10 * log10(255.0 * 255.0 / mse)
        }

        /** Copy of [src] scaled so the long side is at most [side]. */
        fun scaledTo(src: Bitmap, side: Int): Bitmap {
            val k = side.toFloat() / max(src.width, src.height)
            if (k >= 1f) return src.copy(Bitmap.Config.ARGB_8888, true)
            return ImageTensors.scaled(src, (src.width * k).roundToInt(), (src.height * k).roundToInt())
        }
    }
}
