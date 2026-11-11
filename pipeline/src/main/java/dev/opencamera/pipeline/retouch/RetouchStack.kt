/*
 * SPDX-FileCopyrightText: Open Camera contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package dev.opencamera.pipeline.retouch

import android.graphics.Bitmap
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/** One applied edit. */
data class RetouchOp(val tool: String, val params: RetouchParams) {
    fun toJson(): JSONObject = JSONObject().put("tool", tool).put("params", params.toJson())

    companion object {
        fun fromJson(o: JSONObject) = RetouchOp(o.getString("tool"), RetouchParams.fromJson(o.getJSONObject("params")))
    }
}

/**
 * Non-destructive edit list with undo/redo. Only data: the ops in order and how many are
 * applied. Serialized next to the photo (or in a processing job) and replayed on the
 * original at any resolution.
 */
class RetouchStack(ops: List<RetouchOp> = emptyList(), applied: Int = ops.size) {
    private val ops = ops.toMutableList()
    var applied: Int = applied.coerceIn(0, ops.size)
        private set

    val active: List<RetouchOp> get() = ops.subList(0, applied).toList()
    val canUndo get() = applied > 0
    val canRedo get() = applied < ops.size

    /** Adds [op] after the applied ones; anything that was undone is dropped. */
    fun push(op: RetouchOp) {
        while (ops.size > applied) ops.removeAt(ops.size - 1)
        ops += op
        applied = ops.size
    }

    fun undo(): RetouchOp? = if (canUndo) ops[--applied] else null
    fun redo(): RetouchOp? = if (canRedo) ops[applied++] else null

    fun toJson(): String = JSONObject().apply {
        put("version", VERSION)
        put("applied", applied)
        put("ops", JSONArray().apply { ops.forEach { put(it.toJson()) } })
    }.toString()

    companion object {
        const val VERSION = 1

        /** Null for malformed or newer-version JSON. */
        fun fromJson(json: String?): RetouchStack? {
            if (json.isNullOrBlank()) return null
            return runCatching {
                val o = JSONObject(json)
                if (o.optInt("version", 0) > VERSION) return null
                val a = o.getJSONArray("ops")
                val ops = (0 until a.length()).map { RetouchOp.fromJson(a.getJSONObject(it)) }
                RetouchStack(ops, o.optInt("applied", ops.size))
            }.getOrNull()
        }
    }
}

/**
 * Live editing state for a host screen: the working bitmap, the [RetouchStack] and undo
 * patches. Undo restores a patch; when patches were dropped to stay within [undoBudgetBytes]
 * the image is rebuilt from [original] by replay. Not thread-safe: use from one worker.
 */
class RetouchSession(
    private val original: Bitmap,
    private val toolbox: RetouchToolbox,
    private val undoBudgetBytes: Long = 192L shl 20,
) {
    val stack = RetouchStack()
    var current: Bitmap = original.copy(Bitmap.Config.ARGB_8888, true)
        private set
    private val patches = ArrayList<UndoPatch?>()

    val tags: Set<String> get() = stack.active.flatMapTo(HashSet()) { toolbox.tagsOf(it) }

    /** Applies [params] with [tool]; on failure the image is unchanged and the error is thrown. */
    fun apply(tool: RetouchTool, params: RetouchParams): RetouchResult {
        val result = tool.apply(current, params)
        current = result.bitmap
        stack.push(RetouchOp(tool.id, result.resolved ?: params))
        while (patches.size > stack.applied - 1) patches.removeAt(patches.size - 1)
        patches += result.undo
        trimPatches()
        return result
    }

    /** Puts an already computed result (from the comparison grid) on the stack. */
    fun accept(tool: RetouchTool, params: RetouchParams, result: RetouchResult) {
        current = result.bitmap
        stack.push(RetouchOp(tool.id, result.resolved ?: params))
        while (patches.size > stack.applied - 1) patches.removeAt(patches.size - 1)
        patches += result.undo
        trimPatches()
    }

    fun undo(): Boolean {
        stack.undo() ?: return false
        val patch = patches.getOrNull(stack.applied)
        current = if (patch != null) patch.restore(current) else rebuild()
        patches[stack.applied] = null
        return true
    }

    fun redo(): Boolean {
        val op = stack.redo() ?: return false
        val tool = toolbox.tool(op.tool) ?: return false
        val result = runCatching { tool.apply(current, op.params) }.getOrElse {
            Log.w(TAG, "Redo of ${op.tool} failed", it)
            stack.undo()
            return false
        }
        current = result.bitmap
        patches[stack.applied - 1] = result.undo
        trimPatches()
        return true
    }

    private fun rebuild(): Bitmap {
        val base = original.copy(Bitmap.Config.ARGB_8888, true)
        val rebuilt = toolbox.replay(base, stack.active).bitmap
        for (i in patches.indices) patches[i] = null
        return rebuilt
    }

    private fun trimPatches() {
        var total = patches.sumOf { it?.bytes ?: 0L }
        for (i in patches.indices) {
            if (total <= undoBudgetBytes) break
            val p = patches[i] ?: continue
            total -= p.bytes
            patches[i] = null
        }
    }

    private companion object {
        const val TAG = "RetouchSession"
    }
}
