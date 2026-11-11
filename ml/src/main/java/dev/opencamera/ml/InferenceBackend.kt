/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.ml

import java.io.Closeable

/** One loaded model on one accelerator. Not thread-safe; [MlRuntime] serializes calls. */
interface InferenceSession : Closeable {
    val accelerator: Accelerator
    fun run(inputs: List<Tensor>): List<Tensor>
}

/** A runtime (LiteRT, ONNX Runtime) able to open [ModelFormat] models on some accelerators. */
interface InferenceBackend {
    val name: String
    fun supports(format: ModelFormat, accelerator: Accelerator): Boolean

    /** Throws when the model or accelerator cannot be used; callers fall back to the next option. */
    fun open(source: ModelSource, accelerator: Accelerator): InferenceSession
}
