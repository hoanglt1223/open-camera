/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.video

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saves mode output next to the main camera's media (DCIM/Camera, Documents for PDFs). */
object MediaSaver {
    private val CAMERA_DIR = "${Environment.DIRECTORY_DCIM}/Camera"
    private val DOCS_DIR = "${Environment.DIRECTORY_DOCUMENTS}/OpenCamera"

    fun name(prefix: String) =
        prefix + "_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

    /** Values for a recording named [displayName] (from [name]). */
    fun outputOptionsValues(displayName: String) = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
        put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.MediaColumns.RELATIVE_PATH, CAMERA_DIR)
        }
    }

    fun saveVideo(context: Context, file: File, prefix: String): Uri? =
        save(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, CAMERA_DIR, "${name(prefix)}.mp4", "video/mp4") {
            file.inputStream().use { input -> input.copyTo(it) }
        }

    fun saveJpeg(context: Context, bitmap: Bitmap, prefix: String): Uri? =
        save(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, CAMERA_DIR, "${name(prefix)}.jpg", "image/jpeg") {
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) { "JPEG encode failed" }
        }

    fun savePdf(context: Context, prefix: String, write: (OutputStream) -> Unit): Uri? =
        saveDocument(context, "${name(prefix)}.pdf", "application/pdf", write)

    /** Non-media files (PDF, `.cube`) go to Documents: MediaStore keeps DCIM for photos and videos. */
    fun saveDocument(context: Context, displayName: String, mime: String, write: (OutputStream) -> Unit): Uri? {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Files.getContentUri("external")
        }
        return save(context, collection, DOCS_DIR, displayName, mime, write)
    }

    private fun save(
        context: Context,
        collection: Uri,
        relativeDir: String,
        displayName: String,
        mime: String,
        write: (OutputStream) -> Unit,
    ): Uri? {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val dir = File(Environment.getExternalStorageDirectory(), relativeDir).apply { mkdirs() }
            val file = File(dir, displayName)
            file.outputStream().use(write)
            MediaScannerConnection.scanFile(context, arrayOf(file.path), arrayOf(mime), null)
            return Uri.fromFile(file)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: return null
        try {
            resolver.openOutputStream(uri)?.use(write) ?: error("no output stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
}
