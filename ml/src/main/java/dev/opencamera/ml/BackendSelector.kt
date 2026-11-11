/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.ml

/**
 * Picks the accelerator for a model. The first use on a device benchmarks every candidate
 * against the CPU result; the winner is cached per device fingerprint and model hash so later
 * launches skip the benchmark. Pure logic: no Android dependencies.
 */
object BackendSelector {
    /** User override from Settings; [AUTO] means benchmark. */
    enum class Preference { AUTO, CPU, GPU, NNAPI }

    data class Trial(val accelerator: Accelerator, val ok: Boolean, val ms: Double, val cosine: Double)

    const val MIN_COSINE = 0.99

    /**
     * Accelerators to try, in order. A forced preference goes first; CPU is always last so
     * a model that opens anywhere opens on CPU.
     */
    fun candidates(
        spec: ModelSpec,
        preference: Preference,
        supported: (Accelerator) -> Boolean,
    ): List<Accelerator> {
        val declared = spec.accelerators.filter(supported)
        val forced = when (preference) {
            Preference.AUTO -> null
            Preference.CPU -> Accelerator.CPU
            Preference.GPU -> Accelerator.GPU
            Preference.NNAPI -> Accelerator.NNAPI
        }?.takeIf { supported(it) }
        return (listOfNotNull(forced) + declared + Accelerator.CPU).distinct()
    }

    /** Fastest accelerator whose output matches the CPU reference; CPU when nothing else qualifies. */
    fun choose(trials: List<Trial>): Accelerator =
        trials.filter { it.ok && (it.accelerator == Accelerator.CPU || it.cosine >= MIN_COSINE) }
            .minByOrNull { it.ms }?.accelerator ?: Accelerator.CPU

    fun cacheKey(fingerprint: String, spec: ModelSpec) = "ml.backend.${spec.id}.${spec.sha256.take(12)}.${fingerprint.hashCode()}"
}
