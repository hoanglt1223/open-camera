/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.develop.io

import android.app.ActivityManager
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import dev.opencamera.app.develop.model.EditStore
import dev.opencamera.app.develop.model.SourceRef
import dev.opencamera.app.develop.raw.DngReader
import dev.opencamera.app.develop.raw.RawDevelop
import dev.opencamera.app.develop.raw.RawFormatException
import dev.opencamera.pipeline.raw.LibRawDecoder
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.roundToInt

class UnsupportedImageException(message: String) : Exception(message)

/**
 * Opens photos for the editor: JPEG/HEIF/PNG/WebP through the platform decoders, DNG through
 * [DngReader] + [RawDevelop], other camera RAW through LibRaw. Output is always upright.
 * RAW sources become linear half-float bitmaps so highlights above white survive into the
 * develop pipeline.
 */
class SourceLoader(private val context: Context) {
    enum class Kind { RASTER, DNG, CAMERA_RAW }

    class Info(
        val uri: Uri,
        val kind: Kind,
        val mime: String?,
        val displayName: String,
        val width: Int,
        val height: Int,
        val wide: Boolean,
        val hasGainmap: Boolean,
        val ref: SourceRef,
        val notes: List<String>,
    )

    private val resolver = context.contentResolver

    /** Proxy long side by device memory, 2048..3072. */
    fun proxyLongSide(): Int {
        val am = context.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem
        return when {
            mem < 4L shl 30 -> 2048
            mem < 7L shl 30 -> 2560
            else -> 3072
        }
    }

