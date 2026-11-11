/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.opencamera.ml.Accelerator
import dev.opencamera.ml.BackendSelector
import dev.opencamera.ml.ImageTensors
import dev.opencamera.ml.MlException
import dev.opencamera.ml.MlRuntime
import dev.opencamera.ml.Tensor
import dev.opencamera.ml.tasks.AestheticScorer
import dev.opencamera.ml.tasks.Composition
import dev.opencamera.ml.tasks.FaceMesh
import dev.opencamera.ml.tasks.ImageRestorer
import dev.opencamera.ml.tasks.SaliencyDetector
import dev.opencamera.ml.tasks.SceneDetector
import dev.opencamera.ml.tasks.Segmenter
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Random
import kotlin.math.log10

/**
 * Device benchmark and correctness checks for the ML features. Optional inputs are read from
 * the test app's files dir: `bench/portrait.jpg` (a photo with a person) and downloaded models
 * under `models/<id>/`. Results go to logcat (tag AiBench) and `files/ai-bench.txt`.
 */
@RunWith(AndroidJUnit4::class)
class AiBenchmarkTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val runtime = MlRuntime.get(context)
    private val out = ArrayList<String>()

    private fun log(line: String) {
        Log.i(TAG, line)
        out += line
    }

    private inline fun <T> timed(block: () -> T): Pair<T, Long> {
        val t0 = SystemClock.elapsedRealtime()
        val r = block()
        return r to SystemClock.elapsedRealtime() - t0
    }

    private fun portrait12mp(): Bitmap {
        val f = File(context.filesDir, "bench/portrait.jpg")
        val src = if (f.isFile) {
            BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
        } else {
            null
        }
        val out = Bitmap.createBitmap(3000, 4000, Bitmap.Config.ARGB_8888)
        if (src != null) {
            Canvas(out).drawBitmap(src, null, Rect(0, 0, 3000, 4000), Paint(Paint.FILTER_BITMAP_FLAG))
            src.recycle()
        } else {
            log("No bench/portrait.jpg: using a synthetic image")
            out.eraseColor(0xff668899.toInt())
        }
        return out
    }

    @Test
    fun benchmark() {
        log("models: " + runtime.registry.specs.values.sortedBy { it.id }.joinToString { "${it.id}=${runtime.isAvailable(it.id)}" })
        backends()
        features()
        File(context.filesDir, "ai-bench.txt").writeText(out.joinToString("\n") + "\n")
    }

    /** Each model on CPU and GPU: median time and agreement with the CPU output. */
    private fun backends() {
        for (spec in runtime.registry.specs.values.sortedBy { it.id }) {
            if (!runtime.isAvailable(spec.id)) continue
            val input = listOf(MlRuntime.randomInput(spec.inputShape, 11))
            val iterations = if (spec.sizeBytes > 50_000_000) 2 else 10
            var reference: FloatArray? = null
            for (acc in listOf(Accelerator.CPU, Accelerator.GPU, Accelerator.NNAPI)) {
                if (acc !in spec.accelerators && acc != Accelerator.CPU) continue
                runtime.preference = BackendSelector.Preference.valueOf(acc.name)
                try {
                    val b = runtime.benchmark(spec.id, iterations, input)
                    val outData = runtime.run(spec.id, input, spec.timeoutMs * 3).first().data
                    if (b.accelerator == Accelerator.CPU) reference = outData
                    val cos = reference?.let { Tensor.cosine(it, outData) } ?: Double.NaN
                    log("backend %s requested=%s actual=%s open=%d ms median=%.1f ms cosine_vs_cpu=%.5f".format(
                        spec.id, acc, b.accelerator, b.openMs, b.medianMs, cos))
                } catch (e: Exception) {
                    log("backend ${spec.id} $acc failed: $e")
                }
            }
        }
        runtime.preference = BackendSelector.Preference.AUTO
    }

    private fun features() {
        val segmenter = Segmenter(runtime)
        val faces = FaceMesh(runtime)
        val full = portrait12mp()

        // Portrait: segmentation + refinement, then full 12 MP render.
        val bokeh = BokehProcessor(segmenter)
        val (prep, prepMs) = timed { bokeh.prepare(full, 0) }
        log("portrait prepare(12MP -> 1024) ${prepMs} ms model=${prep?.model} coverage=%.3f".format(prep?.subjectCoverage ?: -1f))
        if (prep != null) {
            saveMask(prep)
            val img = full.copy(Bitmap.Config.ARGB_8888, true)
            val (ok, renderMs) = timed { bokeh.render(img, prep, BokehProcessor.Params(0.9f)) }
            log("portrait render 12MP ${renderMs} ms ok=$ok total=${prepMs + renderMs} ms (target < 2000)")
            save(img, "bokeh.jpg")
            img.recycle()
            prep.release()
        }
        val withMulti = full.copy(Bitmap.Config.ARGB_8888, true)
        val (outcome, procMs) = timed { bokeh.process(withMulti, 0, BokehProcessor.Params(0.9f)) }
        log("portrait process 12MP (default model choice) $procMs ms outcome=$outcome")
        withMulti.recycle()

        // Live preview path at 480 px with the small model.
        val preview = ImageTensors.scaled(full, 360, 480)
        var liveMs = 0L
        repeat(20) {
            val (p, ms) = timed {
                bokeh.prepare(preview, 0, 480, preferMulticlass = false)?.let { pp ->
                    bokeh.renderPreview(pp, BokehProcessor.Params(0.9f)).also { pp.release() }
                }
            }
            p?.recycle()
            if (it >= 5) liveMs += ms
        }
        var segMs = 0L
        var prepOnlyMs = 0L
        repeat(10) {
            segMs += timed { segmenter.segment(preview, preferMulticlass = false) }.second
            val (pp, ms) = timed { bokeh.prepare(preview, 0, 480, preferMulticlass = false) }
            prepOnlyMs += ms
            pp?.release()
        }
        log("live breakdown: segment %.1f ms, prepare (segment+resample+refine) %.1f ms".format(segMs / 10f, prepOnlyMs / 10f))
        log("live portrait 480px %.1f ms/frame (%.1f fps processing; target >= 15)".format(liveMs / 15f, 15000f / liveMs.coerceAtLeast(1)))

        // Scene detection and composition on a preview-sized frame.
        val frame = ImageTensors.scaled(full, 480, 640)
        val scene = SceneDetector(context, runtime, faces)
        scene.detect(frame)
        var sceneMs = 0L
        var detection: SceneDetector.Detection? = null
        repeat(20) { val (d, ms) = timed { scene.detect(frame) }; detection = d; sceneMs += ms }
        log("scene detect %.1f ms/frame result=%s (target < 30)".format(sceneMs / 20f, detection))
        val saliency = SaliencyDetector(runtime)
        saliency.saliency(frame)
        val (sal, salMs) = timed { saliency.saliency(frame) }
        log("composition saliency ${salMs} ms byModel=${sal.second} suggestion=${Composition.suggest(sal.first)}")

        // Face mesh and beauty.
        val (faceList, faceMs) = timed { faces.detect(ImageTensors.scaled(full, 768, 1024)) }
        log("face mesh ${faceMs} ms faces=${faceList?.size}")
        val beautyImg = full.copy(Bitmap.Config.ARGB_8888, true)
        val (beautyOutcome, beautyMs) = timed { BeautyProcessor(faces, segmenter).process(beautyImg, 0, 0.6f) }
        log("beauty 12MP ${beautyMs} ms outcome=$beautyOutcome")
        save(beautyImg, "beauty.jpg")
        beautyImg.recycle()

        // Aesthetic best-frame: the sharp frame must win over blurred copies.
        val scorer = AestheticScorer(runtime)
        val sharp = ImageTensors.scaled(full, 600, 800)
        val frames = listOf(blur(sharp, 8), sharp, blur(sharp, 3))
        val (best, bestMs) = timed { scorer.best(frames) }
        log("best shot of 3: index=$best (expected 1) ${bestMs} ms model=${scorer.modelAvailable} scores=${frames.map { scorer.score(it).value }}")
        assertTrue("Sharp frame not chosen", best == 1)

        // Denoise: PSNR on a noisy 1 MP crop, classical and model.
        val clean = Bitmap.createBitmap(full, 1000, 1200, 1024, 1024).copy(Bitmap.Config.ARGB_8888, true)
        val noisy = addNoise(clean, 18f)
        val enhance = EnhanceProcessor(ImageRestorer(runtime))
        val classical = noisy.copy(Bitmap.Config.ARGB_8888, true)
        val (_, classicalMs) = timed { enhance.denoise(classical, 1f, 1000, useModel = false) }
        log("denoise classical 1MP ${classicalMs} ms psnr noisy=%.2f -> %.2f dB".format(psnr(clean, noisy), psnr(clean, classical)))
        val full12 = full.copy(Bitmap.Config.ARGB_8888, true)
        val (_, classical12) = timed { enhance.denoise(full12, 1f, 1000, useModel = false) }
        log("denoise classical 12MP ${classical12} ms")
        full12.recycle()
        if (ImageRestorer(runtime).denoiseAvailable) {
            val (r, modelMs) = timed { enhance.denoise(noisy.copy(Bitmap.Config.ARGB_8888, true), 1f, 300_000) }
            log("denoise model 1MP ${modelMs} ms method=${r.method} psnr=%.2f dB; 12MP estimate %.0f s (target < 10 s)".format(
                psnr(clean, r.bitmap), modelMs * 12 / 1000.0))
            val big = full.copy(Bitmap.Config.ARGB_8888, true)
            val (r12, ms12) = timed { enhance.denoise(big, 1f, 10_000) }
            log("denoise 12MP with 10 s budget: ${ms12} ms method=${r12.method} fallback=${r12.fallbackReason}")
        }

        // Super-resolution 2x on a 512 px crop.
        val small = Bitmap.createBitmap(full, 1200, 1400, 512, 512)
        val (srClassical, srcMs) = timed { enhance.upscale(small, 2, 1000, useModel = false) }
        log("upscale classical 512->1024 ${srcMs} ms")
        srClassical.bitmap.recycle()
        if (ImageRestorer(runtime).superResAvailable) {
            val (sr, srMs) = timed { enhance.upscale(small, 2, 120_000) }
            log("upscale model 512->1024 ${srMs} ms method=${sr.method}; 12MP x2 estimate %.0f s".format(srMs * 12_000_000.0 / (512 * 512) / 1000))
            save(sr.bitmap, "upscale.jpg")
        }

        // Fallbacks: a missing model must surface as MISSING, not a crash.
        try {
            runtime.run("does_not_exist", emptyList())
        } catch (e: MlException) {
            log("missing model -> ${e.reason}")
            assertTrue(e.reason == MlException.Reason.MISSING)
        }
        full.recycle()
    }

    private fun blur(src: Bitmap, factor: Int): Bitmap {
        val s = Bitmap.createScaledBitmap(src, src.width / factor, src.height / factor, true)
        return Bitmap.createScaledBitmap(s, src.width, src.height, true).also { s.recycle() }
    }

    private fun addNoise(src: Bitmap, sigma: Float): Bitmap {
        val px = ImageTensors.pixels(src)
        val r = Random(3)
        for (i in px.indices) {
            val c = px[i]
            fun n(v: Int) = (v + r.nextGaussian() * sigma).toInt().coerceIn(0, 255)
            px[i] = (0xff shl 24) or (n(c shr 16 and 0xff) shl 16) or (n(c shr 8 and 0xff) shl 8) or n(c and 0xff)
        }
        return Bitmap.createBitmap(px, src.width, src.height, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, true)
    }

    private fun psnr(a: Bitmap, b: Bitmap): Double {
        val pa = ImageTensors.pixels(a)
        val pb = ImageTensors.pixels(b)
        var se = 0.0
        for (i in pa.indices) for (s in intArrayOf(0, 8, 16)) {
            val d = ((pa[i] shr s) and 0xff) - ((pb[i] shr s) and 0xff)
            se += d * d
        }
        val mse = se / (pa.size * 3)
        return 10 * log10(255.0 * 255.0 / mse.coerceAtLeast(1e-9))
    }

    private fun save(b: Bitmap, name: String) {
        val dir = File(context.filesDir, "bench-out").apply { mkdirs() }
        File(dir, name).outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    }

    private fun saveMask(p: BokehProcessor.Prepared) {
        val px = IntArray(p.mask.data.size) { val v = (p.mask.data[it] * 255).toInt().coerceIn(0, 255); (0xff shl 24) or (v shl 16) or (v shl 8) or v }
        save(Bitmap.createBitmap(px, p.mask.width, p.mask.height, Bitmap.Config.ARGB_8888), "mask.jpg")
    }

    private companion object {
        const val TAG = "AiBench"
    }
}
