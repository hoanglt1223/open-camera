/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.ml

import android.os.Build
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Delegate
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** LiteRT (TensorFlow Lite): XNNPACK on CPU, OpenCL/GL GPU delegate, NNAPI below Android 15. */
class LiteRtBackend(private val threads: Int = 4) : InferenceBackend {
    override val name = "litert"

    override fun supports(format: ModelFormat, accelerator: Accelerator) = format == ModelFormat.TFLITE && when (accelerator) {
        Accelerator.CPU -> true
        Accelerator.GPU -> gpuSupported
        // NNAPI is deprecated from Android 15 and often slower than CPU on older Qualcomm drivers.
        Accelerator.NNAPI -> Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM
    }

    override fun open(source: ModelSource, accelerator: Accelerator): InferenceSession {
        val options = Interpreter.Options()
        var delegate: Delegate? = null
        when (accelerator) {
            Accelerator.CPU -> options.setNumThreads(threads).setUseXNNPACK(true)
            Accelerator.GPU -> {
                delegate = GpuDelegate(CompatibilityList().use { it.bestOptionsForThisDevice })
                options.addDelegate(delegate)
            }
            Accelerator.NNAPI -> {
                delegate = NnApiDelegate()
                options.addDelegate(delegate)
            }
        }
        val interpreter = try {
            when (source) {
                is ModelSource.Buffer -> Interpreter(source.buffer, options)
                is ModelSource.Path -> Interpreter(source.file, options)
            }
        } catch (e: Throwable) {
            (delegate as? AutoCloseable)?.close()
            throw IllegalStateException("LiteRT cannot open model on $accelerator", e)
        }
        return Session(interpreter, delegate, accelerator)
    }

    private class Session(
        private val interpreter: Interpreter,
        private val delegate: Delegate?,
        override val accelerator: Accelerator,
    ) : InferenceSession {
        private val inCache = HashMap<Int, ByteBuffer>()
        private val outCache = HashMap<Int, ByteBuffer>()

        private fun buffer(cache: HashMap<Int, ByteBuffer>, i: Int, bytes: Int): ByteBuffer {
            val b = cache[i]?.takeIf { it.capacity() >= bytes }
                ?: ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder()).also { cache[i] = it }
            b.clear()
            b.limit(bytes)
            return b
        }

        override fun run(inputs: List<Tensor>): List<Tensor> {
            require(inputs.size == interpreter.inputTensorCount) {
                "Model takes ${interpreter.inputTensorCount} inputs, got ${inputs.size}"
            }
            var resized = false
            inputs.forEachIndexed { i, t ->
                if (!interpreter.getInputTensor(i).shape().contentEquals(t.shape)) {
                    interpreter.resizeInput(i, t.shape)
                    resized = true
                }
            }
            if (resized) interpreter.allocateTensors()
            val inBuffers = inputs.mapIndexed { i, t -> encode(interpreter.getInputTensor(i), t, buffer(inCache, i, interpreter.getInputTensor(i).numBytes())) }.toTypedArray<Any>()
            val outBuffers = HashMap<Int, Any>()
            for (i in 0 until interpreter.outputTensorCount) {
                val o = interpreter.getOutputTensor(i)
                outBuffers[i] = buffer(outCache, i, o.numBytes())
            }
            interpreter.runForMultipleInputsOutputs(inBuffers, outBuffers)
            return (0 until interpreter.outputTensorCount).map { i ->
                decode(interpreter.getOutputTensor(i), outBuffers[i] as ByteBuffer)
            }
        }

        override fun close() {
            interpreter.close()
            (delegate as? AutoCloseable)?.close()
        }

        private fun encode(spec: org.tensorflow.lite.Tensor, t: Tensor, buf: ByteBuffer): ByteBuffer {
            when (spec.dataType()) {
                DataType.FLOAT32 -> buf.asFloatBuffer().put(t.data)
                DataType.UINT8, DataType.INT8 -> {
                    val q = spec.quantizationParams()
                    val signed = spec.dataType() == DataType.INT8
                    val lo = if (signed) -128 else 0
                    val hi = if (signed) 127 else 255
                    for (v in t.data) {
                        val raw = if (q.scale == 0f) v.roundToInt() else (v / q.scale + q.zeroPoint).roundToInt()
                        buf.put(raw.coerceIn(lo, hi).toByte())
                    }
                }
                else -> error("Unsupported input type ${spec.dataType()}")
            }
            buf.rewind()
            return buf
        }

        private fun decode(spec: org.tensorflow.lite.Tensor, buf: ByteBuffer): Tensor {
            buf.rewind()
            val shape = spec.shape()
            val out = Tensor(shape)
            when (spec.dataType()) {
                DataType.FLOAT32 -> buf.asFloatBuffer().get(out.data)
                DataType.UINT8, DataType.INT8 -> {
                    val q = spec.quantizationParams()
                    val signed = spec.dataType() == DataType.INT8
                    for (i in out.data.indices) {
                        val raw = buf.get().toInt().let { if (signed) it else it and 0xff }
                        out.data[i] = if (q.scale == 0f) raw.toFloat() else (raw - q.zeroPoint) * q.scale
                    }
                }
                else -> error("Unsupported output type ${spec.dataType()}")
            }
            return out
        }
    }

    private companion object {
        val gpuSupported: Boolean by lazy {
            runCatching { CompatibilityList().use { it.isDelegateSupportedOnThisDevice } }.getOrDefault(false)
        }
    }
}
