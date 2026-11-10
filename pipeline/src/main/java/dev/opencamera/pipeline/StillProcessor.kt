/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import androidx.exifinterface.media.ExifInterface
import dev.opencamera.pipeline.looks.LookParams
import dev.opencamera.pipeline.looks.NativeLookRenderer
import dev.opencamera.pipeline.watermark.ExifFields
import dev.opencamera.pipeline.watermark.WatermarkRenderer
import dev.opencamera.pipeline.watermark.WatermarkTemplate
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Renders a captured JPEG at full resolution: look, then frame, then JPEG encode
 * with the source EXIF carried over. Peak memory is the decoded bitmap (plus the
 * framed copy when a frame is drawn); the look is applied in place in strips.
 */
class StillProcessor(private val resolver: ContentResolver) {
    data class Timings(val decodeMs: Long, val lookMs: Long, val frameMs: Long, val encodeMs: Long, val width: Int, val height: Int) {
        val totalMs get() = decodeMs + lookMs + frameMs + encodeMs
    }

    fun process(
        source: Uri,
        look: LookParams?,
        template: WatermarkTemplate,
        fallbackModel: String?,
        output: File,
    ): Timings {
        val exif = resolver.openInputStream(source)?.use { ExifInterface(it) }
            ?: throw IOException("Cannot open $source")
        val t0 = SystemClock.elapsedRealtime()
        val bitmap = resolver.openInputStream(source)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                inMutable = true
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })
        } ?: throw IOException("Cannot decode $source")
        return processBitmap(bitmap, exif, look, template, fallbackModel, output, t0)
    }

    /** Same as [process] for an already decoded mutable ARGB_8888 bitmap. Recycles [bitmap]. */
    fun processBitmap(
        bitmap: Bitmap,
        exif: ExifInterface?,
        look: LookParams?,
        template: WatermarkTemplate,
        fallbackModel: String?,
        output: File,
        decodeStart: Long = SystemClock.elapsedRealtime(),
    ): Timings {
        val t1 = SystemClock.elapsedRealtime()
        if (look != null) NativeLookRenderer.apply(bitmap, look)
        val t2 = SystemClock.elapsedRealtime()
        val framed = WatermarkRenderer.render(
            bitmap, exif?.rotationDegrees ?: 0, template, exif.fields(fallbackModel),
        )
        if (framed !== bitmap) bitmap.recycle()
        val t3 = SystemClock.elapsedRealtime()
        val width = framed.width
        val height = framed.height
        try {
            FileOutputStream(output).use { framed.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        } finally {
            framed.recycle()
        }
        exif?.let { copyExif(it, output, width, height) }
        val t4 = SystemClock.elapsedRealtime()
        return Timings(t1 - decodeStart, t2 - t1, t3 - t2, t4 - t3, width, height)
    }

    companion object {
        const val JPEG_QUALITY = 95

        private val SKIPPED_TAGS = setOf(
            ExifInterface.TAG_PIXEL_X_DIMENSION, ExifInterface.TAG_PIXEL_Y_DIMENSION,
            ExifInterface.TAG_IMAGE_WIDTH, ExifInterface.TAG_IMAGE_LENGTH,
            ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT, ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT_LENGTH,
            ExifInterface.TAG_THUMBNAIL_IMAGE_WIDTH, ExifInterface.TAG_THUMBNAIL_IMAGE_LENGTH,
            "ThumbnailOrientation", ExifInterface.TAG_STRIP_OFFSETS,
            ExifInterface.TAG_STRIP_BYTE_COUNTS, ExifInterface.TAG_COMPRESSION,
        )

        private val COPIED_TAGS: List<String> by lazy {
            ExifInterface::class.java.fields
                .filter { it.name.startsWith("TAG_") && it.type == String::class.java }
                .mapNotNull { it.get(null) as? String }
                .distinct()
                .filterNot { it in SKIPPED_TAGS }
        }

        fun copyExif(source: ExifInterface, target: File, width: Int, height: Int) {
            val out = ExifInterface(target)
            for (tag in COPIED_TAGS) {
                source.getAttribute(tag)?.let { out.setAttribute(tag, it) }
            }
            out.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, width.toString())
            out.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, height.toString())
            out.saveAttributes()
        }

        fun ExifInterface?.fields(fallbackModel: String?): ExifFields {
            if (this == null) return ExifFields(model = fallbackModel)
            val exposure = getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)
            return ExifFields(
                model = getAttribute(ExifInterface.TAG_MODEL)?.takeIf { it.isNotBlank() } ?: fallbackModel,
                focalLengthMm = getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0).toFloat().takeIf { it > 0f },
                fNumber = getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0).toFloat().takeIf { it > 0f },
                exposureSeconds = exposure.takeIf { it > 0.0 },
                iso = getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0).takeIf { it > 0 },
                dateTime = (getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: getAttribute(ExifInterface.TAG_DATETIME))?.let(::formatExifDate),
            )
        }

        /** "2026:11:11 09:30:12" -> "2026-11-11 09:30". */
        fun formatExifDate(raw: String): String {
            val m = Regex("""(\d{4}):(\d{2}):(\d{2}) (\d{2}):(\d{2})""").find(raw) ?: return raw.trim()
            val (y, mo, d, h, mi) = m.destructured
            return "$y-$mo-$d $h:$mi"
        }
    }
}
