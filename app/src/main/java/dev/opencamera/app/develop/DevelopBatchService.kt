/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.develop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.opencamera.app.R
import dev.opencamera.app.develop.export.ExportOptions
import dev.opencamera.app.develop.export.Exporter
import dev.opencamera.app.develop.io.SourceLoader
import dev.opencamera.app.develop.mask.AiMaskProvider
import dev.opencamera.app.develop.model.EditStack
import dev.opencamera.app.develop.model.EditStore
import dev.opencamera.app.develop.model.Group
import dev.opencamera.app.develop.model.StackCodec
import dev.opencamera.app.processing.LooksRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Applies a set of edits (a preset or copied edits) to many photos in the background, with a
 * progress notification and a cancel action. Each photo keeps its own non-destructive stack and
 * gets a new exported copy; failures are counted and skipped.
 */
class DevelopBatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var cancelled = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelled = true
            return START_NOT_STICKY
        }
        val uris = intent?.getStringArrayListExtra(EXTRA_URIS)?.map(Uri::parse).orEmpty()
        val stack = runCatching { StackCodec.decode(intent?.getStringExtra(EXTRA_STACK) ?: "") }.getOrNull()
        val groups = intent?.getStringArrayListExtra(EXTRA_GROUPS)?.mapNotNull { runCatching { Group.valueOf(it) }.getOrNull() }?.toSet()
            ?: Group.entries.toSet()
        if (uris.isEmpty() || stack == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!foreground(0, uris.size)) {
            stopSelf()
            return START_NOT_STICKY
        }
        cancelled = false
        scope.launch { run(uris, stack, groups, intent?.getBooleanExtra(EXTRA_MASKS, false) == true) }
        return START_NOT_STICKY
    }

    private fun run(uris: List<Uri>, edits: EditStack, groups: Set<Group>, masks: Boolean) {
        val loader = SourceLoader(this)
        val store = EditStore(File(filesDir, "edits"))
        val exporter = Exporter(this)
        val ai = AiMaskProvider(this)
        var done = 0
        var saved = 0
        for (uri in uris) {
            if (cancelled) break
            try {
                val info = loader.info(uri)
                val base = store.load(info.ref)?.stack ?: EditStack(source = info.ref)
                val stack = base.pasteFrom(edits, groups, masks)
                store.save(info.ref, stack)
                val look = LooksRepository.get(this).params(stack.lookId, 1f)
                val assets = store.assetDir(info.ref)
                exporter.export(info, stack, ExportOptions(), look, { ai.load(it, assets) }, null) { cancelled }
                saved++
            } catch (e: Throwable) {
                if (e is OutOfMemoryError) System.gc()
                Log.w(TAG, "Batch item $uri failed", e)
            }
            done++
            foreground(done, uris.size)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_DONE, NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_image)
            .setContentTitle(getString(R.string.develop_batch_title))
            .setContentText(resources.getQuantityString(R.plurals.develop_batch_done, saved, saved))
            .setAutoCancel(true)
            .build())
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun foreground(done: Int, total: Int): Boolean = try {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.develop_batch_title), NotificationManager.IMPORTANCE_LOW))
        }
        val cancel = PendingIntent.getService(this, 1, Intent(this, DevelopBatchService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_image)
            .setContentTitle(getString(R.string.develop_batch_title))
            .setContentText(resources.getQuantityString(R.plurals.develop_batch_progress, total, done, total))
            .setProgress(total, done, false)
            .setOngoing(true)
            .addAction(0, getString(R.string.develop_batch_cancel), cancel)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        else if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIFICATION, n, type)
        true
    } catch (e: Exception) {
        Log.w(TAG, "Cannot start foreground", e)
        false
    }

    override fun onDestroy() {
        cancelled = true
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DevelopBatch"
        private const val CHANNEL = "develop_batch"
        private const val NOTIFICATION = 7301
        private const val NOTIFICATION_DONE = 7302
        private const val ACTION_CANCEL = "dev.opencamera.app.develop.CANCEL_BATCH"
        private const val EXTRA_URIS = "uris"
        private const val EXTRA_STACK = "stack"
        private const val EXTRA_GROUPS = "groups"
        private const val EXTRA_MASKS = "masks"

        fun start(context: Context, uris: List<Uri>, stack: EditStack, groups: Set<Group>, masks: Boolean = false) {
            val i = Intent(context, DevelopBatchService::class.java)
                .putStringArrayListExtra(EXTRA_URIS, ArrayList(uris.map { it.toString() }))
                .putExtra(EXTRA_STACK, StackCodec.encode(stack.copy(source = null)))
                .putStringArrayListExtra(EXTRA_GROUPS, ArrayList(groups.map { it.name }))
                .putExtra(EXTRA_MASKS, masks)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }
    }
}
