/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.ml

/** Whether this build carries an inference runtime; the foss flavor does not. */
const val ML_RUNTIME_INCLUDED = true

internal fun installedBackends(): List<InferenceBackend> = listOf(LiteRtBackend(), OrtBackend())
