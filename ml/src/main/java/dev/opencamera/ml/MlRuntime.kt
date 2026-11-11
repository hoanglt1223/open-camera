/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.ml

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.preference.PreferenceManager
import java.util.Random
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Why a model could not be used. Features map every reason to their classical fallback. */
class MlException(val reason: Reason, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Reason { MISSING, OPEN_FAILED, TIMEOUT, OUT_OF_MEMORY, FAILED, DISABLED }
}

/**
 * Shared model runtime for capture-time features and the editor. Each loaded model owns one
 * thread: GPU delegates must be created and used on the same thread, and a stuck model cannot
 * block another. Every call is bounded by a timeout; a model that times out twice, or runs out
 * of memory, is disabled for the rest of the process.
 */
class MlRuntime private constructor(context: Context) {
    data class Benchmark(val modelId: String, val accelerator: Accelerator, val openMs: Long, val runMs: List<Double>) {
        val medianMs get() = runMs.sorted().let { if (it.isEmpty()) Double.NaN else it[it.size / 2] }
    }

    private class Loaded(val session: InferenceSession, val executor: ExecutorService, val openMs: Long) {
        @Volatile var lastUsed = SystemClock.elapsedRealtime()
    }

    private val app = context.applicationContext
    val registry = ModelRegistry.get(app)
    private val prefs = PreferenceManager.getDefaultSharedPreferences(app)
    private val backends: List<InferenceBackend> = listOf(LiteRtBackend(), OrtBackend())
    private val loaded = HashMap<String, Loaded>()
    private val executors = HashMap<String, ExecutorService>()
    private val strikes = HashMap<String, Int>()
    private val disabled = HashSet<String>()

