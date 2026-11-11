/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.develop.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import dev.opencamera.app.develop.auto.AutoAdjust
import dev.opencamera.app.develop.io.SourceLoader
import dev.opencamera.app.develop.mask.MaskRaster
import dev.opencamera.app.develop.model.AiShape
import dev.opencamera.app.develop.model.ColorRangeShape
import dev.opencamera.app.develop.model.EditStack
import dev.opencamera.app.develop.model.LuminanceRangeShape
import dev.opencamera.app.develop.render.CpuDevelop
import dev.opencamera.app.develop.render.DevelopUniforms
import dev.opencamera.app.develop.render.Geometry
import dev.opencamera.app.develop.render.GlDevelop
import dev.opencamera.app.develop.tools.ToolRegistry
import dev.opencamera.pipeline.looks.LookParams
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class ExportFormat(val mime: String, val ext: String) {
    JPEG("image/jpeg", "jpg"), HEIF("image/heic", "heic"), AVIF("image/avif", "avif"), PNG("image/png", "png");

    companion object {
        /** Formats this device can encode right now. */
        fun available(): List<ExportFormat> = buildList {
            add(JPEG)
            if (hasEncoder(MediaFormat.MIMETYPE_VIDEO_HEVC)) add(HEIF)
            if (avifFormat() != null) add(AVIF)
            add(PNG)
        }

        fun avifFormat(): Bitmap.CompressFormat? = Bitmap.CompressFormat.entries.firstOrNull { it.name == "AVIF" }

        private fun hasEncoder(mime: String) = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { it.isEncoder && mime in it.supportedTypes }
        }.getOrDefault(false)
    }
}

enum class MetadataPolicy { KEEP_ALL, STRIP_LOCATION, STRIP_ALL }

data class ExportOptions(
    val format: ExportFormat = ExportFormat.JPEG,
    val quality: Int = 92,
    /** 0 = original size, otherwise the long side in pixels. */
    val longSide: Int = 0,
    val metadata: MetadataPolicy = MetadataPolicy.KEEP_ALL,
    /** 0 none, 1 screen, 2 print. */
    val outputSharpen: Int = 0,
    val embedStack: Boolean = true,
)

/**
 * Full-resolution export: the same develop shader as the preview, run over 2048 px output
 * tiles with an overlap margin, then encode, metadata, and a MediaStore insert that stays
 * `IS_PENDING` until the file is complete. Falls back to the CPU kernel when GL fails.
 */
class Exporter(private val context: Context) {
    class Result(val uri: Uri?, val file: File?, val width: Int, val height: Int, val ms: Long, val notes: List<String>)

    fun interface Progress {
        fun onProgress(fraction: Float)
    }

    private val loader = SourceLoader(context)

    /** Renders [stack] over the full image; returns an ARGB bitmap (colour space of the source). */
    fun render(
        info: SourceLoader.Info,
        stack: EditStack,
        options: ExportOptions,
        look: LookParams?,
        aiMasks: (AiShape) -> Triple<FloatArray, Int, Int>?,
        progress: Progress?,
        notes: MutableList<String>,
        cancelled: () -> Boolean = { false },
    ): Bitmap {
        var full = loader.loadFull(info, MAX_PIXELS)
        if (stack.tools.isNotEmpty()) {
            val skipped = ArrayList<String>()
            full = ToolRegistry.applyAll(full, stack.tools, 1f, File(context.filesDir, "edits/assets-${info.ref.hash}"), skipped)
            if (skipped.isNotEmpty()) notes += "tools skipped: ${skipped.distinct().joinToString()}"
        }
        // Global statistics and masks come from a proxy so preview and export agree.
        val proxyLong = 2560
        val s = minOf(1f, proxyLong.toFloat() / max(full.width, full.height))
        val proxy = if (s < 1f) Bitmap.createScaledBitmap(full, max(1, (full.width * s).roundToInt()), max(1, (full.height * s).roundToInt()), true) else full
        val ppx = IntArray(proxy.width * proxy.height).also { proxy.getPixels(it, 0, proxy.width, 0, 0, proxy.width, proxy.height) }
        val atmosphere = AutoAdjust.atmosphere(AutoAdjust.samples(ppx))
        val u = DevelopUniforms.from(stack, look, atmosphere, info.wide)
        val mw = max(1, proxy.width / 2); val mh = max(1, proxy.height / 2)
        val active = stack.masks.filter { it.enabled }.take(DevelopUniforms.MASK_SLOTS)
        val needsPx = active.any { m -> m.components.any { it.shape is LuminanceRangeShape || it.shape is ColorRangeShape } }
        val small = if (needsPx) IntArray(mw * mh) { i -> ppx[((i / mw) * proxy.height / mh) * proxy.width + (i % mw) * proxy.width / mw] } else null
        val maps = active.map { m -> MaskRaster.render(m, mw, mh, small) { a -> aiMasks(a)?.let { MaskRaster.resize(it.first, it.second, it.third, mw, mh) } } }

        val frame = Geometry.frame(stack, full.width, full.height)
        val scale = if (options.longSide > 0) minOf(1f, options.longSide.toFloat() / max(frame.outWidth, frame.outHeight)) else 1f
        val outW = max(1, (frame.outWidth * scale).roundToInt())
        val outH = max(1, (frame.outHeight * scale).roundToInt())
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888, false,
            full.colorSpace?.takeUnless { info.kind != SourceLoader.Kind.RASTER } ?: android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
        try {
            renderGl(u, full, proxy, frame, outW, outH, maps, mw, mh, out, progress, cancelled)
        } catch (e: Throwable) {
            if (e is InterruptedException) throw e
            Log.w(TAG, "GPU export failed, using CPU", e)
            notes += "cpu"
            val prepared = CpuDevelop.prepare(
                IntArray(full.width * full.height).also { full.getPixels(it, 0, full.width, 0, 0, full.width, full.height) }, full.width, full.height,
            )
            val px = CpuDevelop.render(u, prepared, frame, outW, outH, maps.map { MaskRaster.resize(it, mw, mh, full.width, full.height) })
            out.setPixels(px, 0, outW, 0, 0, outW, outH)
        }
        if (proxy !== full) proxy.recycle()
        full.recycle()
        if (options.outputSharpen > 0) OutputSharpen.apply(out, if (options.outputSharpen == 1) 0.35f else 0.7f)
        return out
    }