    /** [hash] = false skips the SHA-256 (fill it in later with [withHash]) so decoding can start sooner. */
    fun info(uri: Uri, hash: Boolean = true): Info {
        val name = queryName(uri) ?: uri.lastPathSegment ?: "photo"
        val mime = runCatching { resolver.getType(uri) }.getOrNull()
        val head = ByteArray(16)
        val n = resolver.openInputStream(uri)?.use { it.read(head) } ?: throw FileNotFoundException("Cannot open $uri")
        if (n < 4) throw UnsupportedImageException("Empty file")
        val ref = SourceRef(mediaId(uri), if (hash) hashOf(uri) else "", name)
        val kind = kindOf(head, name, mime)
        val notes = ArrayList<String>()
        var w = 0; var h = 0; var wide = false; var gain = false
        when (kind) {
            Kind.RASTER -> {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
                if (o.outWidth <= 0) throw UnsupportedImageException("Unsupported image format")
                val rot = rotation(uri)
                w = if (rot % 180 == 0) o.outWidth else o.outHeight
                h = if (rot % 180 == 0) o.outHeight else o.outWidth
                wide = o.outColorSpace?.let { it != ColorSpace.get(ColorSpace.Named.SRGB) && it.isWideGamut } ?: false
            }
            else -> Unit
        }
        if (Build.VERSION.SDK_INT >= 34 && kind == Kind.RASTER) {
            gain = runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { d, _, _ ->
                    d.setTargetSize(64, 64); d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }.hasGainmap()
            }.getOrDefault(false)
            if (gain) notes += "ultra_hdr"
        }
        return Info(uri, kind, mime, name, w, h, wide, gain, ref, notes)
    }

    fun hashOf(uri: Uri): String = resolver.openInputStream(uri)?.let { EditStore.hash(it) } ?: throw FileNotFoundException("Cannot read $uri")

    fun withHash(i: Info): Info = if (i.ref.hash.isNotEmpty()) i else Info(i.uri, i.kind, i.mime, i.displayName, i.width, i.height,
        i.wide, i.hasGainmap, i.ref.copy(hash = hashOf(i.uri)), i.notes)

    fun kindOf(head: ByteArray, name: String, mime: String?): Kind {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            DngReader.isDng(head) && (ext == "dng" || mime == "image/x-adobe-dng" || mime == "image/dng") -> Kind.DNG
            ext in CAMERA_RAW_EXT -> Kind.CAMERA_RAW
            DngReader.isDng(head) && ext !in setOf("tif", "tiff") -> Kind.DNG
            else -> Kind.RASTER
        }
    }

    /** Upright proxy with long side near [longSide]; RAW sources are linear RGBA_F16. */
    fun loadProxy(info: Info, longSide: Int): Bitmap = when (info.kind) {
        Kind.RASTER -> decodeRaster(info.uri, longSide)
        Kind.DNG -> scaleTo(developDng(info.uri, half = true), longSide)
        Kind.CAMERA_RAW -> scaleTo(developCameraRaw(info.uri, half = true), longSide)
    }

    /** Upright full-resolution image for export, capped at [maxPixels]. */
    fun loadFull(info: Info, maxPixels: Long): Bitmap {
        // A RAW with four times the cap is developed at half size: the result is downscaled to
        // the cap anyway, and the full-size buffers would quadruple the peak.
        val halfRaw = info.width.toLong() * info.height >= 4 * maxPixels
        val bmp = when (info.kind) {
            Kind.RASTER -> decodeRaster(info.uri, 0, maxPixels)
            Kind.DNG -> developDng(info.uri, half = halfRaw)
            Kind.CAMERA_RAW -> developCameraRaw(info.uri, half = halfRaw)
        }
        val px = bmp.width.toLong() * bmp.height
        if (px <= maxPixels) return bmp
        val s = kotlin.math.sqrt(maxPixels.toDouble() / px)
        return Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true).also {
            if (it !== bmp) bmp.recycle()
        }
    }

    private fun decodeRaster(uri: Uri, longSide: Int, maxPixels: Long = Long.MAX_VALUE): Bitmap {
        if (Build.VERSION.SDK_INT >= 28) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { d, i, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                d.isMutableRequired = false
                val w = i.size.width; val h = i.size.height
                var s = if (longSide > 0) minOf(1f, longSide.toFloat() / max(w, h)) else 1f
                val px = w.toLong() * h * s * s
                if (px > maxPixels) s *= kotlin.math.sqrt(maxPixels / px).toFloat()
                if (s < 1f) d.setTargetSize(max(1, (w * s).roundToInt()), max(1, (h * s).roundToInt()))
            }.let {
                if (it.config != Bitmap.Config.ARGB_8888 && it.config != Bitmap.Config.RGBA_F16) {
                    it.copy(Bitmap.Config.ARGB_8888, false).also { c -> if (c !== it) it.recycle() }
                } else it
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        if (longSide > 0) while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= longSide) sample *= 2
        while (bounds.outWidth.toLong() * bounds.outHeight / (sample.toLong() * sample) > maxPixels) sample *= 2
        val bmp = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw UnsupportedImageException("Cannot decode image")
        val rot = rotation(uri)
        val upright = if (rot == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
            .also { if (it !== bmp) bmp.recycle() }
        return if (longSide > 0) scaleTo(upright, longSide) else upright
    }

    private fun developDng(uri: Uri, half: Boolean): Bitmap {
        val t0 = android.os.SystemClock.elapsedRealtime()
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: throw FileNotFoundException("Cannot read $uri")
        val t1 = android.os.SystemClock.elapsedRealtime()
        val img = try {
            DngReader.read(bytes)
        } catch (e: RawFormatException) {
            // Our reader covers the common subset; LibRaw reads the rest when installed.
            Log.w(TAG, "DNG subset reader: ${e.message}; trying LibRaw")
            if (LibRawDecoder.available) return developCameraRaw(uri, half)
            throw UnsupportedImageException("DNG not supported: ${e.message}")
        }
        val t2 = android.os.SystemClock.elapsedRealtime()
        val lin = RawDevelop.develop(img, half, toHalf = true)
        val t3 = android.os.SystemClock.elapsedRealtime()
        val out = orient(halfBitmap(lin.width, lin.height, lin.half!!), exifOrientationToDegrees(img.orientation), img.orientation)
        Log.i(TAG, "DNG ${img.width}x${img.height} read ${t1 - t0} parse ${t2 - t1} develop ${t3 - t2} bitmap ${android.os.SystemClock.elapsedRealtime() - t3} ms (half=$half, gainmaps=${img.gainMaps.size}, ${DngReader.lastLayout})")
        return out
    }

    private fun developCameraRaw(uri: Uri, half: Boolean): Bitmap {
        if (!LibRawDecoder.available) throw UnsupportedImageException("Camera RAW support is not installed")
        val pfd: ParcelFileDescriptor = resolver.openFileDescriptor(uri, "r") ?: throw FileNotFoundException("Cannot open $uri")
        val d = pfd.use { LibRawDecoder.decode("/proc/self/fd/${it.fd}", half) }
        // LibRaw flip: 3 = 180, 5 = 90 CCW, 6 = 90 CW. Rotated while converting, so no second
        // full-size bitmap is made for the upright copy.
        val deg = when (d.flip) { 3 -> 180; 5 -> 270; 6 -> 90; else -> 0 }
        val rgb16 = d.rgb16
        return linearBitmap(d.width, d.height, deg) { i -> (rgb16[i].toInt() and 0xFFFF) / 65535f }
    }

    /**
     * Linear extended-sRGB half-float bitmap from interleaved RGB (converted in parallel, off the
     * Java heap), rotated clockwise by [degrees] (0, 90, 180, 270).
     */
    private fun linearBitmap(w: Int, h: Int, degrees: Int, rgb: (Int) -> Float): Bitmap {
        val buf = ByteBuffer.allocateDirect(w * h * 8).order(ByteOrder.nativeOrder())
        val shorts = buf.asShortBuffer()
        val one = RawDevelop.toHalf(1f)
        val ow = if (degrees == 90 || degrees == 270) h else w
        val oh = if (degrees == 90 || degrees == 270) w else h
        (0 until h).chunked(maxOf(1, h / 32)).parallelStream().forEach { band ->
            for (y in band) for (x in 0 until w) {
                val i = y * w + x
                val o = when (degrees) {
                    90 -> x * ow + (h - 1 - y)
                    180 -> (h - 1 - y) * ow + (w - 1 - x)
                    270 -> (w - 1 - x) * ow + y
                    else -> i
                }
                shorts.put(o * 4, RawDevelop.toHalf(rgb(i * 3)))
                shorts.put(o * 4 + 1, RawDevelop.toHalf(rgb(i * 3 + 1)))
                shorts.put(o * 4 + 2, RawDevelop.toHalf(rgb(i * 3 + 2)))
                shorts.put(o * 4 + 3, one)
            }
        }
        return halfBitmap(ow, oh, buf)
    }

    private fun halfBitmap(w: Int, h: Int, buf: ByteBuffer): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGBA_F16, false, ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB))
        buf.position(0)
        bmp.copyPixelsFromBuffer(buf)
        return bmp
    }

    private fun orient(bmp: Bitmap, degrees: Int, exifOrientation: Int): Bitmap {
        val m = Matrix()
        when (exifOrientation) {
            2 -> m.postScale(-1f, 1f)
            4 -> m.postScale(1f, -1f)
            5 -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            7 -> { m.postRotate(-90f); m.postScale(-1f, 1f) }
            else -> if (degrees != 0) m.postRotate(degrees.toFloat())
        }
        if (m.isIdentity) return bmp
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true).also { if (it !== bmp) bmp.recycle() }
    }

    private fun exifOrientationToDegrees(o: Int) = when (o) { 3 -> 180; 6 -> 90; 8 -> 270; else -> 0 }

    private fun scaleTo(bmp: Bitmap, longSide: Int): Bitmap {
        val s = longSide.toFloat() / max(bmp.width, bmp.height)
        if (s >= 1f) return bmp
        return Bitmap.createScaledBitmap(bmp, max(1, (bmp.width * s).roundToInt()), max(1, (bmp.height * s).roundToInt()), true)
            .also { if (it !== bmp) bmp.recycle() }
    }

    fun rotation(uri: Uri): Int = runCatching {
        resolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
    }.getOrDefault(0)

    fun mediaId(uri: Uri): Long {
        if (uri.authority != MediaStore.AUTHORITY) return -1
        return runCatching { ContentUris.parseId(uri) }.getOrDefault(-1L)
    }

    private fun queryName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    companion object {
        private const val TAG = "SourceLoader"
        val CAMERA_RAW_EXT = setOf(
            "cr2", "cr3", "crw", "nef", "nrw", "arw", "srf", "sr2", "raf", "orf", "rw2", "rwl", "pef", "srw",
            "x3f", "3fr", "fff", "iiq", "mos", "mef", "mrw", "kdc", "dcr", "erf", "raw",
        )
    }
}
