/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Gainmap
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.annotation.RequiresApi
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Luma plane (0..255, gamma encoded) at reduced resolution. */
class LumaPlane(val width: Int, val height: Int, val px: FloatArray) {
    init {
        require(width > 0 && height > 0 && px.size >= width * height)
    }
}

/** Single channel gain map: [codes] are log2(ratio) scaled to 0..255 over [1, maxRatio]. */
class GainmapPlane(val width: Int, val height: Int, val codes: ByteArray, val maxRatio: Float)

/**
 * Ultra HDR for merged bursts. The HDR rendition is the merged luma before local tone mapping,
 * brought back to nominal exposure (the burst is underexposed by the tone step), with no clip.
 * The SDR rendition is the tone-mapped output. The gain map stores HDR / SDR where it exceeds 1,
 * so on an HDR display highlights get back the range the tone curve compressed; shadows and
 * midtones, which the tone curve lifted, are shown as in SDR.
 */
object BurstGainmap {
    /** Must match kToneBaseFactor in mf/pipeline.h. */
    const val FACTOR = 4

    /** Headroom cap, in stops. */
    const val MAX_STOPS = 2f

    /** Below this peak ratio the gain map adds nothing visible; a plain JPEG is written. */
    const val MIN_USEFUL_RATIO = 1.1f

    private const val GAMMA = 2.2f
    private const val SDR_FLOOR = 1e-3f

    fun toLinear(code: Float): Float = (code.coerceIn(0f, 255f) / 255f).pow(GAMMA)

    /** Box-downsamples the Y plane of [nv21] by [factor] to 0..255 floats. */
    fun downsampleLuma(nv21: ByteArray, width: Int, height: Int, factor: Int): LumaPlane {
        val w = width / factor
        val h = height / factor
        val out = FloatArray(w * h)
        val inv = 1f / (factor * factor)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var s = 0
                for (j in 0 until factor) {
                    val row = (y * factor + j) * width + x * factor
                    for (i in 0 until factor) s += nv21[row + i].toInt() and 0xFF
                }
                out[y * w + x] = s * inv
            }
        }
        return LumaPlane(w, h, out)
    }

    /** HDR / SDR linear ratio per pixel, clamped to [1, 2^[MAX_STOPS]]. */
    fun ratios(hdrBase: LumaPlane, sdr: LumaPlane, toneEvStep: Float): FloatArray {
        val w = min(hdrBase.width, sdr.width)
        val h = min(hdrBase.height, sdr.height)
        val exposure = 2f.pow(toneEvStep.coerceIn(0f, MAX_STOPS))
        val cap = 2f.pow(MAX_STOPS)
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val hdr = toLinear(hdrBase.px[y * hdrBase.width + x]) * exposure
                val s = max(toLinear(sdr.px[y * sdr.width + x]), SDR_FLOOR)
                out[y * w + x] = (hdr / s).coerceIn(1f, cap)
            }
        }
        return out
    }

    /** Log-encodes [ratios] (w x h); null when the peak ratio is below [MIN_USEFUL_RATIO]. */
    fun encode(ratios: FloatArray, width: Int, height: Int): GainmapPlane? {
        var peak = 1f
        for (r in ratios) if (r > peak) peak = r
        if (peak < MIN_USEFUL_RATIO) return null
        val scale = 255f / ln(peak)
        val codes = ByteArray(width * height) { (ln(ratios[it]) * scale).roundToInt().coerceIn(0, 255).toByte() }
        return GainmapPlane(width, height, codes, peak)
    }

    /** Gain map of a merged image, or null when it has no tone base or no useful headroom. */
    fun build(image: MergedImage, toneEvStep: Float): GainmapPlane? {
        val base = image.toneBase ?: return null
        if (toneEvStep <= 0f) return null
        val sdr = downsampleLuma(image.nv21, image.width, image.height, FACTOR)
        val w = min(base.width, sdr.width)
        val h = min(base.height, sdr.height)
        return encode(ratios(base, sdr, toneEvStep), w, h)
    }

    /**
     * Runs [encoder]; any failure, including running out of memory, or a null result gives
     * back [sdrJpeg] so the photo is always saved.
     */
    inline fun withGainmapOr(sdrJpeg: ByteArray, onError: (Throwable) -> Unit, encoder: () -> ByteArray?): ByteArray =
        try {
            encoder() ?: sdrJpeg
        } catch (e: Exception) {
            onError(e)
            sdrJpeg
        } catch (e: OutOfMemoryError) {
            onError(e)
            sdrJpeg
        }

    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun supported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /** Re-encodes [sdrJpeg] as JPEG_R carrying [plane]. Throws on failure. */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun toUltraHdrJpeg(sdrJpeg: ByteArray, plane: GainmapPlane, quality: Int = 95): ByteArray {
        val bitmap = BitmapFactory.decodeByteArray(sdrJpeg, 0, sdrJpeg.size) ?: error("Cannot decode SDR JPEG")
        val contents = Bitmap.createBitmap(plane.width, plane.height, Bitmap.Config.ALPHA_8)
        try {
            val stride = contents.rowBytes
            val buf = ByteBuffer.allocate(stride * plane.height)
            for (y in 0 until plane.height) {
                buf.position(y * stride)
                buf.put(plane.codes, y * plane.width, plane.width)
            }
            buf.rewind()
            contents.copyPixelsFromBuffer(buf)
            val m = plane.maxRatio
            bitmap.gainmap = Gainmap(contents).apply {
                setRatioMin(1f, 1f, 1f)
                setRatioMax(m, m, m)
                setGamma(1f, 1f, 1f)
                setEpsilonSdr(0f, 0f, 0f)
                setEpsilonHdr(0f, 0f, 0f)
                minDisplayRatioForHdrTransition = 1f
                displayRatioForFullHdr = m
            }
            val out = ByteArrayOutputStream(sdrJpeg.size + sdrJpeg.size / 8)
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) { "JPEG_R compress failed" }
            return out.toByteArray()
        } finally {
            bitmap.recycle()
            contents.recycle()
        }
    }
}
