/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.ml

import kotlin.math.sqrt

/** Dense float tensor, row-major. Backends convert to/from their own types at the boundary. */
class Tensor(val shape: IntArray, val data: FloatArray = FloatArray(shape.fold(1) { a, b -> a * b })) {
    init {
        require(shape.fold(1) { a, b -> a * b } == data.size) {
            "Shape ${shape.contentToString()} does not match ${data.size} values"
        }
    }

    val size get() = data.size

    override fun toString() = "Tensor${shape.contentToString()}"

    companion object {
        /** Cosine similarity, used to check that two backends agree on the same input. */
        fun cosine(a: FloatArray, b: FloatArray): Double {
            require(a.size == b.size) { "Size mismatch ${a.size} vs ${b.size}" }
            var dot = 0.0
            var na = 0.0
            var nb = 0.0
            for (i in a.indices) {
                dot += a[i].toDouble() * b[i]
                na += a[i].toDouble() * a[i]
                nb += b[i].toDouble() * b[i]
            }
            if (na == 0.0 && nb == 0.0) return 1.0
            if (na == 0.0 || nb == 0.0) return 0.0
            return dot / (sqrt(na) * sqrt(nb))
        }
    }
}
