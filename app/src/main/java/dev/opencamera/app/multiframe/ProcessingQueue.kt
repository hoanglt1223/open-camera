/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.multiframe

import kotlinx.coroutines.flow.StateFlow

/**
 * Background work after the shutter returns. Implemented by the shared processing queue
 * (`dev.opencamera.app.processing.BurstJobQueue`).
 */
interface ProcessingQueue {
    /** Number of jobs queued or running. */
    val pending: StateFlow<Int>

    fun submit(job: suspend () -> Unit)
}
