/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.ml

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.os.Build
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
            // Inputs change size per call (e.g. inpainting crops); the arena and memory patterns
            // would otherwise keep the largest peak for the life of the session.
            setCPUArenaAllocator(false)
            setMemoryPatternOptimization(false)
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
                is ModelSource.Buffer -> source.buffer.duplicate().let { b ->
                    if (b.isDirect) env.createSession(b, options)
                    else env.createSession(ByteArray(b.remaining()).also { b.get(it) }, options)
                }
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

        // Models with uint8 image inputs (e.g. MI-GAN's pipeline export) get the float tensor
        // rounded and clamped to 0..255; uint8 outputs come back as floats in the same range.
        private val uint8Inputs = inputNames.map { name ->
            (session.inputInfo[name]?.info as? TensorInfo)?.type == OnnxJavaType.UINT8
        }

        private val byteInputs = arrayOfNulls<ByteBuffer>(inputNames.size)

        override fun run(inputs: List<Tensor>): List<Tensor> {
            require(inputs.size == inputNames.size) { "Model takes ${inputNames.size} inputs, got ${inputs.size}" }
            val tensors = inputs.mapIndexed { i, t ->
                val shape = t.shape.map { it.toLong() }.toLongArray()
                inputNames[i] to if (uint8Inputs[i]) {
                    val bytes = byteInputs[i]?.takeIf { it.capacity() >= t.size }
                        ?: ByteBuffer.allocateDirect(t.size).order(ByteOrder.nativeOrder()).also { byteInputs[i] = it }
                    bytes.clear()
                    bytes.limit(t.size)
                    for (v in t.data) bytes.put((v + 0.5f).toInt().coerceIn(0, 255).toByte())
                    bytes.rewind()
                    OnnxTensor.createTensor(env, bytes, shape, OnnxJavaType.UINT8)
                } else {
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(t.data), shape)
                }
            }.toMap()
            try {
                session.run(tensors).use { result ->
                    return result.map { (_, value) ->
                        val ot = value as OnnxTensor
                        val shape = ot.info.shape.map { it.toInt() }.toIntArray()
                        Tensor(shape).also { out ->
                            if (ot.info.type == OnnxJavaType.UINT8) {
                                val b = ot.byteBuffer
                                for (k in out.data.indices) out.data[k] = (b.get(k).toInt() and 0xff).toFloat()
                            } else {
                                ot.floatBuffer.get(out.data)
                            }
                        }
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
