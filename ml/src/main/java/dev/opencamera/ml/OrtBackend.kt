/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.os.Build
import java.nio.FloatBuffer

/** ONNX Runtime Mobile: CPU provider, NNAPI execution provider below Android 15. */
class OrtBackend(private val threads: Int = 4) : InferenceBackend {
    override val name = "onnxruntime"

    override fun supports(format: ModelFormat, accelerator: Accelerator) = format == ModelFormat.ONNX && when (accelerator) {
        Accelerator.CPU -> true
        Accelerator.GPU -> false
        Accelerator.NNAPI -> Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM
    }

    override fun open(source: ModelSource, accelerator: Accelerator): InferenceSession {
        val env = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            when (accelerator) {
                // Default CPU provider (MLAS). The XNNPACK provider aborted the process on
                // U2-Net-p on SM7225 (uncaught native exception), so it is not used.
                Accelerator.CPU -> setIntraOpNumThreads(threads)
                Accelerator.NNAPI -> addNnapi()
                Accelerator.GPU -> error("No GPU provider in ONNX Runtime Mobile")
            }
        }
        val session = try {
            when (source) {
                is ModelSource.Path -> env.createSession(source.file.path, options)
                is ModelSource.Buffer -> env.createSession(source.buffer.duplicate().let { b ->
                    ByteArray(b.remaining()).also { b.get(it) }
                }, options)
            }
        } catch (e: Throwable) {
            options.close()
            throw IllegalStateException("ONNX Runtime cannot open model on $accelerator", e)
        }
        return Session(env, session, options, accelerator)
    }

    private class Session(
        private val env: OrtEnvironment,
        private val session: OrtSession,
        private val options: OrtSession.SessionOptions,
        override val accelerator: Accelerator,
    ) : InferenceSession {
        private val inputNames = session.inputNames.toList()

        override fun run(inputs: List<Tensor>): List<Tensor> {
            require(inputs.size == inputNames.size) { "Model takes ${inputNames.size} inputs, got ${inputs.size}" }
            val tensors = inputs.mapIndexed { i, t ->
                inputNames[i] to OnnxTensor.createTensor(env, FloatBuffer.wrap(t.data), t.shape.map { it.toLong() }.toLongArray())
            }.toMap()
            try {
                session.run(tensors).use { result ->
                    return result.map { (_, value) ->
                        val ot = value as OnnxTensor
                        val shape = ot.info.shape.map { it.toInt() }.toIntArray()
                        Tensor(shape).also { ot.floatBuffer.get(it.data) }
                    }
                }
            } finally {
                tensors.values.forEach { it.close() }
            }
        }

        override fun close() {
            session.close()
            options.close()
        }
    }
}
