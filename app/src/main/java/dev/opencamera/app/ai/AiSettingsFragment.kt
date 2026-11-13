/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.app.ai

import android.os.Bundle
import android.text.format.Formatter
import android.util.Log
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import dev.opencamera.app.R
import dev.opencamera.app.SettingsActivity
import dev.opencamera.ml.ML_RUNTIME_INCLUDED
import dev.opencamera.ml.MlRuntime
import dev.opencamera.ml.ModelDownloader
import dev.opencamera.ml.ModelRegistry
import dev.opencamera.ml.ModelSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** ML features, accelerator choice, optional model downloads and an on-device benchmark. */
class AiSettingsFragment : SettingsActivity.SettingsFragment(R.xml.ai_preferences) {
    private val runtime by lazy { MlRuntime.get(requireContext()) }
    private val busy = HashSet<String>()

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)
        if (!ML_RUNTIME_INCLUDED) {
            preferenceScreen.removeAll()
            preferenceScreen.addPreference(Preference(requireContext()).apply {
                summary = getString(R.string.ai_not_in_build)
                isIconSpaceReserved = false
                isPersistent = false
                isSelectable = false
            })
            return
        }
        findPreference<ListPreference>(MlRuntime.PREF_ACCELERATOR)?.setOnPreferenceChangeListener { _, _ ->
            runtime.releaseAll()
            true
        }
        val models = findPreference<PreferenceCategory>("ai_models") ?: return
        for (spec in runtime.registry.specs.values.sortedBy { it.id }) {
            models.addPreference(Preference(requireContext()).apply {
                key = "ai_model_${spec.id}"
                title = spec.id
                isIconSpaceReserved = false
                isPersistent = false
                updateSummary(this, spec)
                if (!spec.bundled) setOnPreferenceClickListener { toggle(this, spec); true }
            })
        }
        findPreference<Preference>("ai_benchmark")?.setOnPreferenceClickListener { benchmark(it); true }
    }

    private fun updateSummary(p: Preference, spec: ModelSpec, extra: String? = null) {
        val size = Formatter.formatShortFileSize(requireContext(), spec.sizeBytes)
        val state = when {
            spec.bundled -> getString(R.string.ai_model_bundled)
            else -> when (runtime.registry.state(spec)) {
                ModelRegistry.State.AVAILABLE -> getString(R.string.ai_model_downloaded)
                ModelRegistry.State.NOT_DOWNLOADED -> getString(R.string.ai_model_not_downloaded)
                ModelRegistry.State.CORRUPT -> getString(R.string.ai_model_corrupt)
            }
        }
        p.summary = listOfNotNull(extra ?: state, "$size · ${spec.license}").joinToString("\n")
    }

    private fun toggle(p: Preference, spec: ModelSpec) {
        if (!busy.add(spec.id)) return
        val downloader = ModelDownloader(runtime.registry)
        val available = runtime.registry.state(spec) == ModelRegistry.State.AVAILABLE
        viewLifecycleOwner.lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching {
                    if (available) {
                        runtime.release(spec.id)
                        downloader.delete(spec)
                    } else {
                        var lastPct = -1
                        downloader.download(spec) { bytes, total ->
                            val pct = (bytes * 100 / total.coerceAtLeast(1)).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                activity?.runOnUiThread {
                                    if (isAdded) updateSummary(p, spec, getString(R.string.ai_model_downloading, pct))
                                }
                            }
                        }
                    }
                }.exceptionOrNull()
            }
            busy -= spec.id
            if (!isAdded) return@launch
            updateSummary(p, spec, error?.let { getString(R.string.ai_model_download_failed, it.message ?: it.javaClass.simpleName) })
        }
    }

    private fun benchmark(p: Preference) {
        if (!busy.add("benchmark")) return
        p.summary = getString(R.string.ai_benchmark_running)
        val dir = requireContext().filesDir
        viewLifecycleOwner.lifecycleScope.launch {
            val lines = withContext(Dispatchers.Default) {
                runtime.registry.specs.values.sortedBy { it.id }.filter { runtime.isAvailable(it.id) }.map { spec ->
                    runCatching {
                        val b = runtime.benchmark(spec.id, iterations = if (spec.sizeBytes > 50_000_000) 2 else 5)
                        "%s %s open=%d ms median=%.1f ms runs=%s".format(
                            spec.id, b.accelerator, b.openMs, b.medianMs, b.runMs.joinToString("/") { "%.0f".format(it) },
                        )
                    }.getOrElse { "${spec.id} failed: ${it.message}" }.also { Log.i(TAG, "MlBench $it") }
                }
            }
            runCatching { File(dir, "ml-benchmark.txt").writeText(lines.joinToString("\n") + "\n") }
            busy -= "benchmark"
            if (isAdded) p.summary = lines.joinToString("\n")
        }
    }

    private companion object {
        const val TAG = "AiSettings"
    }
}