    private fun renderGl(
        u: DevelopUniforms, full: Bitmap, proxy: Bitmap, frame: Geometry.Frame, outW: Int, outH: Int,
        maps: List<FloatArray>, mw: Int, mh: Int, out: Bitmap, progress: Progress?, cancelled: () -> Boolean,
    ) {
        val g = GlDevelop.create()
        try {
            val global = g.createSource(proxy)
            if (maps.isNotEmpty()) g.setMasks(MaskRaster.pack(maps, mw, mh), mw, mh)
            val tile = min(TILE, g.maxTextureSize / 2)
            val cols = ceil(outW / tile.toFloat()).toInt()
            val rows = ceil(outH / tile.toFloat()).toInt()
            val target = g.newTarget(tile, tile)
            val canvas = Canvas(out)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            // Same colour space as the output, so drawing the tile does not convert colours.
            val tileBmp = Bitmap.createBitmap(tile, tile, Bitmap.Config.ARGB_8888, false, out.colorSpace ?: android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB))
            // Overlap: the fine-detail blur plus bilinear footprint, in source pixels.
            val margin = (max(full.width, full.height) / GlDevelop.SMALL_BLUR_DIVISOR * 4f + 4f).toInt()
            for (r in 0 until rows) for (c in 0 until cols) {
                if (cancelled()) throw InterruptedException("Export cancelled")
                val x0 = c * tile; val y0 = r * tile
                val tw = min(tile, outW - x0); val th = min(tile, outH - y0)
                val tu = floatArrayOf(x0 / outW.toFloat(), y0 / outH.toFloat(), tw / outW.toFloat(), th / outH.toFloat())
                val b = Geometry.sourceBounds(frame, tu[0], tu[1], tu[0] + tu[2], tu[1] + tu[3])
                val sx0 = ((b[0] * full.width).toInt() - margin).coerceIn(0, full.width - 1)
                val sy0 = ((b[1] * full.height).toInt() - margin).coerceIn(0, full.height - 1)
                val sx1 = (ceil(b[2] * full.width).toInt() + margin).coerceIn(sx0 + 1, full.width)
                val sy1 = (ceil(b[3] * full.height).toInt() + margin).coerceIn(sy0 + 1, full.height)
                val region = Bitmap.createBitmap(full, sx0, sy0, sx1 - sx0, sy1 - sy0)
                val tex = g.linearTexture(region)
                val rect = floatArrayOf(sx0 / full.width.toFloat(), sy0 / full.height.toFloat(), sx1 / full.width.toFloat(), sy1 / full.height.toFloat())
                g.develop(u, global, tex, region.width, rect, frame, tu, outW, outH, target, tw, th)
                if (region !== full) region.recycle()
                g.read(target, tile, tile, tileBmp)
                g.deleteTexture(tex)
                canvas.drawBitmap(tileBmp, android.graphics.Rect(0, 0, tw, th), android.graphics.Rect(x0, y0, x0 + tw, y0 + th), paint)
                progress?.onProgress((r * cols + c + 1f) / (rows * cols))
            }
            g.deleteTexture(target)
            g.release(global)
            tileBmp.recycle()
        } finally {
            g.release()
        }
    }

    /** Encodes [bitmap] with metadata copied from the source per [options]. */
    fun encode(bitmap: Bitmap, info: SourceLoader.Info, stack: EditStack?, options: ExportOptions, history: String): ByteArray {
        val tmp = File.createTempFile("export", ".${options.format.ext}", context.cacheDir)
        try {
            when (options.format) {
                ExportFormat.JPEG -> tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, options.quality.coerceIn(50, 100), it) }
                ExportFormat.PNG -> tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                ExportFormat.AVIF -> {
                    val f = ExportFormat.avifFormat() ?: error("AVIF is not available")
                    tmp.outputStream().use { bitmap.compress(f, options.quality, it) }
                }
                ExportFormat.HEIF -> HeifEncoder.write(bitmap, options.quality, tmp)
            }
            if (options.format == ExportFormat.JPEG || options.format == ExportFormat.PNG) {
                runCatching { writeExif(info, tmp, options.metadata, bitmap.width, bitmap.height) }
                    .onFailure { Log.w(TAG, "EXIF not written", it) }
            }
            var bytes = tmp.readBytes()
            if (options.format == ExportFormat.JPEG) {
                bytes = XmpWriter.insert(bytes, if (options.embedStack && options.metadata != MetadataPolicy.STRIP_ALL) stack else null,
                    CREATOR_TOOL, history)
            }
            return bytes
        } finally {
            tmp.delete()
        }
    }

    private fun writeExif(info: SourceLoader.Info, file: File, policy: MetadataPolicy, w: Int, h: Int) {
        val dst = ExifInterface(file)
        if (policy != MetadataPolicy.STRIP_ALL) {
            val src = context.contentResolver.openInputStream(info.uri)?.use { runCatching { ExifInterface(it) }.getOrNull() }
            if (src != null) for (tag in COPY_TAGS) src.getAttribute(tag)?.let { dst.setAttribute(tag, it) }
            if (policy == MetadataPolicy.KEEP_ALL) {
                // MediaStore hides the location from apps without ACCESS_MEDIA_LOCATION (it reads as 0,0);
                // only the original, unredacted file may provide GPS. Never write the redacted zeros.
                originalExif(info.uri)?.let { o -> for (tag in GPS_TAGS) o.getAttribute(tag)?.let { dst.setAttribute(tag, it) } }
            }
            dst.setAttribute(ExifInterface.TAG_SOFTWARE, CREATOR_TOOL)
        }
        for (tag in GPS_TAGS) if (policy != MetadataPolicy.KEEP_ALL) dst.setAttribute(tag, null)
        dst.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        dst.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, w.toString())
        dst.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, h.toString())
        dst.saveAttributes()
    }

    private fun originalExif(uri: Uri): ExifInterface? {
        val target = if (uri.authority == MediaStore.AUTHORITY && Build.VERSION.SDK_INT >= 29) {
            if (context.checkSelfPermission(android.Manifest.permission.ACCESS_MEDIA_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return null
            }
            runCatching { MediaStore.setRequireOriginal(uri) }.getOrNull() ?: return null
        } else uri
        return runCatching { context.contentResolver.openInputStream(target)?.use { ExifInterface(it) } }
            .onFailure { Log.w(TAG, "Original EXIF unavailable for $uri", it) }.getOrNull()
    }

    /** Saves as a new MediaStore item in Pictures/OpenCamera/Edited, pending until complete. */
    fun saveToMediaStore(bytes: ByteArray, displayName: String, format: ExportFormat, dateTaken: Long?): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, format.mime)
            dateTaken?.let { put(MediaStore.Images.Media.DATE_TAKEN, it) }
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/OpenCamera/Edited")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "OpenCamera/Edited").apply { mkdirs() }
                @Suppress("DEPRECATION")
                put(MediaStore.MediaColumns.DATA, File(dir, "$displayName.${format.ext}").absolutePath)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("MediaStore insert failed")
        try {
            resolver.openOutputStream(uri, "w")!!.use { it.write(bytes) }
            if (Build.VERSION.SDK_INT >= 29) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        return uri
    }

    /** One call: render, encode and save (or write to [shareFile] for sharing). */
    fun export(
        info: SourceLoader.Info,
        stack: EditStack,
        options: ExportOptions,
        look: LookParams?,
        aiMasks: (AiShape) -> Triple<FloatArray, Int, Int>?,
        progress: Progress?,
        shareFile: File? = null,
        cancelled: () -> Boolean = { false },
    ): Result {
        val t0 = SystemClock.elapsedRealtime()
        val notes = ArrayList<String>()
        if (info.hasGainmap) notes += "ultra_hdr_dropped"
        val bmp = render(info, stack, options, look, aiMasks, progress, notes, cancelled)
        val t1 = SystemClock.elapsedRealtime()
        val bytes = encode(bmp, info, stack, options, historyOf(stack))
        val w = bmp.width; val h = bmp.height
        bmp.recycle()
        val base = info.displayName.substringBeforeLast('.') + "_edit"
        val result = if (shareFile != null) {
            shareFile.writeBytes(bytes)
            Result(null, shareFile, w, h, SystemClock.elapsedRealtime() - t0, notes)
        } else {
            val uri = saveToMediaStore(bytes, base, options.format, null)
            Result(uri, null, w, h, SystemClock.elapsedRealtime() - t0, notes)
        }
        Log.i(TAG, "Export ${w}x$h ${options.format} render=${t1 - t0} ms total=${result.ms} ms notes=$notes")
        return result
    }

    companion object {
        private const val TAG = "DevelopExporter"
        const val CREATOR_TOOL = "Open Camera"
        private const val TILE = 2048
        const val MAX_PIXELS = 50_000_000L

        fun historyOf(stack: EditStack): String {
            val parts = ArrayList<String>()
            if (stack.values.isNotEmpty()) parts += "adjusted ${stack.values.keys.map { it.substringBefore('.') }.distinct().joinToString(", ")}"
            if (stack.curves.isNotEmpty()) parts += "tone curve"
            stack.lookId?.let { parts += "look $it" }
            if (stack.masks.isNotEmpty()) parts += "${stack.masks.size} local masks"
            if (stack.tools.isNotEmpty()) parts += "retouch: ${stack.tools.joinToString { it.toolId }}"
            return parts.joinToString("; ").ifEmpty { "no changes" }
        }

        private val GPS_TAGS = listOf(
            ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP, ExifInterface.TAG_GPS_PROCESSING_METHOD,
            ExifInterface.TAG_GPS_SPEED, ExifInterface.TAG_GPS_SPEED_REF, ExifInterface.TAG_GPS_IMG_DIRECTION,
            ExifInterface.TAG_GPS_IMG_DIRECTION_REF, ExifInterface.TAG_GPS_DOP, ExifInterface.TAG_GPS_MAP_DATUM,
            ExifInterface.TAG_GPS_VERSION_ID, ExifInterface.TAG_GPS_H_POSITIONING_ERROR,
        )

        private val COPY_TAGS = listOf(
            ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
            ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            ExifInterface.TAG_EXPOSURE_BIAS_VALUE, ExifInterface.TAG_EXPOSURE_PROGRAM, ExifInterface.TAG_METERING_MODE,
            ExifInterface.TAG_FLASH, ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL,
            ExifInterface.TAG_APERTURE_VALUE, ExifInterface.TAG_SHUTTER_SPEED_VALUE, ExifInterface.TAG_ARTIST,
            ExifInterface.TAG_COPYRIGHT, ExifInterface.TAG_IMAGE_DESCRIPTION, ExifInterface.TAG_COLOR_SPACE,
        )
    }
}

