/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.robustness

import android.util.Log

/**
 * Shared fallback helpers: optional features degrade to the baseline (preview + JPEG capture)
 * instead of crashing. See CONTRIBUTING.md, "Robustness".
 */
object FeatureGuard {
    /** Optional feature dropped by a bind attempt, shown to the user once it applies. */
    enum class Dropped { NONE, MULTI_FRAME, LOOK_PREVIEW, BASELINE }

    /** One step of the bind chain: [useCases] is a CameraController use case mask. */
    data class BindAttempt(val useCases: Int, val dropEffect: Boolean, val dropped: Dropped)

    /**
     * Bind attempts in order: everything requested, then without the analysis stream, then
     * also without the preview effect, then the baseline [primaryUseCase] alone. The analysis
     * stream is only optional next to [captureUseCase] (QR mode needs it).
     */
    fun bindChain(
        useCases: Int,
        primaryUseCase: Int,
        analysisUseCase: Int,
        captureUseCase: Int,
    ): List<BindAttempt> {
        val withoutAnalysis = if (useCases and captureUseCase != 0) {
            useCases and analysisUseCase.inv()
        } else {
            useCases
        }
        return listOf(
            BindAttempt(useCases, false, Dropped.NONE),
            BindAttempt(withoutAnalysis, false, Dropped.MULTI_FRAME),
            BindAttempt(withoutAnalysis, true, Dropped.LOOK_PREVIEW),
            BindAttempt(primaryUseCase, true, Dropped.BASELINE),
        ).distinctBy { it.useCases to it.dropEffect }
    }

    /** Runs [block]; on failure logs [what] and returns null so the caller takes its fallback. */
    inline fun <T> attempt(tag: String, what: String, block: () -> T): T? = try {
        block()
    } catch (e: Exception) {
        Log.w(tag, "$what failed, using fallback", e)
        null
    }
}
