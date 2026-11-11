/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.develop

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.lifecycleScope
import dev.opencamera.app.develop.export.ExportOptions
import dev.opencamera.app.develop.export.Exporter
import dev.opencamera.app.develop.mask.AiMaskProvider
import dev.opencamera.app.develop.model.CurvePoint
import dev.opencamera.app.develop.model.EditStack
import dev.opencamera.app.develop.model.LinearShape
import dev.opencamera.app.develop.model.LocalMask
import dev.opencamera.app.develop.model.MaskComponent
import dev.opencamera.app.develop.model.MaskOp
import dev.opencamera.app.develop.model.Params
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Measurement run for the performance table (started with the `develop_benchmark` extra):
 * slider renders on the proxy, AI masks and a full-resolution export. Results go to logcat
 * with the tag `DevelopBench`. Leaves the user's saved edit untouched.
 */
class DevelopBenchmark(private val a: DevelopActivity) {
    private val queue = ArrayDeque<Pair<String, EditStack>>()
    private val results = LinkedHashMap<String, MutableList<Long>>()
    private var current: String? = null
    private val base = a.stack

    private val full: EditStack = base
        .with(Params.EXPOSURE, 0.4f).with(Params.CONTRAST, 15f).with(Params.HIGHLIGHTS, -40f).with(Params.SHADOWS, 30f)
        .with(Params.WHITES, 10f).with(Params.BLACKS, -10f).with(Params.TEMP, 10f).with(Params.VIBRANCE, 20f)
        .with(Params.hsl("blue", "sat"), -20f).with(Params.hsl("orange", "lum"), 10f)
        .with(Params.grade("shadows", "hue"), 210f).with(Params.grade("shadows", "sat"), 15f)
        .with(Params.CLARITY, 20f).with(Params.TEXTURE, 10f).with(Params.DEHAZE, 15f).with(Params.SHARPEN, 40f)
        .with(Params.NR_LUMA, 20f).with(Params.NR_COLOR, 25f).with(Params.VIGNETTE, -20f).with(Params.GRAIN, 20f)
        .with(Params.CROP_ANGLE, 2f).withCurve("rgb", listOf(CurvePoint(0f, 0.03f), CurvePoint(0.5f, 0.52f), CurvePoint(1f, 1f)))
        .copy(lookId = "vivid", masks = listOf(LocalMask("b1", "Sky", listOf(MaskComponent(MaskOp.ADD, LinearShape(0.5f, 0f, 0.5f, 0.5f))),
            mapOf(Params.EXPOSURE to -0.5f, Params.SATURATION to 20f))))

    fun start() {
        repeat(3) { queue += "warmup" to base.with(Params.EXPOSURE, 0.1f * it) }
        for (i in 0 until 10) queue += "exposure" to base.with(Params.EXPOSURE, -1f + i * 0.2f)
        for (i in 0 until 10) queue += "global-mix" to full.copy(masks = emptyList(), lookId = null).with(Params.CONTRAST, i * 5f)
        for (i in 0 until 10) queue += "pyramid" to base.with(Params.CLARITY, i * 8f).with(Params.DEHAZE, i * 5f).with(Params.HIGHLIGHTS, -i * 8f).with(Params.SHADOWS, i * 8f)
        for (i in 0 until 10) queue += "full-stack" to full.with(Params.EXPOSURE, 0.1f * i)
        for (i in 0 until 10) queue += "draft:exposure" to base.with(Params.EXPOSURE, -1f + i * 0.2f)
        for (i in 0 until 10) queue += "draft:full-stack" to full.with(Params.EXPOSURE, 0.1f * i)
        Log.i(TAG, "start ${a.engine.proxyWidth}x${a.engine.proxyHeight} gpu=${a.engine.gpu}")
        next()
    }

    fun onRendered(stats: DevelopEngine.RenderStats) {
        val c = current ?: return
        results.getOrPut(c) { ArrayList() } += stats.ms
        current = null
        a.previewFrame.post { next() }
    }

    private fun next() {
        val item = queue.removeFirstOrNull()
        if (item == null) {
            report()
            return
        }
        current = item.first
        a.engine.look = if (item.second.lookId != null) a.looks.params(item.second.lookId, 1f) else null
        a.engine.render(item.second, draft = item.first.startsWith("draft:"))
    }

    private fun report() {
        for ((k, v) in results) {
            val s = v.sorted()
            Log.i(TAG, "render $k median ${s[s.size / 2]} ms min ${s.first()} max ${s.last()} n=${s.size}")
        }
        a.lifecycleScope.launch {
            // Pyramid recompute: re-open the same proxy (linearize + mips + both blurs).
            val px = a.engine.proxyPixels()
            val w = a.engine.proxyWidth; val h = a.engine.proxyHeight
            if (px != null) {
                val bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
                val t0 = SystemClock.elapsedRealtime()
                a.engine.open(bmp) { Log.i(TAG, "source+pyramid rebuild ${SystemClock.elapsedRealtime() - t0} ms (see DevelopEngine 'Source' line)") }
                val i = a.info
                if (i != null) {
                    for (kind in listOf(AiMaskProvider.KIND_SUBJECT, AiMaskProvider.KIND_PERSON)) {
                        val t = SystemClock.elapsedRealtime()
                        val shape = withContext(Dispatchers.Default) { runCatching { a.ai.compute(kind, bmp, a.store.assetDir(i.ref)) }.getOrNull() }
                        Log.i(TAG, "ai mask $kind ${if (shape == null) "unavailable" else "ok"} ${SystemClock.elapsedRealtime() - t} ms")
                    }
                    val look = withContext(Dispatchers.IO) { a.looks.params("vivid", 1f) }
                    for (opts in listOf(ExportOptions(), ExportOptions(metadata = dev.opencamera.app.develop.export.MetadataPolicy.STRIP_LOCATION))) {
                        val r = withContext(Dispatchers.Default) {
                            runCatching { Exporter(a).export(i, full.copy(masks = emptyList()), opts, look, { null }, null) }
                        }
                        r.onSuccess { Log.i(TAG, "export ${it.width}x${it.height} ${it.ms} ms uri=${it.uri} notes=${it.notes}") }
                            .onFailure { Log.w(TAG, "export failed", it) }
                    }
                }
            }
            a.engine.render(a.stack)
            a.bench = null
            Log.i(TAG, "done")
        }
    }

    companion object {
        private const val TAG = "DevelopBench"
    }
}
