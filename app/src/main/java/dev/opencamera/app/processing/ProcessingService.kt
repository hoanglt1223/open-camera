/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.processing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.opencamera.app.R
import android.graphics.Bitmap
import dev.opencamera.app.multiframe.BurstRecovery
import dev.opencamera.pipeline.StillProcessor
import dev.opencamera.pipeline.creative.MotionPhotoFormat
import dev.opencamera.pipeline.queue.BurstArchive
import androidx.exifinterface.media.ExifInterface
import dev.opencamera.pipeline.ai.AiOps
import dev.opencamera.pipeline.ai.AiStage
import dev.opencamera.pipeline.retouch.RetouchExif
import dev.opencamera.pipeline.queue.Job
import dev.opencamera.pipeline.queue.JobState
import dev.opencamera.pipeline.queue.ProcessingQueue
import dev.opencamera.pipeline.queue.SqliteJobStore
import dev.opencamera.pipeline.watermark.WatermarkTemplate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException

/**
 * Renders queued photos in the background. Runs as a foreground service only while
 * jobs are pending; the notification goes away as soon as the queue drains.
 *
 * Publishing is crash-safe: the original stays in the gallery until the rendered
 * copy is fully written (as a pending MediaStore item), then the original is
 * removed and the copy takes its name.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class ProcessingService : Service() {
    // Queue decisions run on the main thread so they serialize with onStartCommand;
    // rendering hops to Dispatchers.Default.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var worker: kotlinx.coroutines.Job? = null
    private lateinit var queue: ProcessingQueue

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        queue = queue(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!startInForeground(pendingCount())) {
            // Jobs stay queued and resume on the next launch.
            stopSelf()
            return START_NOT_STICKY
        }
        if (worker?.isActive != true) {
            worker = scope.launch { runQueue() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runQueue() {
        try {
            queue.recover()
            while (true) {
                val job = queue.claimNext()
                if (job == null) {
                    val due = queue.nextDueAt()
                        ?: if (BurstJobQueue.get(this).pending.value > 0) {
                            System.currentTimeMillis() + BURST_POLL_MS
                        } else {
                            break
                        }
                    delay((due - System.currentTimeMillis()).coerceAtLeast(50))
                    continue
                }
                updateNotification(pendingCount())
                runCatching { withContext(Dispatchers.Default) { process(job) } }.onFailure { e ->
                    Log.e(TAG, "Job ${job.id} failed", e)
                    val retryable = e !is FileNotFoundException && e !is IllegalArgumentException
                    queue.markFailed(job.id, e.toString(), retryable)
                }
            }
        } finally {
            worker = null
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun process(job: Job) {
        if (job.sourceUri.startsWith(BurstRecovery.SCHEME)) {
            processBurst(job)
            return
        }
        when (job.state) {
            JobState.RUNNING -> {
                val out = render(job)
                publish(queue.markWritten(job.id, out.toString()))
            }
            JobState.WRITTEN -> publish(job)
            else -> error("Unexpected state ${job.state}")
        }
    }

    private fun render(job: Job): Uri {
        val source = Uri.parse(job.sourceUri)
        val meta = sourceMeta(source) ?: throw FileNotFoundException("Source gone: $source")
        val tmp = File(cacheDir, "processing").apply { mkdirs() }.resolve("job-${job.id}.jpg")
        try {
            val look = LooksRepository.get(this).params(job.lookId, job.intensity)
            val ops = AiOps.fromJson(job.ops)?.takeUnless { it.isEmpty }
            val aiReport = ArrayList<String>()
            var stage: ((Bitmap, Int) -> Bitmap)? = null
            var ai: AiStage? = null
            if (ops != null) {
                if (ops.portraitAperture != null && !ops.keepSource) PortraitOriginals.keep(this, source, meta.name)
                val aiStage = AiStage(this).also { ai = it }
                stage = { bitmap, rotation -> aiStage.apply(bitmap, rotation, ops, aiReport) }
            }
            val t = StillProcessor(contentResolver).process(
                source, look, WatermarkTemplate.fromKey(job.watermark), Build.MODEL, tmp, stage,
            )
            ai?.retouchTags?.takeIf { it.isNotEmpty() }?.let { tags ->
                runCatching {
                    val exif = ExifInterface(tmp)
                    RetouchExif.write(exif, tags, exif.getAttribute(ExifInterface.TAG_SOFTWARE) ?: "Open Camera")
                    exif.saveAttributes()
                }.onFailure { Log.w(TAG, "Retouch tags not written for job ${job.id}", it) }
            }
            carryOverMotionPhoto(source, tmp)
            Log.i(
                TAG, "Job ${job.id} ${t.width}x${t.height} look=${job.lookId} frame=${job.watermark} ai=[$aiReport] " +
                    "decode=${t.decodeMs} look=${t.lookMs} frame=${t.frameMs} encode=${t.encodeMs} " +
                    "total=${t.totalMs} ms"
            )
            val pendingName = "${meta.name}${if (ops?.keepSource == true) EDIT_SUFFIX else ""}$PENDING_SUFFIX${job.id}"
            deleteStalePending(pendingName)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, pendingName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, meta.relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
                meta.dateTaken?.let { put(MediaStore.Images.Media.DATE_TAKEN, it) }
            }
            val out = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore insert failed")
            try {
                contentResolver.openOutputStream(out, "w")!!.use { os -> tmp.inputStream().use { it.copyTo(os) } }
            } catch (e: Exception) {
                contentResolver.delete(out, null, null)
                throw e
            }
            return out
        } finally {
            tmp.delete()
        }
    }

    /**
     * Merges a burst archived by a process that died before its merge finished. The merged
     * photo then gets the look/frame step like any other capture.
     */
    private fun processBurst(job: Job) {
        if (job.state == JobState.WRITTEN) {
            queue.markDone(job.id)
            return
        }
        val dir = File(BurstRecovery.root(this), job.sourceUri.removePrefix(BurstRecovery.SCHEME))
        if (BurstArchive.open(dir) == null) throw FileNotFoundException("Burst archive gone: $dir")
        val uri = BurstRecovery.mergeArchived(this, dir) ?: error("Merge of $dir failed")
        Log.i(TAG, "Recovered burst ${dir.name} as $uri")
        queue.markWritten(job.id, uri.toString())
        queue.markDone(job.id)
        if (job.lookId != null || job.watermark != WatermarkTemplate.NONE.key) {
            enqueue(this, uri, job.lookId, job.intensity, job.watermark)
        }
    }

    /** Keeps the clip of a motion photo on the re-rendered still; on failure the still alone is kept. */
    private fun carryOverMotionPhoto(source: Uri, rendered: File) {
        runCatching {
            val original = contentResolver.openInputStream(source)?.use { it.readBytes() } ?: return
            if (MotionPhotoFormat.videoLength(original) == null) return
            rendered.writeBytes(MotionPhotoFormat.carryOver(original, rendered.readBytes()))
        }.onFailure { Log.w(TAG, "Motion photo clip not carried over for $source", it) }
    }

    /** Idempotent: safe to repeat after a crash at any point. */
    private fun publish(job: Job) {
        val out = Uri.parse(requireNotNull(job.outputUri))
        val outMeta = sourceMeta(out) ?: throw IllegalArgumentException("Rendered copy missing for job ${job.id}")
        val finalName = outMeta.name.substringBefore(PENDING_SUFFIX)
        val source = Uri.parse(job.sourceUri)
        val keepSource = AiOps.fromJson(job.ops)?.keepSource == true
        if (!keepSource && sourceMeta(source) != null) {
            try {
                contentResolver.delete(source, null, null)
            } catch (e: SecurityException) {
                // Not ours any more (e.g. after reinstall): keep both files.
                Log.w(TAG, "Cannot remove original $source", e)
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
            if (keepSource || sourceMeta(source) == null) put(MediaStore.MediaColumns.DISPLAY_NAME, finalName)
        }
        contentResolver.update(out, values, null, null)
        queue.markDone(job.id)
    }

    private data class Meta(val name: String, val relativePath: String, val dateTaken: Long?)

    private fun sourceMeta(uri: Uri): Meta? {
        val projection = arrayOf(
            MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.Images.Media.DATE_TAKEN,
        )
        val args = Bundle().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            }
        }
        return contentResolver.query(uri, projection, args, null)?.use { c ->
            if (!c.moveToFirst()) return null
            Meta(
                name = c.getString(0)?.substringBeforeLast('.') ?: return null,
                relativePath = c.getString(1) ?: "DCIM/Camera/",
                dateTaken = if (c.isNull(2)) null else c.getLong(2),
            )
        }
    }

    /** Removes a half-written copy left by an attempt that died before it was recorded. */
    private fun deleteStalePending(name: String) {
        val args = Bundle().apply {
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?")
            putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("$name.%"))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            }
        }
        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns._ID), args, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0).toString())
                runCatching { contentResolver.delete(uri, null, null) }
            }
        }
    }

    private fun pendingCount() = queue.pendingCount() + BurstJobQueue.get(this).pending.value

    private fun startInForeground(pending: Int): Boolean {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        // Platform call: ServiceCompat masks out types it does not know on newer releases,
        // which reaches the system as type none and is rejected.
        val types = listOf(type, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC).distinct()
        for (t in types) {
            try {
                startForeground(NOTIFICATION_ID, notification(pending), t)
                return true
            } catch (e: RuntimeException) {
                Log.w(TAG, "Foreground start with type $t failed", e)
            }
        }
        return false
    }

    private fun updateNotification(pending: Int) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(pending))
    }

    private fun notification(pending: Int): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.processing_channel_name), NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_image)
            .setContentTitle(getString(R.string.processing_notification_title))
            .setContentText(resources.getQuantityString(R.plurals.processing_notification_text, pending.coerceAtLeast(1), pending.coerceAtLeast(1)))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val TAG = "ProcessingService"
        private const val CHANNEL_ID = "processing"
        private const val NOTIFICATION_ID = 0x0c01
        private const val PENDING_SUFFIX = "_oc"
        private const val EDIT_SUFFIX = "_edit"
        private const val BURST_POLL_MS = 250L

        @Volatile
        private var sharedQueue: ProcessingQueue? = null

        fun queue(context: Context) = sharedQueue ?: synchronized(this) {
            sharedQueue ?: ProcessingQueue(SqliteJobStore(context)).also { sharedQueue = it }
        }

        fun enqueue(context: Context, uri: Uri, lookId: String?, intensity: Float, watermark: String, ops: AiOps? = null) {
            queue(context).enqueue(uri.toString(), lookId, intensity, watermark, ops?.toJson())
            start(context)
        }

        /** Restarts processing left over from a previous process. Call while in the foreground. */
        fun resumeIfPending(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
            runCatching { enqueueOrphanBursts(context) }.onFailure { Log.w(TAG, "Burst recovery scan failed", it) }
            if (queue(context).pendingCount() > 0) start(context)
        }

        /** Bursts archived by a dead process go into the persistent queue to be merged. */
        private fun enqueueOrphanBursts(context: Context) {
            val prefs = LookPreferences(context)
            val look = prefs.lookId.takeIf { LooksRepository.get(context).descriptor(it) != null }
            for (dir in BurstRecovery.orphans(context)) {
                val job = queue(context).enqueue(BurstRecovery.SCHEME + dir.name, look, prefs.intensity, prefs.watermark.key)
                if (job.state == JobState.FAILED || job.state == JobState.DONE) BurstArchive.delete(dir)
            }
        }

        /** Keeps the service in the foreground while multi-frame merges run. */
        fun keepAlive(context: Context) = start(context)

        private fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, ProcessingService::class.java))
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Cannot start processing now; will resume on next launch", e)
            }
        }
    }
}
