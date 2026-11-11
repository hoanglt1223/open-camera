/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.video

import android.content.Context
import android.graphics.Matrix
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.SingleColorLut
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import dev.opencamera.capture.video.FrameCorrection
import dev.opencamera.capture.video.GyroPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.max

/**
 * Maps a recording's presentation time to the elapsedRealtime clock, skipping paused spans so
 * the gyro samples line up with the frames after a pause.
 */
class RecordingTimeline(private val startNs: Long) {
    private val pauses = mutableListOf<LongArray>()
    private var pausedAt = -1L

    fun onPause(nowNs: Long) { pausedAt = nowNs }
    fun onResume(nowNs: Long) {
        if (pausedAt >= 0) pauses += longArrayOf(pausedAt, nowNs - pausedAt)
        pausedAt = -1L
    }

    fun toElapsedNs(ptsUs: Long): Long {
        var t = startNs + ptsUs * 1000
        for (p in pauses) if (t >= p[0]) t += p[1]
        return t
    }
}

/** Software EIS in post: per-frame counter-rotation from the gyro path plus a 10% crop. */
@OptIn(UnstableApi::class)
class GyroEisTransformation(
    private val path: GyroPath,
    private val timeline: RecordingTimeline,
    private val focalOverSensor: Float,
    private val frameRotationDeg: Int,
    private val mirrored: Boolean,
) : MatrixTransformation {
    private var width = 1
    private var height = 1

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        width = inputWidth
        height = inputHeight
        return Size(inputWidth, inputHeight)
    }

    override fun getMatrix(presentationTimeUs: Long): Matrix {
        val longSide = max(width, height)
        val c = FrameCorrection.from(
            path.correctionAt(timeline.toElapsedNs(presentationTimeUs), HALF_WINDOW_NS),
            focalOverSensor * longSide, frameRotationDeg, mirrored, frameLongSidePx = longSide,
        )
        return ndcMatrix(c, width, height)
    }

    companion object {
        private const val HALF_WINDOW_NS = 400_000_000L

        /** The correction in normalized device coordinates (y up), zoomed by the crop margin. */
        fun ndcMatrix(c: FrameCorrection, width: Int, height: Int): Matrix {
            val aspect = width.toFloat() / height
            return Matrix().apply {
                postScale(aspect, 1f)
                postRotate(-c.rollDeg)
                postScale(1f / aspect, 1f)
                postTranslate(2f * c.dxPx / width, -2f * c.dyPx / height)
                postScale(FrameCorrection.CROP_SCALE, FrameCorrection.CROP_SCALE)
            }
        }
    }
}

/** Media3 Transformer jobs: grade (log + look LUT), gyro EIS and short-video music. */
@OptIn(UnstableApi::class)
object VideoPostProcessor {
    class Job(
        val lutCube: Array<Array<IntArray>>? = null,
        val eis: GyroEisTransformation? = null,
        val music: Uri? = null,
        val keepOriginalAudio: Boolean = true,
    ) {
        val isEmpty get() = lutCube == null && eis == null && music == null
    }

    /** Runs [job] on [input] into [output]. Returns the error message, or null on success. */
    suspend fun run(context: Context, input: Uri, output: File, job: Job): String? =
        withContext(Dispatchers.Main) {
            val effects = buildList<Effect> {
                job.eis?.let { add(it) }
                job.lutCube?.let { add(SingleColorLut.createFromCube(it)) }
            }
            val video = EditedMediaItem.Builder(MediaItem.fromUri(input))
                .setRemoveAudio(job.music != null && !job.keepOriginalAudio)
                .setEffects(Effects(emptyList(), effects))
                .build()
            val sequences = mutableListOf(EditedMediaItemSequence.Builder(video).build())
            job.music?.let {
                val audio = EditedMediaItem.Builder(MediaItem.fromUri(it)).setRemoveVideo(true).build()
                sequences += EditedMediaItemSequence.Builder(audio).setIsLooping(true).build()
            }
            val composition = Composition.Builder(sequences).build()
            suspendCancellableCoroutine { cont ->
                val transformer = Transformer.Builder(context)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (cont.isActive) cont.resume(null)
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException,
                        ) {
                            output.delete()
                            if (cont.isActive) cont.resume(exportException.message ?: "export failed")
                        }
                    })
                    .build()
                cont.invokeOnCancellation { runCatching { transformer.cancel() } }
                try {
                    transformer.start(composition, output.path)
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(e.message ?: "export failed")
                }
            }
        }
}