/** Light output sharpening (unsharp mask on luminance, 1 px), for screen or print. */
object OutputSharpen {
    fun apply(bmp: Bitmap, amount: Float) {
        val w = bmp.width; val h = bmp.height
        var prev = IntArray(w); var cur = IntArray(w); var next = IntArray(w)
        val out = IntArray(w)
        bmp.getPixels(cur, 0, w, 0, 0, w, 1)
        cur.copyInto(prev)
        for (y in 0 until h) {
            bmp.getPixels(next, 0, w, 0, min(h - 1, y + 1), w, 1)
            for (x in 0 until w) {
                val c = cur[x]
                val sum = luma(prev[x]) + luma(next[x]) + luma(cur[max(0, x - 1)]) + luma(cur[min(w - 1, x + 1)])
                val d = ((luma(c) * 4 - sum) * amount / 4f).toInt()
                out[x] = (c and 0xff000000.toInt()) or (clamp(((c shr 16) and 255) + d) shl 16) or
                    (clamp(((c shr 8) and 255) + d) shl 8) or clamp((c and 255) + d)
            }
            bmp.setPixels(out, 0, w, 0, y, w, 1)
            val t = prev; prev = cur; cur = next; next = t
        }
    }

    private fun luma(p: Int) = (54 * ((p shr 16) and 255) + 183 * ((p shr 8) and 255) + 19 * (p and 255)) shr 8
    private fun clamp(v: Int) = v.coerceIn(0, 255)
}