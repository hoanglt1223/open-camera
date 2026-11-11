/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.ai

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.view.CameraController
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import dev.opencamera.ml.MlRuntime
import dev.opencamera.ml.tasks.AestheticScorer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Burst of in-memory JPEG captures; only the frame with the best aesthetic score (NIMA when
 * downloaded, sharpness + exposure otherwise) is saved. Returns null when nothing could be
 * captured, so the caller takes a normal photo instead.
 */
class BestShotCapture(private val context: Context) {
    data class Result(val uri: Uri, val chosen: Int, val frames: Int, val byModel: Boolean, val scoreMs: Long)

    private val scorer = AestheticScorer(MlRuntime.get(context))

    suspend fun capture(controller: CameraController, count: Int): Result? {
        val shots = ArrayList<Pair<ByteArray, Int>>()
        for (i in 0 until count) {
            val shot = runCatching { takeOne(controller) }
                .onFailure { Log.w(TAG, "Burst frame $i failed", it) }
                .getOrNull() ?: break
            shots += shot
        }
        if (shots.isEmpty()) return null
        return withContext(Dispatchers.Default) {
            val t0 = SystemClock.elapsedRealtime()
            val previews = shots.map { (jpeg, _) ->
                BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = 8 })
            }
            val valid = previews.withIndex().filter { it.value != null }
            val best = if (valid.isEmpty()) 0 else valid[scorer.best(valid.map { it.value!! })].index
            val byModel = scorer.modelAvailable
            previews.forEach { it?.recycle() }
            val scoreMs = SystemClock.elapsedRealtime() - t0
            val (jpeg, rotation) = shots[best]
            val uri = save(jpeg, rotation) ?: return@withContext null
            Log.i(TAG, "Best shot $best of ${shots.size} (model=$byModel) scored in $scoreMs ms")
            Result(uri, best, shots.size, byModel, scoreMs)
        }
    }

    private suspend fun takeOne(controller: CameraController): Pair<ByteArray, Int> =
        suspendCancellableCoroutine { cont ->
            controller.takePicture(
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try {
                            require(image.format == ImageFormat.JPEG) { "Not a JPEG frame: ${image.format}" }
                            val buf = image.planes[0].buffer
                            val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                            cont.resume(bytes to image.imageInfo.rotationDegrees)
                        } catch (e: Exception) {
                            cont.resumeWithException(e)
                        } finally {
                            image.close()
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        cont.resumeWithException(exception)
                    }
                },
            )
        }

    private fun save(jpeg: ByteArray, rotation: Int): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US).format(System.currentTimeMillis()))
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Camera")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri, "w")!!.use { it.write(jpeg) }
            resolver.openFileDescriptor(uri, "rw")?.use { fd ->
                ExifInterface(fd.fileDescriptor).apply {
                    setAttribute(ExifInterface.TAG_ORIENTATION, orientation(rotation).toString())
                    saveAttributes()
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
            uri
        } catch (e: Exception) {
            Log.e(TAG, "Cannot save best shot", e)
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    private fun orientation(rotation: Int) = when (((rotation % 360) + 360) % 360) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }

    private companion object {
        const val TAG = "BestShotCapture"
    }
}
