/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.processing

import android.content.Context
import android.os.Build
import dev.opencamera.app.multiframe.ProcessingQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Multi-frame merges on the shared processing queue. A burst lives in native memory, so it
 * cannot be persisted like a saved photo; merges run one at a time while [ProcessingService]
 * holds the foreground, and the merged JPEG then enters the persistent look/frame queue.
 */
class BurstJobQueue private constructor(private val app: Context) : ProcessingQueue {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val _pending = MutableStateFlow(0)
    override val pending = _pending.asStateFlow()

    override fun submit(job: suspend () -> Unit) {
        _pending.update { it + 1 }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ProcessingService.keepAlive(app)
        scope.launch {
            try {
                job()
            } finally {
                _pending.update { it - 1 }
            }
        }
    }

    companion object {
        @Volatile
        private var instance: BurstJobQueue? = null

        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: BurstJobQueue(context.applicationContext).also { instance = it }
        }
    }
}
