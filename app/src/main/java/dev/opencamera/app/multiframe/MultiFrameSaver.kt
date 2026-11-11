/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.multiframe

import android.content.ContentValues
import android.content.Context
import android.hardware.camera2.CaptureResult
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/** Writes a merged JPEG with EXIF into MediaStore (DCIM/Camera). */
object MultiFrameSaver {
    private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
    private val STORAGE_DESTINATION = "${Environment.DIRECTORY_DCIM}/Camera"

    class Metadata(
        val timestamp: Long,
        val rotationDegrees: Int,
        val captureResult: CaptureResult?,
        val location: Location?,
        val description: String,
    )

    fun save(context: Context, jpeg: ByteArray, width: Int, height: Int, meta: Metadata): Uri? {
        val temp = File.createTempFile("merged", ".jpg", context.cacheDir)
        try {
            temp.writeBytes(jpeg)
            writeExif(temp, width, height, meta)

            val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(meta.timestamp)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.DATE_TAKEN, meta.timestamp)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, STORAGE_DESTINATION)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return null
            try {
                resolver.openOutputStream(uri)?.use { out -> temp.inputStream().use { it.copyTo(out) } }
                    ?: error("Cannot open $uri")
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(
                    uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null, null
                )
            }
            return uri
        } finally {
            temp.delete()
        }
    }

    private fun writeExif(file: File, width: Int, height: Int, meta: Metadata) {
        val exif = ExifInterface(file)
        val stamp = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(meta.timestamp)
        exif.setAttribute(ExifInterface.TAG_DATETIME, stamp)
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, stamp)
        exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, stamp)
        exif.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
        exif.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
        exif.setAttribute(ExifInterface.TAG_SOFTWARE, "Open Camera")
        exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, meta.description)
        exif.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, width.toString())
        exif.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, height.toString())
        exif.setAttribute(
            ExifInterface.TAG_ORIENTATION,
            when ((meta.rotationDegrees % 360 + 360) % 360) {
                90 -> ExifInterface.ORIENTATION_ROTATE_90
                180 -> ExifInterface.ORIENTATION_ROTATE_180
                270 -> ExifInterface.ORIENTATION_ROTATE_270
                else -> ExifInterface.ORIENTATION_NORMAL
            }.toString()
        )
        meta.captureResult?.let { r ->
            r.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let {
                exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, (it / 1e9).toString())
            }
            r.get(CaptureResult.SENSOR_SENSITIVITY)?.let {
                exif.setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, it.toString())
            }
            r.get(CaptureResult.LENS_APERTURE)?.let {
                exif.setAttribute(ExifInterface.TAG_F_NUMBER, it.toString())
            }
            r.get(CaptureResult.LENS_FOCAL_LENGTH)?.let {
                exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "${(it * 1000).toInt()}/1000")
            }
        }
        meta.location?.let { exif.setGpsInfo(it) }
        exif.saveAttributes()
    }
}
