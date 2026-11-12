/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.develop.mask

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Log
import dev.opencamera.app.develop.model.AiShape
import dev.opencamera.app.develop.render.ColorMath
import dev.opencamera.ml.MlRuntime
import dev.opencamera.ml.tasks.SaliencyDetector
import dev.opencamera.ml.tasks.Segmenter
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * AI masks through the shared `:ml` runtime: subject (saliency model), person, hair and skin
 * (selfie segmenters). Each runs once on the proxy, is refined with a guided filter against
 * the photo, and is stored as an 8-bit PNG in the edit's asset directory with the model id.
 * Sky has no model in this build and is reported unavailable.
 */
class AiMaskProvider(context: Context) {
    private val runtime by lazy { MlRuntime.get(context) }
    private val cache = HashMap<String, Triple<FloatArray, Int, Int>>()

    private val reasons = java.util.concurrent.ConcurrentHashMap<String, java.util.Optional<Reason>>()

    /** Resolves every kind (model catalog, files, prefs) so the panels never do it on main. */
    fun warm() = KINDS.forEach { unavailableReason(it) }

    /** Null when [kind] can run, else a short reason. Cached for the editor session. */
    fun unavailableReason(kind: String): Reason? =
        reasons.getOrPut(kind) { java.util.Optional.ofNullable(resolve(kind)) }.orElse(null)

    private fun resolve(kind: String): Reason? = runCatching {
        when (kind) {
            KIND_SKY -> Reason.NO_SKY_MODEL
            KIND_SUBJECT -> if (runtime.isAvailable(SaliencyDetector.MODEL)) null else Reason.NO_MODEL
            KIND_PERSON -> if (runtime.isAvailable(Segmenter.SELFIE) || runtime.isAvailable(Segmenter.MULTICLASS)) null else Reason.NO_MODEL
            KIND_HAIR, KIND_FACE_SKIN, KIND_BODY_SKIN -> if (runtime.isAvailable(Segmenter.MULTICLASS)) null else Reason.NO_MODEL
            else -> Reason.NO_MODEL
        }
    }.getOrDefault(Reason.NO_MODEL)

    enum class Reason { NO_MODEL, NO_SKY_MODEL }

    /** Runs the model on [proxy] and stores the mask in [dir]; null when the model is missing or fails. */
    fun compute(kind: String, proxy: Bitmap, dir: File): AiShape? {
        val (mask, model) = try {
            when (kind) {
                KIND_SUBJECT -> SaliencyDetector(runtime).saliency(proxy).let { (m, usedModel) ->
                    if (!usedModel) return null
                    m to SaliencyDetector.MODEL
                }
                else -> {
                    val r = Segmenter(runtime).segment(proxy, preferMulticlass = kind != KIND_PERSON, timeoutMs = 8_000) ?: return null
                    val m = when (kind) {
                        KIND_PERSON -> r.person
                        KIND_HAIR -> r.hair
                        KIND_FACE_SKIN -> r.faceSkin
                        KIND_BODY_SKIN -> r.bodySkin
                        else -> null
                    } ?: return null
                    m to r.model
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "AI mask $kind failed", e)
            return null
        }
        // Refine at up to 512 px against the photo's luminance.
        val s = minOf(1f, 512f / max(proxy.width, proxy.height))
        val w = max(1, (proxy.width * s).roundToInt()); val h = max(1, (proxy.height * s).roundToInt())
        val guideBmp = Bitmap.createScaledBitmap(proxy, w, h, true)
        val px = IntArray(w * h).also { guideBmp.getPixels(it, 0, w, 0, 0, w, h) }
        if (guideBmp !== proxy) guideBmp.recycle()
        val guide = FloatArray(w * h) { i -> val p = px[i]; ColorMath.luma(((p shr 16) and 255) / 255f, ((p shr 8) and 255) / 255f, (p and 255) / 255f) }
        val coarse = MaskRaster.resize(mask.data, mask.width, mask.height, w, h)
        val refined = MaskRaster.guidedFilter(guide, coarse, w, h, max(2, w / 64), 1e-3f)
        dir.mkdirs()
        val name = "ai-$kind-${model.replace(Regex("[^A-Za-z0-9_]"), "_")}-${System.currentTimeMillis().toString(36)}.png"
        val out = Bitmap.createBitmap(IntArray(w * h) { i -> val v = (refined[i] * 255f).roundToInt().coerceIn(0, 255); Color.rgb(v, v, v) }, w, h, Bitmap.Config.ARGB_8888)
        File(dir, name).outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        out.recycle()
        synchronized(cache) { cache[File(dir, name).path] = Triple(refined, w, h) }
        return AiShape(kind, name, model)
    }

    fun load(shape: AiShape, dir: File): Triple<FloatArray, Int, Int>? {
        val f = File(dir, shape.file ?: return null)
        synchronized(cache) { cache[f.path]?.let { return it } }
        val bmp = runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull() ?: return null
        val px = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
        val t = Triple(FloatArray(px.size) { ((px[it] shr 8) and 255) / 255f }, bmp.width, bmp.height)
        bmp.recycle()
        synchronized(cache) { cache[f.path] = t }
        return t
    }

    companion object {
        private const val TAG = "AiMaskProvider"
        const val KIND_SUBJECT = "subject"
        const val KIND_PERSON = "person"
        const val KIND_HAIR = "hair"
        const val KIND_FACE_SKIN = "faceSkin"
        const val KIND_BODY_SKIN = "bodySkin"
        const val KIND_SKY = "sky"
        private val KINDS = listOf(KIND_SUBJECT, KIND_PERSON, KIND_HAIR, KIND_FACE_SKIN, KIND_BODY_SKIN, KIND_SKY)
    }
}