    private val idleReaper = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ml-idle").apply { isDaemon = true }
    }

    init {
        // Sessions (ORT arenas, GPU delegates) hold hundreds of MB; drop them when the app is
        // hidden or memory is low, and after a model sat unused for a while. They reopen on demand.
        app.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) releaseAll()
            }
            override fun onConfigurationChanged(newConfig: Configuration) = Unit
            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = releaseAll()
        })
        idleReaper.scheduleWithFixedDelay(::releaseIdle, IDLE_CHECK_S, IDLE_CHECK_S, TimeUnit.SECONDS)
    }

    private fun releaseIdle() {
        val now = SystemClock.elapsedRealtime()
        synchronized(this) { loaded.filterValues { now - it.lastUsed > IDLE_RELEASE_MS }.keys.toList() }
            .forEach { Log.i(TAG, "Releasing idle model $it"); release(it) }
    }

    var preference: BackendSelector.Preference
        get() = runCatching {
            BackendSelector.Preference.valueOf(prefs.getString(PREF_ACCELERATOR, null) ?: "AUTO")
        }.getOrDefault(BackendSelector.Preference.AUTO)
        set(value) {
            prefs.edit().putString(PREF_ACCELERATOR, value.name).apply()
            releaseAll()
        }

    /** True when the model is declared, present (bundled or downloaded) and not disabled. */
    fun isAvailable(modelId: String): Boolean {
        val spec = registry.spec(modelId) ?: return false
        return modelId !in disabledSnapshot() && registry.state(spec) == ModelRegistry.State.AVAILABLE
    }

    fun accelerator(modelId: String): Accelerator? = synchronized(this) { loaded[modelId]?.session?.accelerator }

    /** Runs [modelId] on [inputs]. Throws [MlException]; never lets an Error escape. */
    fun run(modelId: String, inputs: List<Tensor>, timeoutMs: Long? = null): List<Tensor> {
        val spec = registry.spec(modelId) ?: throw MlException(MlException.Reason.MISSING, "Unknown model $modelId")
        if (modelId in disabledSnapshot()) throw MlException(MlException.Reason.DISABLED, "$modelId disabled")
        val executor = executorFor(modelId)
        // Opening (and the first-use benchmark) can take seconds on GPU; it has its own,
        // longer limit so a slow first load is not counted against the model.
        try {
            executor.submit<Loaded> { sessionFor(spec) }.get(OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            strike(modelId, 1)
            throw MlException(MlException.Reason.TIMEOUT, " did not open within  ms")
        } catch (e: ExecutionException) {
            throw wrap(modelId, e.cause ?: e)
        }
        // Looked up again on the model thread: a release queued after the open closes the
        // session on this same thread, so the run then reopens it instead of using a closed one.
        val future = executor.submit<List<Tensor>> {
            val l = sessionFor(spec)
            l.lastUsed = SystemClock.elapsedRealtime()
            try { l.session.run(inputs) } finally { l.lastUsed = SystemClock.elapsedRealtime() }
        }
        try {
            return future.get(timeoutMs ?: spec.timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(false)
            strike(modelId, 1)
            throw MlException(MlException.Reason.TIMEOUT, "$modelId took longer than ${timeoutMs ?: spec.timeoutMs} ms")
        } catch (e: ExecutionException) {
            throw wrap(modelId, e.cause ?: e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw MlException(MlException.Reason.FAILED, "Interrupted", e)
        }
    }

    /** [run] that returns null instead of throwing, logging the reason. */
    fun runOrNull(modelId: String, inputs: List<Tensor>, timeoutMs: Long? = null): List<Tensor>? = try {
        run(modelId, inputs, timeoutMs)
    } catch (e: MlException) {
        Log.w(TAG, "Model $modelId unavailable: ${e.reason} ${e.message}")
        null
    }

    /** Times [iterations] runs on random input; used for docs/benchmarks and the debug screen. */
    fun benchmark(modelId: String, iterations: Int = 5, input: List<Tensor>? = null): Benchmark {
        val spec = registry.spec(modelId) ?: throw MlException(MlException.Reason.MISSING, "Unknown model $modelId")
        val inputs = input ?: listOf(randomInput(spec.inputShape))
        val first = executorFor(modelId).submit<Loaded> { sessionFor(spec) }.get()
        val times = ArrayList<Double>()
        repeat(iterations) {
            val t0 = SystemClock.elapsedRealtimeNanos()
            run(modelId, inputs, spec.timeoutMs * 3)
            times += (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
        }
        return Benchmark(modelId, first.session.accelerator, first.openMs, times)
    }

    fun release(modelId: String) {
        val l = synchronized(this) { loaded.remove(modelId) } ?: return
        l.executor.execute { runCatching { l.session.close() } }
    }

    fun releaseAll() {
        synchronized(this) { loaded.keys.toList() }.forEach(::release)
    }

    // --- internals ---

    private fun disabledSnapshot() = synchronized(this) { disabled.toSet() }

    private fun strike(modelId: String, weight: Int) = synchronized(this) {
        val n = (strikes[modelId] ?: 0) + weight
        strikes[modelId] = n
        if (n >= MAX_STRIKES) {
            Log.w(TAG, "Disabling $modelId for this session")
            disabled += modelId
        }
    }

    private fun wrap(modelId: String, e: Throwable): MlException = when (e) {
        is MlException -> e
        is OutOfMemoryError -> {
            release(modelId)
            strike(modelId, MAX_STRIKES)
            MlException(MlException.Reason.OUT_OF_MEMORY, "$modelId out of memory", e)
        }
        else -> MlException(MlException.Reason.FAILED, "$modelId failed: $e", e)
    }

    private fun executorFor(modelId: String) = synchronized(this) {
        executors.getOrPut(modelId) {
            Executors.newSingleThreadExecutor { r -> Thread(r, "ml-$modelId").apply { isDaemon = true } }
        }
    }

    /** Runs on the model's own thread. */
    private fun sessionFor(spec: ModelSpec): Loaded {
        synchronized(this) { loaded[spec.id] }?.let { return it }
        val source = registry.open(spec)
            ?: throw MlException(MlException.Reason.MISSING, "Model ${spec.id} not present or corrupt")
        val pref = preference
        val candidates = BackendSelector.candidates(spec, pref) { acc -> backends.any { it.supports(spec.format, acc) } }
        val key = BackendSelector.cacheKey(Build.FINGERPRINT, spec)
        val cached = prefs.getString(key, null)?.let { runCatching { Accelerator.valueOf(it) }.getOrNull() }
        val order = when {
            pref != BackendSelector.Preference.AUTO -> candidates
            cached != null -> listOf(cached) + candidates
            candidates.size > 1 -> {
                val best = selectByBenchmark(spec, source, candidates)
                prefs.edit().putString(key, best.name).apply()
                listOf(best) + candidates
            }
            else -> candidates
        }.distinct()
        var last: Throwable? = null
        for (acc in order) {
            val backend = backends.firstOrNull { it.supports(spec.format, acc) } ?: continue
            val t0 = SystemClock.elapsedRealtime()
            try {
                val session = backend.open(source, acc)
                val l = Loaded(session, executorFor(spec.id), SystemClock.elapsedRealtime() - t0)
                Log.i(TAG, "Model ${spec.id} on ${backend.name}/$acc, open ${l.openMs} ms")
                synchronized(this) { loaded[spec.id] = l }
                return l
            } catch (e: Throwable) {
                if (e is OutOfMemoryError) throw e
                Log.w(TAG, "Model ${spec.id} cannot open on $acc", e)
                last = e
            }
        }
        throw MlException(MlException.Reason.OPEN_FAILED, "Model ${spec.id} opens on no accelerator", last)
    }

    private fun selectByBenchmark(spec: ModelSpec, source: ModelSource, candidates: List<Accelerator>): Accelerator {
        val input = listOf(randomInput(spec.inputShape))
        var reference: FloatArray? = null
        val trials = ArrayList<BackendSelector.Trial>()
        // CPU first: it is the reference the others are checked against.
        for (acc in listOf(Accelerator.CPU) + candidates.filter { it != Accelerator.CPU }) {
            val backend = backends.firstOrNull { it.supports(spec.format, acc) } ?: continue
            val trial = runCatching {
                backend.open(source, acc).use { s ->
                    s.run(input)
                    val t0 = SystemClock.elapsedRealtimeNanos()
                    val out = s.run(input)
                    val ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
                    val flat = out.first().data
                    if (acc == Accelerator.CPU) reference = flat
                    val cos = reference?.let { Tensor.cosine(it, flat) } ?: 0.0
                    BackendSelector.Trial(acc, true, ms, cos)
                }
            }.getOrElse { BackendSelector.Trial(acc, false, Double.MAX_VALUE, 0.0) }
            Log.i(TAG, "Benchmark ${spec.id} $trial")
            trials += trial
        }
        return BackendSelector.choose(trials)
    }

    companion object {
        private const val TAG = "MlRuntime"
        const val PREF_ACCELERATOR = "ml_accelerator"
        private const val MAX_STRIKES = 2
        private const val OPEN_TIMEOUT_MS = 30_000L
        private const val IDLE_RELEASE_MS = 30_000L
        private const val IDLE_CHECK_S = 10L

        @Volatile
        private var instance: MlRuntime? = null

        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: MlRuntime(context).also { instance = it }
        }

        fun randomInput(shape: IntArray, seed: Long = 7): Tensor {
            val r = Random(seed)
            return Tensor(shape).also { t -> for (i in t.data.indices) t.data[i] = r.nextFloat() }
        }
    }
}
