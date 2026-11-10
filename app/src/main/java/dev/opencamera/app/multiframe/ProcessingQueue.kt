/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.multiframe

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Background work after the shutter returns. Kept minimal so it can be swapped for the shared
 * capture queue later.
 */
interface ProcessingQueue {
    /** Number of jobs queued or running. */
    val pending: StateFlow<Int>

    fun submit(job: suspend () -> Unit)
}

/** Runs jobs one at a time on a background dispatcher, independent of any UI lifecycle. */
class SerialProcessingQueue : ProcessingQueue {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val count = AtomicInteger(0)
    private val _pending = MutableStateFlow(0)
    override val pending = _pending.asStateFlow()

    override fun submit(job: suspend () -> Unit) {
        _pending.value = count.incrementAndGet()
        scope.launch {
            try {
                job()
            } finally {
                _pending.value = count.decrementAndGet()
            }
        }
    }

    companion object {
        val shared: ProcessingQueue by lazy { SerialProcessingQueue() }
    }
}
